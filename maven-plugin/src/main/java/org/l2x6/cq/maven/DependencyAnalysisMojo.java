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
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.ProjectBuilder;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.collection.DependencyCollectionException;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.graph.DependencyVisitor;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.util.graph.transformer.ConflictResolver;
import org.l2x6.cq.common.CqCommonUtils;
import org.l2x6.pom.tuner.MavenSourceTree;
import org.l2x6.pom.tuner.model.Ga;
import org.l2x6.pom.tuner.model.Gav;
import org.l2x6.pom.tuner.model.GavSet;

/**
 * Writes reports about dependencies reachable over more than one primary dependency project.
 * Primary dependencies are Camel, Quarkus, etc.
 *
 * @since 4.2.0
 */
@Mojo(name = "da", threadSafe = true, requiresProject = true)
public class DependencyAnalysisMojo extends AbstractMojo {

    /**
     * The base directory of the current project
     *
     * @since 4.2.0
     */
    @Parameter(property = "cq.basedir", defaultValue = "${project.basedir}")
    File basedir;

    /**
     * Encoding to read and write files in the current source tree
     *
     * @since 4.2.0
     */
    @Parameter(defaultValue = "${project.build.sourceEncoding}", required = true, property = "project.build.sourceEncoding")
    String encoding;
    Charset charset;

    /**
     * The root directory of the current source tree.
     *
     * @since 4.2.0
     */
    @Parameter(defaultValue = "${maven.multiModuleProjectDirectory}", readonly = true)
    File multiModuleProjectDirectory;
    Path rootModuleDirectory;

    /**
     * Skip the execution of this mojo.
     *
     * @since 4.2.0
     */
    @Parameter(property = "cq.conflict-paths.skip", defaultValue = "false")
    boolean skip;

    /**
     * A list of GAV patterns to select a set of entries from the {@link #bomFile}. These initial artifacts
     * will be resolved, and their transitive dependencies will be filtered into the resulting reports.
     *
     * @since 4.2.0
     */
    @Parameter(property = "cq.resolutionEntryPointIncludes")
    List<String> resolutionEntryPointIncludes;

    /**
     * A list of GAV patterns whose matching entries will be removed from the initial GAV set selected by
     * {@link #resolutionEntryPointIncludes}.
     *
     * @since 4.2.0
     */
    @Parameter(property = "cq.resolutionEntryPointExcludes")
    List<String> resolutionEntryPointExcludes;

    /**
     * A list of {@code groupId:artifactid:version} tripples whose managed dependencies should be honored when resolving
     * transitives of the current BOM. Example:
     *
     * <pre>
     * {@code
     *    <additionalBoms>
     *        <additionalBom>io.quarkus:quarkus-bom:${quarkus.version}</additionalBom>
     *    </additionalBoms>
     * }
     * </pre>
     *
     * @since 4.2.0
     */
    @Parameter
    List<String> additionalBoms;

    @Parameter(property = "camel.version", defaultValue = "${camel.version}")
    String camelVersion;

    /**
     * A path to the pom.xml whose constraints will be used as a universe for {@link #resolutionEntryPointIncludes} and
     * {@link #resolutionEntryPointExcludes}
     *
     * @since 4.2.0
     */
    @Parameter(property = "cq.bomFile")
    String bomFile;

    /**
     * Where to store the short report
     *
     * @since 4.2.0
     */
    @Parameter(defaultValue = "${project.build.directory}/conflict-paths-report-short.yaml", property = "cq.shortReportFile")
    String shortReportFile;

    /**
     * Where to store the verbose report
     *
     * @since 4.2.0
     */
    @Parameter(defaultValue = "${project.build.directory}/conflict-paths-report-verbose.yaml", property = "cq.verboseReportFile")
    String verboseReportFile;

    /**
     * Where to store the versions report
     *
     * @since 4.2.1
     */
    @Parameter(defaultValue = "${project.build.directory}/versions-report.yaml", property = "cq.versionsReportFile")
    String versionsReportFile;

    /**
     * Where to store the versions report
     *
     * @since 4.2.1
     */
    @Parameter(defaultValue = "${project.build.directory}/dependency-paths-report.txt", property = "cq.dependencyPathsReportFile")
    String dependencyPathsReportFile;

    /**
     * Primary dependency projects such as Camel and Quarkus
     *
     * @since 4.2.0
     */
    @Parameter
    List<IdGavSet> primaryDependencyProjects;

    /**
     * Group transitive dependencies into the projects they come from to get a meaningful report
     *
     * @since 4.2.0
     */
    @Parameter
    List<IdGavSet> transitiveProjects;

    @Component
    RepositorySystem repoSystem;

    @Component
    private ProjectBuilder mavenProjectBuilder;

    @Parameter(defaultValue = "${repositorySystemSession}", readonly = true, required = true)
    RepositorySystemSession repoSession;

    @Parameter(defaultValue = "${project.remoteProjectRepositories}", readonly = true, required = true)
    List<RemoteRepository> repositories;

    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    MavenSession session;

    @Parameter(defaultValue = "${settings.localRepository}", readonly = true)
    String localRepository;
    Path localRepositoryPath;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("Skipping as requested by the user");
            return;
        }
        charset = Charset.forName(encoding);
        localRepositoryPath = Paths.get(localRepository);
        rootModuleDirectory = multiModuleProjectDirectory.toPath().toAbsolutePath().normalize();
        final Path bomPath = basedir.toPath().resolve(bomFile);
        final Model bom = CqCommonUtils.readPom(bomPath, charset);

        final GavSet entryPoints = GavSet.builder()
                .includes(resolutionEntryPointIncludes == null ? Collections.emptyList() : resolutionEntryPointIncludes)
                .excludes(resolutionEntryPointExcludes == null ? Collections.emptyList() : resolutionEntryPointExcludes)
                .build();

        final Map<String, GavSet> primaryDependencyProjectSets = new TreeMap<String, GavSet>();
        primaryDependencyProjects.stream()
                .forEach(p -> primaryDependencyProjectSets.put(p.getId(), p.toGavSet()));

        final DefaultRepositorySystemSession useRepoSession = repoSession();
        final DefaultArtifact emptyInstalledArtifact = emptyInstalledArtifact();

        final List<Dependency> originalConstrains = Collections
                .unmodifiableList(new ArrayList<>(bom.getDependencyManagement().getDependencies()));

        final List<Dependency> constraintsFilteredByOriginPlusAdditionalBoms = new ArrayList<>();

        final List<Gav> additionalBomGavs = additionalBoms == null ? Collections.emptyList()
                : additionalBoms.stream().map(Gav::of).collect(Collectors.toList());

        final Map<Gav, Map<Ga, String>> boms = new TreeMap<>();
        /* Add Quarkus BOM entries first */
        addAdditionalBoms(
                additionalBomGavs.stream()
                        .filter(gav -> "io.quarkus".equals(gav.getGroupId()))
                        .peek(gav -> boms.put(gav, new TreeMap<>()))
                        .collect(Collectors.toList()),
                (Gav gav, Dependency dep) -> {
                    constraintsFilteredByOriginPlusAdditionalBoms.add(dep);
                    boms.get(gav).put(toGa(dep), dep.getVersion());
                });
        constraintsFilteredByOriginPlusAdditionalBoms.addAll(originalConstrains);
        final Map<Ga, String> ownBomConstraints = new TreeMap<>();
        originalConstrains.stream()
                .forEach(dep -> ownBomConstraints.put(toGa(dep), dep.getVersion()));
        boms.put(
                new Gav(bom.getGroupId(), bom.getArtifactId(), bom.getVersion()),
                ownBomConstraints);
        addAdditionalBoms(
                additionalBomGavs.stream()
                        .filter(gav -> !"io.quarkus".equals(gav.getGroupId()))
                        .peek(gav -> boms.put(gav, new TreeMap<>()))
                        .collect(Collectors.toList()),
                (Gav gav, Dependency dep) -> {
                    constraintsFilteredByOriginPlusAdditionalBoms.add(dep);
                    boms.get(gav).put(toGa(dep), dep.getVersion());
                });

        final MavenSourceTree t = MavenSourceTree.of(rootModuleDirectory.resolve("pom.xml"), charset);
        final Set<Ga> ownGas = t.getModulesByGa().keySet();

        final List<org.eclipse.aether.graph.Dependency> aetherConstraints = constraintsFilteredByOriginPlusAdditionalBoms
                .stream()
                .filter(dep -> !ownGas.contains(toGa(dep)))
                .map(DependencyAnalysisMojo::toAetherDependency)
                .collect(Collectors.toList());

        final Map<Ga, Set<String>> camelVersions = camelVersions(ownBomConstraints, emptyInstalledArtifact, useRepoSession,
                camelVersion);
        final DependencyCollector collector = new DependencyCollector(
                primaryDependencyProjectSets,
                new ProjectMapper(transitiveProjects),
                ownGas,
                new Ga(emptyInstalledArtifact.getGroupId(), emptyInstalledArtifact.getArtifactId()),
                boms,
                camelVersions);
        originalConstrains.stream()
                .filter(dep -> entryPoints.contains(dep.getGroupId(), dep.getArtifactId(), dep.getVersion()))
                .forEach(entry -> {

                    getLog().info("Resolving " + entry.getGroupId() + ":" + entry.getArtifactId() + ":" +
                            entry.getType() + ":" +
                            entry.getVersion());

                    final CollectRequest request = new CollectRequest()
                            .setRoot(new org.eclipse.aether.graph.Dependency(emptyInstalledArtifact, null))
                            .setRepositories(repositories)
                            .setManagedDependencies(aetherConstraints)
                            .setDependencies(
                                    Collections.singletonList(
                                            new org.eclipse.aether.graph.Dependency(
                                                    new DefaultArtifact(
                                                            entry.getGroupId(),
                                                            entry.getArtifactId(),
                                                            entry.getType(),
                                                            entry.getVersion()),
                                                    null)));

                    try {
                        final DependencyNode rootNode = repoSystem.collectDependencies(useRepoSession, request).getRoot();
                        rootNode.accept(collector);
                    } catch (DependencyCollectionException | IllegalArgumentException e) {
                        throw new RuntimeException(
                                "Could not resolve dependencies of " + entry.getGroupId() + ":" + entry.getArtifactId() + ":" +
                                        entry.getType() + ":" +
                                        entry.getVersion(),
                                e);
                    }

                });

        Map.<String, Consumer<Consumer<String>>> of(
                shortReportFile, collector::renderShort,
                verboseReportFile, collector::renderVerbose,
                versionsReportFile, collector::renderVersions,
                dependencyPathsReportFile, collector::renderAllDependencyPaths)
                .entrySet().stream()
                .forEach(en -> {
                    Path reportPath = basedir.toPath().resolve(en.getKey());
                    getLog().info("Writing report " + en.getKey());
                    try {
                        Files.createDirectories(reportPath.getParent());
                    } catch (IOException e1) {
                        throw new RuntimeException("Could not create " + reportPath.getParent());
                    }
                    try (Writer w = Files.newBufferedWriter(reportPath)) {
                        en.getValue().accept(string -> {
                            try {
                                w.append(string).append('\n');
                            } catch (IOException e1) {
                                throw new RuntimeException("Could not append to " + reportPath);
                            }
                        });
                    } catch (IOException e1) {
                        throw new RuntimeException("Could not write to " + reportPath);
                    }
                });
    }

    private Map<Ga, Set<String>> camelVersions(Map<Ga, String> ownBomConstraints, Artifact emptyInstalledArtifact,
            RepositorySystemSession useRepoSession, String camelVersion) {

        final CamelCollector collector = new CamelCollector();

        final Path camelParentPath = CqCommonUtils.resolveArtifact(
                localRepositoryPath,
                "org.apache.camel",
                "camel-parent",
                camelVersion,
                "pom",
                repositories, repoSystem, useRepoSession);
        final Model camelParentModel = CqCommonUtils.resolveEffectiveModel(camelParentPath, mavenProjectBuilder, session);
        getLog().info("Camel constraints:");
        final List<org.eclipse.aether.graph.Dependency> aetherConstraints = camelParentModel.getDependencyManagement()
                .getDependencies()
                .stream()
                .peek(dep -> getLog().info(" - " + dep.getGroupId() + ":" + dep.getArtifactId() + ":" + dep.getVersion()))
                .map(DependencyAnalysisMojo::toAetherDependency)
                .collect(Collectors.toList());

        ownBomConstraints.entrySet().stream()
                .filter(en -> en.getKey().getGroupId().equals("org.apache.camel"))
                .forEach(en -> {
                    final Ga entry = en.getKey();
                    final String version = en.getValue();
                    getLog().info("Resolving " + entry.getGroupId() + ":" + entry.getArtifactId() + ":jar:" +
                            version);

                    final CollectRequest request = new CollectRequest()
                            .setRoot(new org.eclipse.aether.graph.Dependency(emptyInstalledArtifact, null))
                            .setRepositories(repositories)
                            .setManagedDependencies(aetherConstraints)
                            .setDependencies(
                                    Collections.singletonList(
                                            new org.eclipse.aether.graph.Dependency(
                                                    new DefaultArtifact(
                                                            entry.getGroupId(),
                                                            entry.getArtifactId(),
                                                            "jar",
                                                            version),
                                                    null)));

                    try {
                        final DependencyNode rootNode = repoSystem.collectDependencies(useRepoSession, request).getRoot();
                        rootNode.accept(collector);
                    } catch (DependencyCollectionException | IllegalArgumentException e) {
                        throw new RuntimeException(
                                "Could not resolve dependencies of " + entry.getGroupId() + ":" + entry.getArtifactId()
                                        + ":jar:" +
                                        version,
                                e);
                    }
                });

        return collector.artifactVersions;
    }

    private DefaultArtifact emptyInstalledArtifact() {
        final String artifactId = "emptyInstalledArtifact";
        final String groupId = "org.l2x6.cq.maven.emptyInstalledArtifact";
        final String version = "1.0.0-SNAPSHOT";
        try {
            final Path tempPath = Files.createTempFile(artifactId, "pom.xml");
            String pomContent = "<project xmlns=\"http://maven.apache.org/POM/4.0.0\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd\">\n"
                    + "    <modelVersion>4.0.0</modelVersion>\n"
                    + "\n"
                    + "    <groupId>" + groupId + "</groupId>\n"
                    + "    <artifactId>" + artifactId + "</artifactId>\n"
                    + "    <version>" + version + "</version>\n"
                    + "    <packaging>pom</packaging>\n"
                    + "</project>";
            Files.write(tempPath, pomContent.getBytes(charset));
            CqCommonUtils.installArtifact(tempPath, localRepositoryPath, groupId, artifactId, version, "pom");
            return new DefaultArtifact(
                    groupId,
                    artifactId,
                    null,
                    "pom",
                    version);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public DefaultRepositorySystemSession repoSession() {
        /*
         * ConflictResolver.CONFIG_PROP_VERBOSE = true causes a much thorough and more expensive dependency
         * resolution so we use it only in format mode
         */
        DefaultRepositorySystemSession useRepoSession = new DefaultRepositorySystemSession(repoSession);
        final Map<String, Object> configProps = new HashMap<>(repoSession.getConfigProperties());
        configProps.put(ConflictResolver.CONFIG_PROP_VERBOSE, true);
        useRepoSession.setConfigProperties(configProps);
        return useRepoSession;
    }

    void addAdditionalBoms(List<Gav> additionalBoms, BiConsumer<Gav, Dependency> additionalBomEntryConsumer) {

        for (Gav gav : additionalBoms) {
            final Path path = CqCommonUtils.resolveArtifact(localRepositoryPath, gav.getGroupId(), gav.getArtifactId(),
                    gav.getVersion(), "pom",
                    repositories, repoSystem, repoSession);
            final Model pom = CqCommonUtils.readPom(path, charset);
            final List<Dependency> deps = pom.getDependencyManagement().getDependencies();
            final String msg = deps.stream()
                    .peek(dep -> additionalBomEntryConsumer.accept(gav, dep))
                    .filter(dep -> dep.getVersion().contains("${"))
                    .map(dep -> dep.getGroupId() + ":" + dep.getArtifactId() + ":" + dep.getVersion())
                    .collect(Collectors.joining("\n    - "));
            if (!msg.isEmpty()) {
                throw new IllegalStateException("Additional BOM " + gav + " contains unresolved versions:\n    - " + msg);
            }
        }
    }

    static Ga toGa(Dependency dep) {
        return new Ga(dep.getGroupId(), dep.getArtifactId());
    }

    static abstract class BaseCollector implements DependencyVisitor {
        protected final Deque<Ga> stack = new ArrayDeque<>();
        /** From Ga to a Set of versions */
        protected final Map<Ga, Set<String>> artifactVersions = new TreeMap<>();

        @Override
        public boolean visitLeave(DependencyNode node) {
            if (node.getData().get(ConflictResolver.NODE_DATA_WINNER) == null) {
                stack.pop();
            }
            return true;
        }

        @Override
        public boolean visitEnter(DependencyNode node) {
            final Artifact a = node.getArtifact();
            final Ga ga = new Ga(a.getGroupId(), a.getArtifactId());
            DependencyNode winner = (DependencyNode) node.getData().get(ConflictResolver.NODE_DATA_WINNER);
            if (winner != null) {
                /* Recurse the winner instead of the current looser */
                if (!stack.contains(ga)) {
                    winner.accept(this);
                }
                return false; // should have empty children anyway as stated in class level JavaDoc of ConflictResolver
            }

            doVisitEnter(ga, a.getVersion());
            stack.push(ga);

            return true;
        }

        protected abstract void doVisitEnter(Ga ga, String version);

    }

    static class CamelCollector extends BaseCollector {
        @Override
        protected void doVisitEnter(Ga ga, String version) {
            artifactVersions.compute(ga, (k, v) -> {
                (v == null ? (v = new TreeSet<String>()) : v).add(version);
                return v;
            });
        }
    }

    static class DependencyCollector extends BaseCollector {
        private final Map<String, GavSet> primaryDependencyProjectSets;

        /**
         * Artifacts reachable through the project with the given ID.
         * A map from primary dependency projectId to a map from Ga reachable through that project
         * to paths through it is reachanle.
         */
        private final Map<String, Map<Ga, Set<String>>> transitivesByPrimaryDependencyProject;
        private final ProjectMapper gavSetMapper;
        private final Set<Ga> ownGas;
        private final Set<String> allDependencyPaths = new TreeSet<>();
        private final Ga emptyArtifact;
        private final Map<Gav, Map<Ga, String>> boms;
        private final Map<Ga, Set<String>> camelVersions;

        public DependencyCollector(
                Map<String, GavSet> primaryDependencyProjectSets,
                ProjectMapper gavSetMapper,
                Set<Ga> ownGas,
                Ga emptyArtifact,
                Map<Gav, Map<Ga, String>> boms,
                Map<Ga, Set<String>> camelVersions) {
            this.primaryDependencyProjectSets = primaryDependencyProjectSets;
            this.gavSetMapper = gavSetMapper;
            this.ownGas = ownGas;
            this.emptyArtifact = emptyArtifact;
            Map<String, Map<Ga, Set<String>>> map = new TreeMap<>();
            primaryDependencyProjectSets.keySet().stream()
                    .forEach(k -> map.put(k, new TreeMap<>()));
            this.transitivesByPrimaryDependencyProject = Collections.unmodifiableMap(map);
            this.boms = boms;
            this.camelVersions = camelVersions;
        }

        public void renderShort(Consumer<String> log) {
            log.accept("#");
            log.accept("# Potential inter-project conflicts");
            log.accept("#");

            /* From projectId, to artifact groups */
            final Map<String, Set<String>> shortProjects = new TreeMap<>();
            transitivesByPrimaryDependencyProject.entrySet()
                    .forEach(en -> {
                        shortProjects.put(
                                en.getKey(),
                                en.getValue().keySet().stream()
                                        .map(gavSetMapper::toProjectId)
                                        .collect(Collectors.toCollection(TreeSet::new)));
                    });

            final List<String> projectIds = new ArrayList<>(shortProjects.keySet());
            for (int i = 0; i < projectIds.size(); i++) {
                final String id1 = projectIds.get(i);
                final Set<String> gas1 = shortProjects.get(id1);
                for (int j = i + 1; j < projectIds.size(); j++) {
                    final String id2 = projectIds.get(j);
                    final Set<String> gas2 = shortProjects.get(id2);
                    log.accept("- \"" + id1 + ".." + id2 + "\":");
                    gas1.stream()
                            .filter(ga -> gas2.contains(ga))
                            .forEach(ga -> {
                                final Predicate<Ga> projectPredicate = gavSetMapper.findProjectPredicate(ga);
                                log.accept("  - \"" + ga + "\"");
                                final Set<Ga> projectArtifacts = new TreeSet<>();
                                final Set<String> versions = artifactVersions.entrySet().stream()
                                        .filter(versionEntry -> projectPredicate.test(versionEntry.getKey()))
                                        .peek(en -> projectArtifacts.add(en.getKey()))
                                        .map(Entry::getValue)
                                        .flatMap(Set::stream)
                                        .collect(Collectors.toCollection(TreeSet::new));

                                final Set<Ga> camelGas = new TreeSet<>();
                                final Set<String> camelArtifactVersions = camelVersions.entrySet().stream()
                                        .filter(versionEntry -> projectPredicate.test(versionEntry.getKey()))
                                        .peek(en -> camelGas.add(en.getKey()))
                                        .map(Entry::getValue)
                                        .flatMap(Set::stream)
                                        .collect(Collectors.toCollection(TreeSet::new));

                                if (versions.size() == 1) {
                                    log.accept("    # ✅ same version throughout CQ dependency graph: " + versions);
                                } else {
                                    log.accept("    # ❌ various versions throughout CQ dependency graph: " + versions);
                                }

                                final AtomicBoolean managed = new AtomicBoolean(false);
                                boms.forEach((bomGav, bomConstraints) -> {
                                    final Set<String> managedVersions = bomConstraints.entrySet().stream()
                                            .filter(en -> projectArtifacts.contains(en.getKey()))
                                            .map(Entry::getValue)
                                            .collect(Collectors.toCollection(TreeSet::new));
                                    if (managedVersions.size() > 0) {
                                        managed.set(true);
                                        String fullyOrPartly = managedVersions.size() == projectArtifacts.size() ? "✅ fully"
                                                : "⚠️ partly";
                                        log.accept("    # " + fullyOrPartly + " managed (" + managedVersions.size()
                                                + "/" + projectArtifacts.size() + ") in " + bomGav + " at version(s) "
                                                + managedVersions);
                                    }
                                });
                                if (!managed.get()) {
                                    log.accept("    # ❌ not managed in any of the listed BOMs");
                                }
                                if (versions.equals(camelArtifactVersions)) {
                                    log.accept("    # ✅ Camel uses " + camelGas.size()
                                            + "/" + projectArtifacts.size() + " artifacts with the same versions: "
                                            + camelArtifactVersions);
                                } else {
                                    log.accept("    # ❌ Camel uses " + camelGas.size()
                                            + "/" + projectArtifacts.size() + " artifacts with different versions: "
                                            + camelArtifactVersions);
                                }
                            });
                }
            }
        }

        public void renderVerbose(Consumer<String> log) {
            log.accept("#");
            log.accept("# Potential inter-project conflicts");
            log.accept("#");
            final List<String> projectIds = new ArrayList<>(transitivesByPrimaryDependencyProject.keySet());
            for (int i = 0; i < projectIds.size(); i++) {
                final String id1 = projectIds.get(i);
                final Map<Ga, Set<String>> gas1 = transitivesByPrimaryDependencyProject.get(id1);
                for (int j = i + 1; j < projectIds.size(); j++) {
                    final String id2 = projectIds.get(j);
                    final Map<Ga, Set<String>> gas2 = transitivesByPrimaryDependencyProject.get(id2);
                    log.accept("- \"" + id1 + ".." + id2 + "\":");
                    gas1.keySet().stream()
                            .filter(ga -> gas2.keySet().contains(ga))
                            .forEach(ga -> {
                                log.accept("  - \"" + ga + "\"");
                                final AtomicBoolean managed = new AtomicBoolean(false);
                                boms.entrySet().stream()
                                        .filter(en -> en.getValue().containsKey(ga))
                                        .peek(en -> managed.set(true))
                                        .forEach(en -> log.accept("    # managed by " + en.getKey()));
                                if (!managed.get()) {
                                    log.accept("    # not managed by any listed BOM");
                                }
                                gas1.get(ga).stream()
                                        .map(path -> "    - \"" + path + "\"")
                                        .forEach(log);
                                gas2.get(ga).stream()
                                        .map(path -> "    - \"" + path + "\"")
                                        .forEach(log);
                            });
                }
            }
        }

        public void renderVersions(Consumer<String> log) {
            artifactVersions.forEach((ga, versions) -> {
                log.accept("- " + ga);
                versions.stream()
                        .map(v -> "  - \"" + v + "\"")
                        .forEach(log::accept);
            });
        }

        public void renderAllDependencyPaths(Consumer<String> log) {
            log.accept("# Legend: a <- b ... b depends on a");
            allDependencyPaths.forEach(log::accept);
        }

        static String toPath(Deque<Ga> stack, Set<Ga> ownGas, Ga emptyArtifact) {
            AtomicBoolean ownElementFound = new AtomicBoolean(false);
            return stack.stream()
                    .filter(ga -> {
                        if (emptyArtifact.equals(ga)) {
                            /* the dummy artifact is redundant */
                            return false;
                        } else if (ownElementFound.get()) {
                            /* the first element was rendered already */
                            return false;
                        } else if (ownGas.contains(ga)) {
                            ownElementFound.set(true);
                            /* render only the first own element */
                            return true;
                        }
                        return true;
                    })
                    .map(Ga::toString)
                    .collect(Collectors.joining(" <- "));
        }

        @Override
        protected void doVisitEnter(Ga ga, String version) {
            final AtomicBoolean reachableThroughAPrimaryProject = new AtomicBoolean(false);
            primaryDependencyProjectSets.entrySet().stream()
                    /* If any stack element belongs to a project */
                    .filter(en -> stack.stream().anyMatch(stackGa -> en.getValue().contains(stackGa)))
                    /* then add the current ga as being reachable through that project */
                    .map(Map.Entry::getKey)
                    .forEach(
                            projectId -> transitivesByPrimaryDependencyProject
                                    .get(projectId)
                                    .compute(ga, (k, v) -> {
                                        final String path = projectId + ": " + version + " "
                                                + toPath(stack, ownGas, emptyArtifact);
                                        (v == null ? (v = new TreeSet<>()) : v).add(path);
                                        reachableThroughAPrimaryProject.set(true);
                                        return v;
                                    }));

            if (reachableThroughAPrimaryProject.get()) {
                artifactVersions.compute(ga, (k, v) -> {
                    (v == null ? (v = new TreeSet<String>()) : v).add(version);
                    return v;
                });
            }

            allDependencyPaths.add(ga + ":" + version + " <- " + toPath(stack, ownGas, emptyArtifact));

        }

    }

    public static class ProjectMapper {

        private final Map<String, GavSet> customGavSets;

        private ProjectMapper(List<IdGavSet> transitiveProjects) {
            this.customGavSets = new TreeMap<String, GavSet>();
            if (transitiveProjects != null) {
                transitiveProjects.stream()
                        .forEach(p -> customGavSets.put(p.getId(), p.toGavSet()));
            }
        }

        /**
         * @param  ga the {@link Ga} to find a project for
         * @return    a projectId of the project the given {@code ga} belongs to
         */
        public String toProjectId(Ga ga) {
            return customGavSets.entrySet().stream()
                    .filter(en -> en.getValue().contains(ga))
                    .map(Map.Entry::getKey)
                    .findFirst()
                    .orElse(ga.getGroupId());
        }

        /**
         * @param  projectId the ID of a project whose {@link Predicate} is to be found
         * @return           a {@link Predicate} able to select artifacts of the given project
         */
        public Predicate<Ga> findProjectPredicate(String projectId) {
            GavSet result = customGavSets.get(projectId);
            if (result != null) {
                return result::contains;
            }
            return ga -> ga.getGroupId().equals(projectId);
        }
    }

    public static class IdGavSet {
        private String id;
        private String includeGas;
        private String excludeGas;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getIncludeGas() {
            return includeGas;
        }

        public void setIncludeGas(String includeGas) {
            this.includeGas = includeGas;
        }

        public String getExcludeGas() {
            return excludeGas;
        }

        public void setExcludeGas(String excludeGas) {
            this.excludeGas = excludeGas;
        }

        public GavSet toGavSet() {
            return GavSet.builder().includes(includeGas).excludes(excludeGas).build();
        }
    }

    private static org.eclipse.aether.graph.Dependency toAetherDependency(Dependency dep) {
        return new org.eclipse.aether.graph.Dependency(
                new DefaultArtifact(
                        dep.getGroupId(),
                        dep.getArtifactId(),
                        dep.getClassifier(),
                        dep.getType(),
                        dep.getVersion()),
                null,
                false,
                dep.getExclusions().stream()
                        .map(e -> new org.eclipse.aether.graph.Exclusion(e.getGroupId(), e.getArtifactId(), "*",
                                "*"))
                        .collect(Collectors.toList()));
    }

}
