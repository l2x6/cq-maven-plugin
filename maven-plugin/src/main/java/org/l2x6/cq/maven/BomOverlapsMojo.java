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
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
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
     * If not specified, the BOM {@code poms/bom/pom.xml} in the current project will be compared with
     * {@code io.quarkus:quarkus-bom:${quarkus.version}}.
     *
     * @since 2.19.0
     */
    @Parameter(property = "cq.compare")
    String compare;

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
        if (compare == null || compare.isEmpty()) {
            baseBomPath = basedir.toPath().resolve("poms/bom/pom.xml");
            compareBomPath = CqCommonUtils.resolveArtifact(localRepositoryPath, "io.quarkus", "quarkus-bom",
                    quarkusVersion,
                    "pom", repositories, repoSystem, repoSession);
        } else {
            final String delim = "..";
            final int delimPos = compare.indexOf(delim);
            if (delimPos <= 0) {
                throw new IllegalStateException("Expected compare delimited by '..': found '" + compare + "'");
            }
            final Gav baseGav = Gav.of(compare.substring(0, delimPos));
            final Gav compareGav = Gav.of(compare.substring(delimPos + delim.length()));
            baseBomPath = CqCommonUtils.resolveArtifact(localRepositoryPath, baseGav.getGroupId(), baseGav.getArtifactId(),
                    baseGav.getVersion(),
                    "pom", repositories, repoSystem, repoSession);
            compareBomPath = CqCommonUtils.resolveArtifact(localRepositoryPath, compareGav.getGroupId(),
                    compareGav.getArtifactId(),
                    compareGav.getVersion(),
                    "pom", repositories, repoSystem, repoSession);
        }

        final Set<Ga> baseGas = toGas(baseBomPath);
        final Set<Ga> compareGas = toGas(compareBomPath);

        StringBuilder sb = new StringBuilder();
        baseGas.stream()
                .filter(compareGas::contains)
                .forEach(ga -> sb.append("\n - " + ga));

        if (sb.length() > 0) {
            getLog().warn("The following artifacts are managed in both BOMs:" + sb.toString());
        }

    }

    private Set<Ga> toGas(Path baseBomPath) {
        return CqCommonUtils.readPom(baseBomPath, charset)
                .getDependencyManagement()
                .getDependencies()
                .stream()
                .filter(dep -> !"import".equals(dep.getScope()))
                .map(dep -> new Ga(dep.getGroupId(), dep.getArtifactId()))
                .collect(Collectors.toCollection(TreeSet::new));
    }

}
