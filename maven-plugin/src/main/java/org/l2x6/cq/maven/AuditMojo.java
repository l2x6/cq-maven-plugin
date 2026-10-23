/*
 * Copyright (c) 2020 CQ Maven Plugin
 * project contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.l2x6.cq.maven;

import java.io.File;
import java.nio.charset.Charset;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.DependencyManagement;
import org.apache.maven.model.Model;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.l2x6.cq.common.CqCommonUtils;
import org.l2x6.pom.tuner.model.*;

/**
 * Performs some assertions on the specified pom.xml file.
 *
 * @since 4.29.0
 */
@Mojo(name = "audit", threadSafe = true, requiresProject = true)
public class AuditMojo extends AbstractMojo {

    /**
     * Encoding to read and write files in the current source tree
     *
     * @since 4.29.0
     */
    @Parameter(defaultValue = "${project.build.sourceEncoding}", required = true, property = "project.build.sourceEncoding")
    String encoding;
    Charset charset;

    /**
     * The pom.xml file to audit
     *
     * @since 4.29.0
     */
    @Parameter(defaultValue = "${project.basedir}/pom.xml", property = "cq.pomFile")
    File pomFile;

    /**
     * Require a regular expression match on versions of managed artifacts.
     * <p>
     * Example:
     * {@code '-Dcq.requireManagedVersions=org.apache.cxf.services.sts:*@4\.2\.2' -Dcq.pomFile=target/flattened-reduced-expanded-pom.xml}
     *
     * @since 4.29.0
     */
    @Parameter(property = "cq.requireManagedVersions")
    List<MatchVersion> requireManagedVersions;

    /**
     * Ban managed artifacts matching a regular expression
     * <p>
     * Example:
     * {@code '-Dcq.banManagedVersions=org.apache.cxf.services.sts:*@4\.2\.2' -Dcq.pomFile=target/flattened-reduced-expanded-pom.xml}
     *
     * @since 4.29.0
     */
    @Parameter(property = "cq.banManagedVersions")
    List<MatchVersion> banManagedVersions;

    /**
     * Skip the execution of this mojo.
     *
     * @since 4.29.0
     */
    @Parameter(property = "cq.audit.skip", defaultValue = "false")
    boolean skip;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("Skipping as requested by the user");
            return;
        }
        if (requireManagedVersions == null || requireManagedVersions.isEmpty()) {
            getLog().info("Nothing to do: matchManagedVersions is empty");
        }

        charset = Charset.forName(encoding);

        Model model = CqCommonUtils.readPom(pomFile.toPath(), charset);

        DependencyManagement dm = model.getDependencyManagement();
        if (dm != null) {
            List<Dependency> deps = dm.getDependencies();
            if (deps != null && !deps.isEmpty()) {

                Map<Boolean, Map<String, Set<Gavtcs>>> errors = new LinkedHashMap<>();

                for (Dependency dep : deps) {
                    Gavtcs gavtcs = toGavtcs(dep);
                    Consumer<MatchResult> matchResultConsumer = r -> {
                        errors.computeIfAbsent(r.expected, k -> new LinkedHashMap<>())
                                .computeIfAbsent(r.pattern, k1 -> new LinkedHashSet<>())
                                .add(r.gavtcs);
                    };
                    check(requireManagedVersions, gavtcs, matchResultConsumer, true);
                    check(banManagedVersions, gavtcs, matchResultConsumer, false);
                }

                if (!errors.isEmpty()) {
                    StringBuilder msgs = new StringBuilder();
                    errors.forEach((k, v) -> {
                        String msg = k
                                ? "Managed dependencies must match version pattern '%s': %s"
                                : "Managed dependencies must not match version pattern '%s': %s";
                        v.forEach((pattern, gavtcs) -> {
                            if (!gavtcs.isEmpty()) {
                                if (!msgs.isEmpty()) {
                                    msgs.append('\n');
                                }
                                msgs.append(msg.formatted(pattern,
                                        gavtcs.stream().map(Gavtcs::toString).collect(Collectors.joining(", "))));
                            }
                        });
                    });
                    throw new RuntimeException(msgs.toString());
                }
            }
        }
    }

    record MatchResult(boolean expected, String pattern, Gavtcs gavtcs) {
    }

    static void check(List<MatchVersion> matchVersions, Gavtcs gavtcs, Consumer<MatchResult> errors, boolean expected) {
        for (MatchVersion match : matchVersions) {
            match.check(gavtcs, errors, expected);
        }
    }

    static Gavtcs toGavtcs(Dependency mvnDep) {
        return new Gavtcs(
                mvnDep.getGroupId(),
                mvnDep.getArtifactId(),
                mvnDep.getVersion(),
                Gavtc.Type.of(mvnDep.getType()),
                mvnDep.getClassifier(),
                mvnDep.getScope());
    }

    public static class MatchVersion {
        private GavtcsSet gavtcsPatterns;
        private Pattern versionPattern;

        public MatchVersion() {
        }

        public MatchVersion(String gavtcsPatterns, String versionPattern) {
            if (gavtcsPatterns != null) {
                setGavtcsPatterns(gavtcsPatterns);
            }
            if (versionPattern != null) {
                setVersionPattern(versionPattern);
            }
        }

        private void setVersionPattern(String versionPattern) {
            this.versionPattern = Pattern.compile(versionPattern);
        }

        public void set(String serialized) {
            if (serialized == null || serialized.isEmpty()) {
                return;
            }
            int atPos = serialized.indexOf('@');

            final String gavPatterns;
            final String versionPattern;
            if (atPos >= 0) {
                gavPatterns = serialized.substring(0, atPos);
                versionPattern = serialized.substring(atPos + 1);
            } else {
                throw new IllegalArgumentException(
                        "Could not parse VersionMatch '" + serialized + "': must contain @");
            }
            setGavtcsPatterns(gavPatterns);
            setVersionPattern(versionPattern);
        }

        private void setGavtcsPatterns(String gavtcsPatterns) {
            this.gavtcsPatterns = GavtcsSet.builder().includes(gavtcsPatterns).build();
        }

        public void check(Gavtcs gavtcs, Consumer<MatchResult> errors, boolean expected) {
            if (gavtcsPatterns.contains(gavtcs)) {
                if (versionPattern.matcher(gavtcs.getVersion()).matches() != expected) {
                    errors.accept(new MatchResult(expected, versionPattern.pattern(), gavtcs));
                }
            }
        }

        @Override
        public String toString() {
            return gavtcsPatterns + "@" + versionPattern.pattern();
        }
    }
}
