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
import java.util.Map.Entry;
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
import org.l2x6.cq.maven.prod.ProdExcludesMojo.CamelEdition;
import org.l2x6.pom.tuner.PomTransformer;
import org.l2x6.pom.tuner.PomTransformer.ContainerElement;
import org.l2x6.pom.tuner.PomTransformer.SimpleElementWhitespace;
import org.l2x6.pom.tuner.PomTransformer.TransformationContext;
import org.l2x6.pom.tuner.model.Ga;
import org.l2x6.pom.tuner.model.Gav;
import org.l2x6.pom.tuner.model.GavSet;
import org.w3c.dom.Document;

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

    /**
     * A map from Camel Quarkus artifactIds to comma separated list of {@code groupId:artifactId} patterns.
     * Used for assigning shaded dependencies to a Camel Quarkus artifact when deciding whether the given transitive
     * needs
     * to get productized.
     *
     * @since 2.18.0
     */
    @Parameter(property = "cq.additionalExtensionDependencies")
    Map<String, String> additionalExtensionDependencies;

    /**
     * How to format simple XML elements ({@code <elem/>}) - with or without space before the slash.
     *
     * @since 2.23.0
     */
    @Parameter(property = "cq.simpleElementWhitespace", defaultValue = "SPACE")
    SimpleElementWhitespace simpleElementWhitespace;

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
        final TreeMap<String, GavSet> additionalDependenciesMap = new TreeMap<>();
        if (additionalExtensionDependencies != null) {
            for (Entry<String, String> en : additionalExtensionDependencies.entrySet()) {
                additionalDependenciesMap.put(en.getKey(), GavSet.builder().includes(en.getValue()).build());
            }
        }

        final Model bomModel = CqCommonUtils.readPom(basedir.toPath().resolve("poms/bom/pom.xml"), charset);

        final Set<Ga> ownManagedGas = new TreeSet<>();
        final Map<String, Set<Ga>> bomGroups = new TreeMap<>();

        final Map<String, Boolean> cqArtifactIds = new TreeMap<>();
        bomModel.getDependencyManagement().getDependencies().stream()
                .peek(dep -> {
                    if (!"import".equals(dep.getScope())) {
                        final Ga ga = new Ga(dep.getGroupId(), dep.getArtifactId());
                        ownManagedGas.add(ga);
                        bomGroups.computeIfAbsent(dep.getVersion(), k -> new TreeSet<>()).add(ga);
                    }
                })
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

        final Set<Ga> allTransitiveGas = toGas(collector.allTransitives);
        final Set<Ga> prodTransitiveGas = toGas(collector.prodTransitives);
        bomModel.getDependencyManagement().getDependencies().stream()
                .filter(dep -> !"import".equals(dep.getScope()))
                .forEach(dep -> {
                    final Ga depGa = new Ga(dep.getGroupId(), dep.getArtifactId());
                    additionalDependenciesMap.entrySet().stream()
                            .filter(en -> en.getValue().contains(dep.getGroupId(), dep.getArtifactId(), dep.getVersion()))
                            .map(Entry::getKey) // artifactId
                            .findFirst()
                            .ifPresent(artifactId -> {
                                final Ga extensionGa = new Ga("org.apache.camel.quarkus", artifactId);
                                if (prodTransitiveGas.contains(extensionGa)) {
                                    prodTransitiveGas.add(depGa);
                                    allTransitiveGas.add(depGa);
                                } else if (allTransitiveGas.contains(extensionGa)) {
                                    allTransitiveGas.add(depGa);
                                }
                            });
                });

        final Map<Ga, Set<ComparableVersion>> multiversionedProdArtifacts = findMultiversionedArtifacts(
                collector.prodTransitives);
        if (!multiversionedProdArtifacts.isEmpty()) {
            getLog().warn("Found dependencies of productized artifacts with multiple versions:");
            multiversionedProdArtifacts.entrySet().forEach(en -> {
                getLog().warn("- " + en.getKey() + ": " + en.getValue());
            });
        }

        /* Ensure that all camel deps are managed */
        final Set<Ga> nonManagedCamelArtifacts = allTransitiveGas.stream()
                .filter(ga -> "org.apache.camel".equals(ga.getGroupId()))
                .filter(ga -> !ownManagedGas.contains(ga))
                .collect(Collectors.toCollection(TreeSet::new));
        final StringBuilder sb = new StringBuilder(
                "Found non-managed Camel artifacts; consider adding the following to camel-quarkus-bom:");
        if (!nonManagedCamelArtifacts.isEmpty()) {
            nonManagedCamelArtifacts.forEach(ga -> sb.append("\n            <dependency>\n                <groupId>")
                    .append(ga.getGroupId())
                    .append("</groupId>\n                <artifactId>")
                    .append(ga.getArtifactId())
                    .append("</artifactId>\n                <version>")
                    .append(prodTransitiveGas.contains(ga) ? "${camel.version}" : "${camel-community.version}")
                    .append("</version>\n            </dependency>"));
            throw new RuntimeException(sb.toString());
        }

        /*
         * For the sake of consistency in end user apps, we manage some artifacts that are not actually used in our
         * extensions. We need to classify these as prod/non-prod too so that PME does not change the versions were we
         * do not want
         */
        bomModel.getDependencyManagement().getDependencies().stream()
                .filter(dep -> !"import".equals(dep.getScope()))
                .filter(dep -> !allTransitiveGas.contains(new Ga(dep.getGroupId(), dep.getArtifactId())))
                .forEach(dep -> {
                    final Ga depGa = new Ga(dep.getGroupId(), dep.getArtifactId());
                    final Set<Ga> gaSet = bomGroups.get(dep.getVersion());
                    if (prodTransitiveGas.stream().anyMatch(gaSet::contains)) {
                        prodTransitiveGas.add(depGa);
                        getLog().debug("   - BOM entry mappable to an otherwise productized group: " + depGa);
                    } else if (allTransitiveGas.stream().anyMatch(gaSet::contains)) {
                        /* Still mappable */
                        getLog().debug("   - BOM entry mappable to an otherwise non-productized group: " + depGa);
                    } else {
                        getLog().warn(" - BOM entry not mappable to any group: " + depGa
                                + " - is it perhaps supperfluous and should be removed from the BOM? Or needs to get assigne to an extension via <additionalExtensionDependencies>?");
                    }
                    allTransitiveGas.add(depGa);
                });

        write(allTransitiveGas, allDependenciesFile.toPath());
        write(prodTransitiveGas, productizedDependenciesFile.toPath());
        final Set<Ga> nonProdTransitives = allTransitiveGas.stream()
                .filter(dep -> !prodTransitiveGas.contains(dep))
                .collect(Collectors.toCollection(TreeSet::new));
        write(nonProdTransitives, nonProductizedDependenciesFile.toPath());

        updateCamelQuarkusBom(prodTransitiveGas);
    }

    void updateCamelQuarkusBom(Set<Ga> prodTransitiveGas) {

        final Path bomPath = basedir.toPath().resolve("poms/bom/pom.xml");
        new PomTransformer(bomPath, charset, simpleElementWhitespace)
                .transform((Document document, TransformationContext context) -> {

                    context.getContainerElement("project", "dependencyManagement", "dependencies").get()
                            .childElementsStream()
                            .map(ContainerElement::asGavtcs)
                            .filter(gavtcs -> gavtcs.getGroupId().equals("org.apache.camel"))
                            .forEach(gavtcs -> {
                                final Ga ga = new Ga(gavtcs.getGroupId(), gavtcs.getArtifactId());
                                final String expectedVersion = prodTransitiveGas.contains(ga)
                                        ? CamelEdition.PRODUCT.getVersionExpression()
                                        : CamelEdition.COMMUNITY.getVersionExpression();
                                if (!expectedVersion.equals(gavtcs.getVersion())) {
                                    gavtcs.getNode().setVersion(expectedVersion);
                                }
                            });
                });
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
