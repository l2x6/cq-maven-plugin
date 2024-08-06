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
package org.l2x6.cq.maven.prod;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.maven.artifact.versioning.ComparableVersion;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Model;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.ProjectBuilder;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.collection.DependencyCollectionException;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.graph.DependencyVisitor;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;
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
public class TransitiveDependenciesMojo {

    /**
     * The version of the current source tree
     *
     * @since 2.17.0
     */
    private final String version;

    /**
     * Camel Quarkus community version
     *
     * @since 2.17.0
     */
    private final String camelQuarkusCommunityVersion;

    /**
     * The basedir
     *
     * @since 2.17.0
     */
    private final Path basedir;

    /**
     * Encoding to read and write files in the current source tree
     *
     * @since 2.17.0
     */
    private final Charset charset;

    /**
     * Where to write a list of runtime dependencies of all Camel Quarkus productized extensions.
     * It is a text file one artifactId per line.
     *
     * @since 2.17.0
     */
    private final Path productizedDependenciesFile;

    /**
     * Where to write a list of runtime dependencies of all Camel Quarkus productized extensions.
     * It is a text file one artifactId per line.
     *
     * @since 2.17.0
     */
    private final Path allDependenciesFile;

    /**
     * Where to write a list of runtime dependencies of all Camel Quarkus productized extensions.
     * It is a text file one artifactId per line.
     *
     * @since 2.17.0
     */
    private final Path nonProductizedDependenciesFile;

    private final Product product;
    private final Product productCxf;

    /**
     * How to format simple XML elements ({@code <elem/>}) - with or without space before the slash.
     *
     * @since 2.23.0
     */
    private final SimpleElementWhitespace simpleElementWhitespace;

    private final List<RemoteRepository> repositories;

    private final RepositorySystem repoSystem;

    private final RepositorySystemSession repoSession;

    private final Log log;

    private final Runnable bomInstaller;

    private final ProjectBuilder mavenProjectBuilder;

    private final MavenSession session;

    public TransitiveDependenciesMojo(
            String version,
            String camelQuarkusCommunityVersion,
            Path basedir,
            Charset charset,
            Path productizedDependenciesFile,
            Path allDependenciesFile,
            Path nonProductizedDependenciesFile,
            Product product,
            Product productCxf,
            SimpleElementWhitespace simpleElementWhitespace,
            List<RemoteRepository> repositories,
            RepositorySystem repoSystem,
            RepositorySystemSession repoSession,
            ProjectBuilder mavenProjectBuilder,
            Log log,
            Runnable bomInstaller,
            MavenSession session) {
        this.version = version;
        this.camelQuarkusCommunityVersion = camelQuarkusCommunityVersion;
        this.basedir = basedir;
        this.charset = charset;
        this.productizedDependenciesFile = basedir.resolve(productizedDependenciesFile);
        this.allDependenciesFile = basedir.resolve(allDependenciesFile);
        this.nonProductizedDependenciesFile = basedir.resolve(nonProductizedDependenciesFile);
        this.product = product;
        this.productCxf = productCxf;
        this.simpleElementWhitespace = simpleElementWhitespace;
        this.repositories = repositories;
        this.repoSystem = repoSystem;
        this.repoSession = repoSession;
        this.mavenProjectBuilder = mavenProjectBuilder;
        this.log = log;
        this.bomInstaller = bomInstaller;
        this.session = session;
    }

    public void execute() {

        final BomModel bomModel = BomModel.of(
                basedir.resolve("poms/bom/pom.xml"),
                charset,
                Product.getInitialProductizedModules(product, productCxf),
                mavenProjectBuilder,
                session);
        /*
         * Set Camel dependency versions and install the BOM so that we get correct transitives via
         * DependencyCollector
         */
        final CamelDependencyCollector camelCollector = new CamelDependencyCollector();
        collect(bomModel, camelCollector, Collections.emptyList());
        updateCamelQuarkusBom(camelCollector.camelProdDeps);
        log.info("Installing camel-quarkus-bom again, now with proper Camel constraints");
        bomInstaller.run();

        final DependencyCollector collector = new DependencyCollector(
                product.getTransitiveDependencyReplacements(),
                product.getIgnoredTransitiveDependencies());
        collect(bomModel, collector, readConstraints());

        final Set<Ga> allTransitiveGas = toGas(collector.allTransitives);
        final Set<Ga> prodTransitiveGas = toGas(collector.prodTransitives);

        bomModel.getConstraintGas().stream()
                .forEach(depGa -> {
                    product.getAdditionalExtensionDependencies().entrySet().stream()
                            .filter(en -> en.getValue().contains(depGa.getGroupId(), depGa.getArtifactId()))
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
            log.warn("Found dependencies of productized artifacts with multiple versions:");
            multiversionedProdArtifacts.entrySet().forEach(en -> {
                log.warn("- " + en.getKey() + ": " + en.getValue());
            });
        }

        /* Ensure that all camel deps are managed */
        final Set<Ga> nonManagedCamelArtifacts = allTransitiveGas.stream()
                .filter(ga -> "org.apache.camel".equals(ga.getGroupId()))
                .filter(ga -> !bomModel.isManaged(ga))
                .collect(Collectors.toCollection(TreeSet::new));
        final StringBuilder sb = new StringBuilder(
                "Detected missing dependency management entries for Camel artifacts; consider adding the following to camel-quarkus-bom:");
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
         * do not want.
         * We extrapolate by version properties from the raw unflattened BOM.
         * If any of the Gas having that version property is productized, then the whole group is considered productized.
         */
        bomModel.getRawConstraints().stream()
                .filter(dep -> !"import".equals(dep.getScope()))
                .forEach(dep -> {
                    final Ga depGa = new Ga(dep.getGroupId(), dep.getArtifactId());
                    if (!allTransitiveGas.contains(depGa)) {
                        final Set<Ga> gaSet = bomModel.getGasWithVersionProperty(dep.getVersion());
                        if (prodTransitiveGas.stream().anyMatch(gaSet::contains) && !"test".equals(dep.getScope())) {
                            prodTransitiveGas.add(depGa);
                            log.debug("   - BOM entry mappable to an otherwise productized group: " + depGa);
                        } else if (allTransitiveGas.stream().anyMatch(gaSet::contains)) {
                            /* Still mappable */
                            log.debug("   - BOM entry mappable to an otherwise non-productized group: " + depGa);
                        } else {
                            log.warn(" - BOM entry not mappable to any group: " + depGa
                                    + " - is it perhaps supperfluous and should be removed from the BOM? Or needs to get assigne to an extension via <additionalExtensionDependencies>?");
                        }
                        allTransitiveGas.add(depGa);
                    }
                });

        write(allTransitiveGas, allDependenciesFile);
        write(prodTransitiveGas, productizedDependenciesFile);
        final Set<Ga> nonProdTransitives = allTransitiveGas.stream()
                .filter(dep -> !prodTransitiveGas.contains(dep))
                .collect(Collectors.toCollection(TreeSet::new));
        write(nonProdTransitives, nonProductizedDependenciesFile);

        log.info("Installing the final version of camel-quarkus-bom again, now with fine grained prod & non-prod versions");
        bomInstaller.run();

    }

    private List<Dependency> readConstraints() {
        final Path path = CqCommonUtils.resolveArtifact(
                repoSession.getLocalRepository().getBasedir().toPath(),
                "org.apache.camel.quarkus", "camel-quarkus-bom", version, "pom",
                repositories, repoSystem, repoSession);
        final Model bomModel = CqCommonUtils.readPom(path, StandardCharsets.UTF_8);

        return bomModel.getDependencyManagement().getDependencies().stream()
                .map(dep -> {
                    return toAetherDependency(dep);
                })
                .collect(Collectors.toList());
    }

    static Dependency toAetherDependency(org.apache.maven.model.Dependency dep) {
        return new Dependency(
                new DefaultArtifact(
                        dep.getGroupId(),
                        dep.getArtifactId(),
                        dep.getType(),
                        dep.getVersion()),
                null,
                false,
                dep.getExclusions() == null
                        ? Collections.emptyList()
                        : dep.getExclusions().stream()
                                .map(e -> new org.eclipse.aether.graph.Exclusion(e.getGroupId(),
                                        e.getArtifactId(),
                                        null, null))
                                .collect(Collectors.toList()));
    }

    void collect(BomModel bomModel, ProdDependencyCollector collector, List<Dependency> constraints) {
        final Map<Ga, EntryPointInfo> cqArtifactIds = bomModel.getResolutionEntryPoints();
        cqArtifactIds.entrySet().stream()
                .forEach(entry -> {

                    final Boolean isProd = entry.getValue().isProd();
                    final Ga ga = entry.getKey();
                    final DefaultArtifact artifact = new DefaultArtifact(
                            ga.getGroupId(),
                            ga.getArtifactId(),
                            null,
                            "pom",
                            entry.getValue().getVersion());
                    final CollectRequest request = new CollectRequest()
                            .setRepositories(repositories)
                            .setRoot(new org.eclipse.aether.graph.Dependency(artifact, null))
                            .setManagedDependencies(constraints);
                    try {
                        final DependencyNode rootNode = repoSystem
                                .collectDependencies(repoSession, request)
                                .getRoot();
                        collector.isProd = isProd;
                        rootNode.accept(collector);
                    } catch (DependencyCollectionException e) {
                        throw new RuntimeException("Could not resolve dependencies", e);
                    }
                });

    }

    void updateCamelQuarkusBom(Set<Ga> prodCamelGas) {

        final Path bomPath = basedir.resolve("poms/bom/pom.xml");
        log.info("Updating Camel versions in " + bomPath);
        new PomTransformer(bomPath, charset, simpleElementWhitespace)
                .transform((Document document, TransformationContext context) -> {

                    context.getContainerElement("project", "dependencyManagement", "dependencies").get()
                            .childElementsStream()
                            .map(ContainerElement::asGavtcs)
                            .filter(gavtcs -> gavtcs.getGroupId().equals("org.apache.camel"))
                            .forEach(gavtcs -> {
                                final Ga ga = new Ga(gavtcs.getGroupId(), gavtcs.getArtifactId());
                                final String expectedVersion = prodCamelGas.contains(ga)
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

    private final Map<String, Boolean> jarToJavax = new HashMap<>();
    private final Set<String> jakartaReport = new TreeSet<>();

    private void analyzeJakarta(Artifact artifact, Deque<Gav> stack) {
        final String groupId = artifact.getGroupId();
        if (!groupId.startsWith("org.apache.camel") && !groupId.startsWith("io.quarkus")) {
            if (stack.stream().anyMatch(gav -> "org.apache.camel.quarkus".equals(gav.getGroupId()))
                    && stack.stream().noneMatch(gav -> "io.quarkus".equals(gav.getGroupId()))
                    && stack.stream().noneMatch(gav -> "org.apache.camel".equals(gav.getGroupId()))) {
                /* We are interested only in transitives coming via Camel */
                File file = artifact.getFile();
                if (file == null) {
                    final ArtifactRequest req = new ArtifactRequest().setRepositories(this.repositories).setArtifact(artifact);
                    try {
                        final ArtifactResult resolutionResult = this.repoSystem.resolveArtifact(this.repoSession, req);
                        file = resolutionResult.getArtifact().getFile();
                    } catch (ArtifactResolutionException e) {
                        throw new RuntimeException("Could not resolve " + artifact, e);
                    }
                }
                if (file != null && file.getName().endsWith(".jar") && containsEnryStartingWith(file, "javax/")) {
                    /* Find the last CQ item */
                    final List<Gav> path = new ArrayList<>();
                    for (Iterator<Gav> i = stack.descendingIterator(); i.hasNext();) {
                        final Gav gav = i.next();
                        if (gav.getGroupId().equals("org.apache.camel.quarkus")) {
                            path.clear();
                            /*
                             * keep just the last CQ element of the path
                             * We'll thus reduce some uninteresting duplications in the report
                             */
                        }
                        path.add(gav);
                    }
                    jakartaReport.add(path.stream().map(Gav::toString).collect(Collectors.joining("\n    -> ")));
                }
            }
        }
    }

    private boolean containsEnryStartingWith(File file, String prefix) {
        final String absolutePath = file.getAbsolutePath();
        final Boolean knownToHaveJavax = jarToJavax.get(absolutePath);
        if (knownToHaveJavax != null) {
            return knownToHaveJavax.booleanValue();
        }

        try (ZipInputStream zip = new ZipInputStream(new FileInputStream(file), StandardCharsets.UTF_8)) {
            ZipEntry entry = null;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.getName().startsWith(prefix)) {
                    jarToJavax.put(absolutePath, true);
                    return true;
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Could not read " + file, e);
        }
        jarToJavax.put(absolutePath, false);
        return false;
    }

    static Properties toProperties(Map<String, String> map) {
        Properties props = new Properties();
        props.putAll(map);
        return props;
    }

    static abstract class ProdDependencyCollector implements DependencyVisitor {
        protected boolean isProd;
    }

    static class CamelDependencyCollector extends ProdDependencyCollector {

        private final Set<Ga> camelProdDeps = new TreeSet<>();

        @Override
        public boolean visitLeave(DependencyNode node) {
            return true;
        }

        @Override
        public boolean visitEnter(DependencyNode node) {
            final Artifact a = node.getArtifact();
            if (isProd && a.getGroupId().equals("org.apache.camel")) {
                camelProdDeps.add(new Ga(a.getGroupId(), a.getArtifactId()));
            }
            return true;
        }

    }

    static class DependencyCollector extends ProdDependencyCollector {
        private final Set<Gav> prodTransitives = new TreeSet<>();
        private final Set<Gav> allTransitives = new TreeSet<>();
        private final Deque<Gav> stack = new ArrayDeque<>();
        private final Map<Ga, Ga> transitiveDependencyReplacements;
        private final GavSet ignoredTransitiveDependencies;

        public DependencyCollector(Map<Ga, Ga> transitiveDependencyReplacements,
                GavSet ignoredTransitiveDependencies) {
            this.transitiveDependencyReplacements = transitiveDependencyReplacements;
            this.ignoredTransitiveDependencies = ignoredTransitiveDependencies;
        }

        @Override
        public boolean visitLeave(DependencyNode node) {
            stack.pop();
            return true;
        }

        @Override
        public boolean visitEnter(DependencyNode node) {
            final Gav gav = dependencyNodeToGav(node);
            stack.push(gav);

            if (ignoredTransitiveDependencies.contains(gav)) {
                /* ignore also the transitives */
                return false;
            }

            allTransitives.add(gav);
            if (isProd) {
                prodTransitives.add(gav);
            }
            return true;
        }

        private Gav dependencyNodeToGav(DependencyNode node) {
            final Artifact a = node.getArtifact();
            final Ga original = new Ga(a.getGroupId(), a.getArtifactId());
            final Ga replacement = transitiveDependencyReplacements.get(original);
            if (replacement == null) {
                return new Gav(original.getGroupId(), original.getArtifactId(), a.getVersion());
            } else {
                return new Gav(replacement.getGroupId(), replacement.getArtifactId(), a.getVersion());
            }
        }
    }

    static class EntryPointInfo {
        private static final Pattern STRIP_SUFFIX_PATTERN = Pattern.compile("[\\\\-\\\\.]redhat-\\\\d+$");
        private final String version;
        private final boolean prod;

        public EntryPointInfo(String version, boolean prod) {
            this.version = STRIP_SUFFIX_PATTERN.matcher(version).replaceAll("");
            this.prod = prod;
        }

        public String getVersion() {
            return version;
        }

        public boolean isProd() {
            return prod;
        }
    }

    static class BomModel {
        private final Map<Ga, EntryPointInfo> resolutionEntryPoints;
        private final Map<Ga, String> constraintGas;
        private final Map<String, Set<Ga>> versionProperty2Gas;
        private final List<org.apache.maven.model.Dependency> rawConstraints;

        static BomModel of(
                Path pomFile,
                Charset charset,
                Set<Ga> initialProductizedModules,
                ProjectBuilder mavenProjectBuilder,
                MavenSession session) {

            final Model rawModel = CqCommonUtils.readPom(pomFile, charset);
            /* A map from version property name to set of Gas being managed with that version */
            final Map<String, Set<Ga>> versionProperty2Gas = new TreeMap<>();
            rawModel.getDependencyManagement().getDependencies().stream()
                    .forEach(dep -> {
                        if (!"import".equals(dep.getScope())) {
                            final Ga ga = new Ga(dep.getGroupId(), dep.getArtifactId());
                            versionProperty2Gas.computeIfAbsent(dep.getVersion(), k -> new TreeSet<>()).add(ga);
                        }
                    });

            final Model model = CqCommonUtils.resolveEffectiveModel(pomFile, mavenProjectBuilder, session);

            final Map<Ga, EntryPointInfo> resolutionEntryPoints = new TreeMap<>();

            final Map<Ga, List<Dependency>> additionalDependencies = new LinkedHashMap<>();

            final Map<Ga, String> gaConstraints = new LinkedHashMap<>();

            model.getDependencyManagement().getDependencies().stream()
                    .filter(dep -> !"import".equals(dep.getScope()))
                    .forEach(dep -> {
                        final Ga ga = new Ga(dep.getGroupId(), dep.getArtifactId());
                        gaConstraints.put(ga, dep.getVersion());
                    });

            final Set<String> initialProductizedGroupIds = new LinkedHashSet<>();
            /* initialProductizedModules are trivially productized */
            initialProductizedModules.stream()
                    .forEach(ga -> {
                        initialProductizedGroupIds.add(ga.getGroupId());
                        String v = gaConstraints.get(ga);
                        if (v == null && ga.getGroupId().equals("org.apache.camel.quarkus")) {
                            v = model.getVersion();
                        }
                        if (v == null) {
                            throw new IllegalStateException("No managed version found for " + ga + " in " + pomFile);
                        }
                        final EntryPointInfo info = new EntryPointInfo(v, true);
                        resolutionEntryPoints.put(ga, info);

                        /* Add -deployment artifact, if it exists (i.e. is managed) */
                        final Ga deploymentGa = new Ga(ga.getGroupId(), ga.getArtifactId() + "-deployment");
                        if (gaConstraints.containsKey(deploymentGa)) {
                            resolutionEntryPoints.put(deploymentGa, info);
                        }
                    });

            /* The rest of own artifacts is not productized */
            gaConstraints.entrySet().stream()
                    .filter(ga -> initialProductizedGroupIds.contains(ga.getKey().getGroupId()))
                    .filter(ga -> !resolutionEntryPoints.containsKey(ga.getKey()))
                    .forEach(ga -> resolutionEntryPoints.put(ga.getKey(), new EntryPointInfo(ga.getValue(), false)));

            /*
             * Remove the runtime artifacts from the set if there is corresponding -deployment artifact,
             * because their -deployment counterparts will pull the runtime deps anyway
             */
            for (Iterator<Ga> it = resolutionEntryPoints.keySet().iterator(); it.hasNext();) {
                final Ga ga = it.next();
                if (!ga.getArtifactId().endsWith("-deployment")) {
                    final Ga deploymentGa = new Ga(ga.getGroupId(), ga.getArtifactId() + "-deployment");
                    if (resolutionEntryPoints.containsKey(deploymentGa)) {
                        it.remove();
                    }
                }
            }

            return new BomModel(
                    Collections.unmodifiableMap(resolutionEntryPoints),
                    Collections.unmodifiableMap(gaConstraints),
                    Collections.unmodifiableMap(versionProperty2Gas),
                    Collections.unmodifiableList(rawModel.getDependencyManagement().getDependencies()));

        }

        public List<org.apache.maven.model.Dependency> getRawConstraints() {
            return rawConstraints;
        }

        private BomModel(
                Map<Ga, EntryPointInfo> resolutionEntryPoints,
                Map<Ga, String> gaConstraints,
                Map<String, Set<Ga>> versionProperty2Gas,
                List<org.apache.maven.model.Dependency> rawConstraints) {
            this.resolutionEntryPoints = resolutionEntryPoints;
            this.constraintGas = gaConstraints;
            this.versionProperty2Gas = versionProperty2Gas;
            this.rawConstraints = rawConstraints;
        }

        public boolean isManaged(Ga ga) {
            return constraintGas.keySet().contains(ga);
        }

        public Set<Ga> getGasWithVersionProperty(String versionProperty) {
            final Set<Ga> result = versionProperty2Gas.get(versionProperty);
            if (result == null) {
                throw new IllegalStateException("Could not get Ga set for version property " + versionProperty);
            }
            return result;
        }

        public Map<Ga, EntryPointInfo> getResolutionEntryPoints() {
            return resolutionEntryPoints;
        }

        public Set<Ga> getConstraintGas() {
            return constraintGas.keySet();
        }

    }
}
