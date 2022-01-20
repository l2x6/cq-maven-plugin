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
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.apache.maven.artifact.versioning.ComparableVersion;
import org.apache.maven.model.Model;
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
import org.l2x6.pom.tuner.model.Ga;
import org.l2x6.pom.tuner.model.Gav;

/**
 * List the transitive dependencies of all, of supported extensions and the rest that neither needs to get productized
 * nor aligned by PNC.
 *
 * @since 2.17.0
 */
@Mojo(name = "transitive-deps", threadSafe = true, requiresProject = true, inheritByDefault = false)
public class TransitiveDependenciesMojo extends AbstractMojo {

    /**
     * The version of the current source tree
     *
     * @since 2.17.0
     */
    @Parameter(property = "cq.version", defaultValue = "${project.version}")
    String version;

    /**
     * The Camel Quarkus community version
     *
     * @since 2.17.0
     */
    @Parameter(property = "camel-quarkus-community.version")
    String camelQuarkusCommunityVersion;

    /**
     * The basedir
     *
     * @since 2.17.0
     */
    @Parameter(property = "cq.basedir", defaultValue = "${project.basedir}")
    File basedir;

    /**
     * Encoding to read and write files in the current source tree
     *
     * @since 2.17.0
     */
    @Parameter(defaultValue = "utf-8", required = true, property = "cq.encoding")
    String encoding;
    Charset charset;

    /**
     * Skip the execution of this mojo.
     *
     * @since 2.17.0
     */
    @Parameter(property = "cq.transitive-deps.skip", defaultValue = "false")
    boolean skip;

    /**
     * Where to write a list of runtime dependencies of all Camel Quarkus productized extensions.
     * It is a text file one artifactId per line.
     *
     * @since 2.17.0
     */
    @Parameter(property = "cq.productizedDependenciesFile", defaultValue = "${basedir}/product/src/main/generated/transitive-dependencies-productized.txt")
    File productizedDependenciesFile;

    /**
     * Where to write a list of runtime dependencies of all Camel Quarkus productized extensions.
     * It is a text file one artifactId per line.
     *
     * @since 2.17.0
     */
    @Parameter(property = "cq.allTransitivesFile", defaultValue = "${basedir}/product/src/main/generated/transitive-dependencies-all.txt")
    File allDependenciesFile;

    /**
     * Where to write a list of runtime dependencies of all Camel Quarkus productized extensions.
     * It is a text file one artifactId per line.
     *
     * @since 2.17.0
     */
    @Parameter(property = "cq.nonProductizedDependenciesFile", defaultValue = "${basedir}/product/src/main/generated/transitive-dependencies-non-productized.txt")
    File nonProductizedDependenciesFile;

    @Parameter(defaultValue = "${project.remoteProjectRepositories}", readonly = true, required = true)
    List<RemoteRepository> repositories;

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

        final Model bomModel = CqCommonUtils.readPom(basedir.toPath().resolve("poms/bom/pom.xml"), charset);

        final Map<String, Boolean> cqArtifactIds = new TreeMap<>();
        bomModel.getDependencyManagement().getDependencies().stream()
                .filter(dep -> dep.getGroupId().equals("org.apache.camel.quarkus"))
                .forEach(dep -> {
                    switch (dep.getVersion()) {
                    case "${camel-quarkus.version}":
                        cqArtifactIds.put(dep.getArtifactId(), true);
                        break;
                    case "${camel-quarkus-community.version}":
                        cqArtifactIds.put(dep.getArtifactId(), false);
                        break;
                    default:
                        throw new IllegalStateException(
                                "Unexpected version of an artifact with groupId 'org.apache.camel.quarkus': " + dep.getVersion()
                                        + "; expected ${camel-quarkus.version} or ${camel-quarkus-community.version}");
                    }
                });

        /*
         * Remove the runtime artifacts from the set, because their -deployment counterparts will pull the runtime deps
         * anyway
         */
        for (Iterator<String> it = cqArtifactIds.keySet().iterator(); it.hasNext();) {
            final String artifactId = it.next();
            if (!artifactId.endsWith("-deployment") && cqArtifactIds.containsKey(artifactId + "-deployment")) {
                it.remove();
            }
        }

        final DependencyCollector collector = new DependencyCollector();

        cqArtifactIds.entrySet().stream()
                .forEach(artifactId -> {

                    final Boolean isProd = artifactId.getValue();
                    final DefaultArtifact artifact = new DefaultArtifact(
                            "org.apache.camel.quarkus",
                            artifactId.getKey(),
                            null,
                            "pom",
                            isProd ? version : camelQuarkusCommunityVersion);

                    final CollectRequest request = new CollectRequest()
                            .setRepositories(repositories)
                            .setRoot(new org.eclipse.aether.graph.Dependency(artifact, null));
                    try {
                        final DependencyNode rootNode = repoSystem.collectDependencies(repoSession, request).getRoot();
                        collector.isProd = isProd;
                        rootNode.accept(collector);
                    } catch (DependencyCollectionException e) {
                        throw new RuntimeException("Could not resolve dependencies", e);
                    }
                });

        final Map<Ga, Set<ComparableVersion>> multiversionedProdArtifacts = findMultiversionedArtifacts(
                collector.prodTransitives);
        if (!multiversionedProdArtifacts.isEmpty()) {
            getLog().warn("Found dependencies of productized artifacts with multiple versions:");
            multiversionedProdArtifacts.entrySet().forEach(en -> {
                System.out.println("- " + en.getKey() + ": " + en.getValue());
            });
        }

        final Set<Ga> allTransitiveGas = toGas(collector.allTransitives);
        write(allTransitiveGas, allDependenciesFile.toPath());
        final Set<Ga> prodTransitiveGas = toGas(collector.prodTransitives);
        write(prodTransitiveGas, productizedDependenciesFile.toPath());
        final Set<Ga> nonProdTransitives = allTransitiveGas.stream()
                .filter(dep -> !prodTransitiveGas.contains(dep))
                .collect(Collectors.toCollection(TreeSet::new));
        write(nonProdTransitives, nonProductizedDependenciesFile.toPath());
    }

    static Set<Ga> toGas(Set<Gav> gavs) {
        return gavs.stream()
                .map(Gav::toGa)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    static Map<Ga, Set<ComparableVersion>> findMultiversionedArtifacts(Set<Gav> prodTransitives) {
        Map<Ga, Set<ComparableVersion>> result = new TreeMap<>();
        prodTransitives.stream()
                .forEach(gav -> {
                    final Ga key = gav.toGa();
                    Set<ComparableVersion> versions = result.computeIfAbsent(key, k -> new TreeSet<>());
                    versions.add(new ComparableVersion(gav.getVersion()));
                });
        for (Iterator<Map.Entry<Ga, Set<ComparableVersion>>> it = result.entrySet().iterator(); it.hasNext();) {
            final Map.Entry<Ga, Set<ComparableVersion>> en = it.next();
            if (en.getValue().size() <= 1) {
                it.remove();
            }
        }
        return result;
    }

    void write(Set<Ga> deps, Path path) {
        try {
            Files.createDirectories(path.getParent());
            Files.write(
                    path,
                    (deps
                            .stream()
                            .map(Ga::toString)
                            .collect(Collectors.joining("\n")) + "\n").getBytes(charset));
        } catch (IOException e) {
            throw new RuntimeException("Could not write to " + path, e);
        }
    }

    static class DependencyCollector implements DependencyVisitor {
        private boolean isProd;

        private final Set<Gav> prodTransitives = new TreeSet<>();
        private final Set<Gav> allTransitives = new TreeSet<>();

        @Override
        public boolean visitLeave(DependencyNode node) {
            return true;
        }

        @Override
        public boolean visitEnter(DependencyNode node) {
            final Artifact a = node.getArtifact();
            final Gav gav = new Gav(a.getGroupId(), a.getArtifactId(), a.getVersion());
            allTransitives.add(gav);
            if (isProd) {
                prodTransitives.add(gav);
            }
            return true;
        }

    }
}
