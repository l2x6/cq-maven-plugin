/**
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
package org.l2x6.cq.maven.prod;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
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
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.collection.DependencyCollectionException;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.graph.DependencyVisitor;
import org.eclipse.aether.repository.RemoteRepository;
import org.l2x6.cq.common.CqCommonUtils;
import org.l2x6.pom.tuner.MavenSourceTree;
import org.l2x6.pom.tuner.model.Dependency;
import org.l2x6.pom.tuner.model.Gav;

/**
 * List the transitive runtime dependencies of all supported extensions.
 *
 * @since 2.16.0
 */
@Mojo(name = "transitive-deps", threadSafe = true, requiresProject = true, inheritByDefault = false)
public class TransitiveDependenciesMojo extends AbstractMojo {
    /**
     * The basedir
     *
     * @since 2.16.0
     */
    @Parameter(property = "cq.version", defaultValue = "${project.version}")
    String version;

    /**
     * The basedir
     *
     * @since 2.16.0
     */
    @Parameter(property = "cq.basedir", defaultValue = "${project.basedir}")
    File basedir;

    /**
     * Encoding to read and write files in the current source tree
     *
     * @since 2.16.0
     */
    @Parameter(defaultValue = "utf-8", required = true, property = "cq.encoding")
    String encoding;
    Charset charset;

    /**
     * Skip the execution of this mojo.
     *
     * @since 2.16.0
     */
    @Parameter(property = "cq.transitive-deps.skip", defaultValue = "false")
    boolean skip;

    /**
     * Where to write a list of runtime dependencies of all Camel Quarkus productized extensions.
     * It is a text file one artifactId per line.
     *
     * @since 2.16.0
     */
    @Parameter(property = "cq.runtimeDependenciesOfSupportedExtensions", defaultValue = "${basedir}/target/all-runtime-dependencies.txt")
    File runtimeDependenciesOfSupportedExtensions;

    /**
     * @since 2.16.0
     */
    @Parameter(defaultValue = "${project.remoteProjectRepositories}", readonly = true, required = true)
    List<RemoteRepository> repositories;

    /**
     * @since 2.16.0
     */
    @Parameter(defaultValue = "${settings.localRepository}", readonly = true)
    String localRepository;

    @Component
    private RepositorySystem repoSystem;

    @Parameter(defaultValue = "${repositorySystemSession}", readonly = true, required = true)
    private RepositorySystemSession repoSession;

    /** {@inheritDoc} */
    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("Skipping as requested by the user");
            return;
        }
        charset = Charset.forName(encoding);
        final MavenSourceTree tree = MavenSourceTree.of(basedir.toPath().resolve("pom.xml"), charset, Dependency::isVirtual);

        final org.eclipse.aether.artifact.Artifact rootArtifact = new DefaultArtifact(
                "org.apache.camel.quarkus",
                "camel-quarkus-all-extensions",
                null,
                "pom",
                version);

        CollectRequest request = new CollectRequest();
        request.setRepositories(repositories);
        request.setRoot(new org.eclipse.aether.graph.Dependency(rootArtifact, null));

        final org.eclipse.aether.artifact.Artifact bomArtifact = new DefaultArtifact(
                "org.apache.camel.quarkus",
                "camel-quarkus-bom",
                null,
                "pom",
                version);
        request.addManagedDependency(new org.eclipse.aether.graph.Dependency(bomArtifact, "import"));

        CqCommonUtils.filterExtensions(tree.getModulesByGa().keySet().stream())
                .forEach(extension -> {
                    request.addDependency(new org.eclipse.aether.graph.Dependency(new DefaultArtifact(
                            extension.getGroupId(),
                            extension.getArtifactId(),
                            null,
                            "jar",
                            version), null));
                });

        try {
            final Set<Gav> result = new TreeSet<>();
            final DependencyNode rootNode = repoSystem.collectDependencies(repoSession, request).getRoot();
            rootNode.accept(new DependencyVisitor() {

                @Override
                public boolean visitLeave(DependencyNode node) {
                    return true;
                }

                @Override
                public boolean visitEnter(DependencyNode node) {
                    final Artifact a = node.getArtifact();
                    result.add(new Gav(a.getGroupId(), a.getArtifactId(), a.getVersion()));
                    return true;
                }
            });
            Files.createDirectories(runtimeDependenciesOfSupportedExtensions.toPath().getParent());
            Files.write(
                    runtimeDependenciesOfSupportedExtensions.toPath(),
                    (result
                            .stream()
                            .map(Gav::toString)
                            .collect(Collectors.joining("\n")) + "\n").getBytes(charset));
        } catch (DependencyCollectionException e) {
            throw new RuntimeException("Could not resolve dependencies", e);
        } catch (IOException e) {
            throw new RuntimeException("Could not write to " + runtimeDependenciesOfSupportedExtensions, e);
        }
    }

}
