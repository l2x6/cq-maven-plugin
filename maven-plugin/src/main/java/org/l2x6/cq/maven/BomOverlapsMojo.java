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
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.apache.maven.model.Model;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.repository.RemoteRepository;
import org.l2x6.cq.common.CqCommonUtils;
import org.l2x6.pom.tuner.model.Ga;
import org.l2x6.pom.tuner.model.Gav;
import org.l2x6.pom.tuner.model.GavSet;
import org.l2x6.pom.tuner.model.GavSet.IncludeExcludeGavSet.Builder;

/**
 * Compares two BOMs and prints G:A pairs managed in both BOMs.
 *
 * @since 2.19.0
 */
@Mojo(name = "bom-overlaps", threadSafe = true, requiresProject = true)
public class BomOverlapsMojo extends AbstractMojo {

    /**
     * G:A:Vs to compare, delimited by {@code ..}, e.g.
     * {@code -Dcq.compare=org.apache.camel.quarkus:camel-quarkus-bom:1.2.3..io.quarkus:quarkus-bom:3.4.5}.
     * If not specified, the BOM specified in {@link #baseBomPath} will be compared with
     * {@code io.quarkus:quarkus-bom:${quarkus.version}}.
     *
     * @since 2.19.0
     */
    @Parameter(property = "cq.compare")
    String compare;

    /**
     * Path to a {@code pom.xml} file that should serve as a BOM to compare against
     * {@code io.quarkus:quarkus-bom:${quarkus.version}}.
     * Ignored if {@link #compare} is specified.
     *
     * @since 4.20.0
     */
    @Parameter(property = "cq.baseBomPath", defaultValue = "${cq.basedir}/poms/bom/pom.xml")
    File baseBomPath;

    /**
     * Directory where the changes should be performed. Default is the current directory of the current Java process.
     *
     * @since 2.19.0
     */
    @Parameter(property = "cq.basedir", defaultValue = "${project.basedir}")
    File basedir;

    /**
     * Quarkus version.
     *
     * @since 2.19.0
     */
    @Parameter(property = "quarkus.version")
    String quarkusVersion;

    /**
     * A list of {@code groupId:artifactId} patterns; if any of the matching artifacts occurs in both of the compared
     * BOMs, then it will be omitted in the final report.
     *
     * @since 4.20.0
     */
    @Parameter(property = "cq.ignoredOverlaps")
    List<String> ignoredOverlaps;

    /**
     * What to do if the list of overlapping artifacts (ignoring {@link #ignoredOverlaps}) is not empty.
     *
     * @since 4.20.0
     */
    @Parameter(property = "cq.remedy", defaultValue = "fail")
    FailureRemedy remedy;

    /**
     * Encoding to read and write files in the current source tree
     *
     * @since 2.19.0
     */
    @Parameter(defaultValue = CqUtils.DEFAULT_ENCODING, required = true, property = "cq.encoding")
    String encoding;
    Charset charset;

    @Parameter(defaultValue = "${settings.localRepository}", readonly = true)
    String localRepository;

    @Parameter(defaultValue = "${project.remoteProjectRepositories}", readonly = true, required = true)
    List<RemoteRepository> repositories;

    @Component
    private RepositorySystem repoSystem;

    @Parameter(defaultValue = "${repositorySystemSession}", readonly = true, required = true)
    private RepositorySystemSession repoSession;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        charset = Charset.forName(encoding);
        final Path localRepositoryPath = Paths.get(localRepository);

        final Path baseBomPath;
        final Path compareBomPath;
        final Gav baseGav;
        if (compare == null || compare.isEmpty()) {
            baseBomPath = this.baseBomPath.toPath();
            compareBomPath = CqCommonUtils.resolveArtifact(localRepositoryPath, "io.quarkus", "quarkus-bom",
                    quarkusVersion,
                    "pom", repositories, repoSystem, repoSession);
            final Model pom = CqCommonUtils.readPom(baseBomPath, charset);
            baseGav = new Gav(pom.getGroupId(), pom.getArtifactId(), pom.getVersion());
        } else {
            final String delim = "..";
            final int delimPos = compare.indexOf(delim);
            if (delimPos <= 0) {
                throw new IllegalStateException("Expected compare delimited by '..': found '" + compare + "'");
            }
            baseGav = Gav.of(compare.substring(0, delimPos));
            final Gav compareGav = Gav.of(compare.substring(delimPos + delim.length()));
            baseBomPath = CqCommonUtils.resolveArtifact(localRepositoryPath, baseGav.getGroupId(), baseGav.getArtifactId(),
                    baseGav.getVersion(),
                    "pom", repositories, repoSystem, repoSession);
            compareBomPath = CqCommonUtils.resolveArtifact(localRepositoryPath, compareGav.getGroupId(),
                    compareGav.getArtifactId(),
                    compareGav.getVersion(),
                    "pom", repositories, repoSystem, repoSession);
        }
        final GavSet ignored;
        if (ignoredOverlaps != null && !ignoredOverlaps.isEmpty()) {
            final Builder builder = GavSet.builder();
            ignoredOverlaps.stream()
                    .forEach(rawPattern -> {
                        int colonCnt = 0;
                        for (int i = 0; i < rawPattern.length(); i++) {
                            if (rawPattern.charAt(i) == ':') {
                                colonCnt++;
                                if (colonCnt > 1) {
                                    throw new IllegalArgumentException(
                                            "ignoredOverlap '" + rawPattern + "' may not contain more than one colon");
                                }
                            }
                        }
                        builder.include(rawPattern);
                    });
            ignored = builder.build();
        } else {
            ignored = GavSet.excludeAll();
        }

        final Map<Ga, String> baseGas = toGas(baseBomPath);
        final Map<Ga, String> compareGas = toGas(compareBomPath);

        StringBuilder sb = new StringBuilder();
        baseGas.entrySet().stream()
                .filter(en -> compareGas.containsKey(en.getKey()))
                .filter(en -> !ignored.contains(en.getKey()))
                .forEach(en -> {
                    final String baseVersion = en.getValue();
                    final String compareVersion = compareGas.get(en.getKey());
                    sb.append(
                            "\n - "
                                    + en.getKey()
                                    + " "
                                    + (baseVersion.equals(compareVersion)
                                            ? ("✅ " + baseVersion)
                                            : (" " + baseVersion + " ❌ " + compareVersion)));
                });

        final String msg = "\n\nThe following artifacts are managed in both BOMs:" + sb.toString()
                + "\n\nYou may want to either remove the listed artifacts from " + baseGav
                + " or add them to <ignoredOverlaps>\n\n";
        if (sb.length() > 0) {
            switch (remedy) {
            case fail: {
                throw new MojoFailureException(msg);
            }
            case warn: {
                getLog().warn(msg);
                break;
            }
            case ignore: {
                getLog().warn(msg);
                break;
            }
            default:
                throw new IllegalArgumentException("Unexpected FailureRemedy: " + remedy);
            }
        }

    }

    private Map<Ga, String> toGas(Path baseBomPath) {
        Map<Ga, String> result = new TreeMap<>();
        CqCommonUtils.readPom(baseBomPath, charset)
                .getDependencyManagement()
                .getDependencies()
                .stream()
                .filter(dep -> !"import".equals(dep.getScope()))
                .forEach(dep -> result.put(new Ga(dep.getGroupId(), dep.getArtifactId()), dep.getVersion()));
        return Collections.unmodifiableMap(result);
    }

    public enum FailureRemedy {
        fail, warn, ignore
    }

}
