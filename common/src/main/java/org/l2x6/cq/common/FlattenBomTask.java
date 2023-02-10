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
package org.l2x6.cq.common;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.xml.XMLConstants;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.DependencyManagement;
import org.apache.maven.model.Exclusion;
import org.apache.maven.model.InputLocation;
import org.apache.maven.model.InputLocation.StringFormatter;
import org.apache.maven.model.InputSource;
import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.apache.maven.model.io.xpp3.MavenXpp3Writer;
import org.apache.maven.model.io.xpp3.MavenXpp3WriterEx;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;
import org.assertj.core.util.diff.Delta;
import org.assertj.core.util.diff.DiffUtils;
import org.codehaus.plexus.util.StringUtils;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.collection.DependencyCollectionException;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.graph.DependencyVisitor;
import org.eclipse.aether.repository.RemoteRepository;
import org.jdom2.Document;
import org.jdom2.JDOMException;
import org.jdom2.input.SAXBuilder;
import org.jdom2.output.Format;
import org.jdom2.output.XMLOutputter;
import org.l2x6.pom.tuner.ExpressionEvaluator;
import org.l2x6.pom.tuner.MavenSourceTree;
import org.l2x6.pom.tuner.PomTransformer;
import org.l2x6.pom.tuner.PomTransformer.ContainerElement;
import org.l2x6.pom.tuner.PomTransformer.NodeGavtcs;
import org.l2x6.pom.tuner.PomTransformer.SimpleElementWhitespace;
import org.l2x6.pom.tuner.PomTransformer.TransformationContext;
import org.l2x6.pom.tuner.model.Ga;
import org.l2x6.pom.tuner.model.Gav;
import org.l2x6.pom.tuner.model.GavPattern;
import org.l2x6.pom.tuner.model.GavSet;
import org.l2x6.pom.tuner.model.Gavtcs;
import org.l2x6.pom.tuner.model.Module;
import org.l2x6.pom.tuner.model.Profile;
import org.w3c.dom.Node;

import static java.util.stream.Collectors.joining;

public class FlattenBomTask {
    public static class BomEntryTransformation {
        private GavPattern gavPattern;
        private Set<Ga> internalExclusions = new TreeSet<>();
        private Pattern versionPattern;
        private String versionReplace;

        public BomEntryTransformation() {
        }

        public BomEntryTransformation(String gavPattern, String versionReplacement, String exclusions, String addExclusions) {
            if (gavPattern != null) {
                setGavPattern(gavPattern);
            }
            if (versionReplacement != null) {
                setVersionReplacement(versionReplacement);
            }
            if (exclusions != null) {
                setExclusions(exclusions);
            }
            if (addExclusions != null) {
                setAddExclusions(addExclusions);
            }
        }

        public List<Exclusion> getAddExclusions() {
            return internalExclusions.stream()
                    .map(ga -> {
                        final Exclusion excl = new Exclusion();
                        excl.setGroupId(ga.getGroupId());
                        excl.setArtifactId(ga.getArtifactId());
                        return excl;
                    })
                    .collect(Collectors.toList());
        }

        public void setAddExclusions(String exclusions) {
            for (String rawExcl : exclusions.split("[,\\s]+")) {
                this.internalExclusions.add(GavPattern.of(rawExcl).asWildcardGa());
            }
        }

        /**
         * An alias for {@link #setAddExclusions(String)}
         *
         * @param      exclusions items to exclude
         * @deprecated            use {@link #setAddExclusions(String)}
         */
        public void setExclusions(String exclusions) {
            setAddExclusions(exclusions);
        }

        public GavPattern getGavPattern() {
            return gavPattern;
        }

        public void setGavPattern(String gavPattern) {
            this.gavPattern = GavPattern.of(gavPattern);
        }

        public String replaceVersion(String version) {
            return versionPattern == null ? version : versionPattern.matcher(version).replaceAll(versionReplace);
        }

        public void setVersionReplacement(String versionReplacement) {
            final int slashPos = versionReplacement.indexOf('/');
            if (slashPos < 1) {
                throw new IllegalStateException(
                        "versionReplacement is expected to contain exactly one slash (/); found " + versionReplacement);
            }
            this.versionPattern = Pattern.compile(versionReplacement.substring(0, slashPos));
            this.versionReplace = versionReplacement.substring(slashPos + 1);
        }

        @Override
        public String toString() {
            return "BomEntryTransformation [gavPattern=" + gavPattern + ", internalExclusions=" + internalExclusions
                    + ", versionReplace=" + versionReplace + "]";
        }
    }

    static class DependencyCollector implements DependencyVisitor {
        private final Set<Ga> allTransitives = new TreeSet<>();
        private final GavSet excludes;

        public DependencyCollector(GavSet excludes) {
            this.excludes = excludes;
        }

        @Override
        public boolean visitLeave(DependencyNode node) {
            return true;
        }

        @Override
        public boolean visitEnter(DependencyNode node) {
            final Artifact a = node.getArtifact();
            if (!excludes.contains(a.getGroupId(), a.getArtifactId())) {
                final Ga gav = new Ga(a.getGroupId(), a.getArtifactId());
                allTransitives.add(gav);
            }
            return true;
        }

    }

    private static class InputLocationStringFormatter
            extends InputLocation.StringFormatter {

        private final String versionSuffix;

        public InputLocationStringFormatter(String version) {
            this.versionSuffix = ":" + version;
        }

        private static final String GAV_PREFIX = FlattenBomTask.ORG_APACHE_CAMEL_QUARKUS_GROUP_ID + ":";

        public String toString(InputLocation location) {
            InputSource source = location.getSource();

            String s = source.getModelId(); // by default, display modelId

            if (StringUtils.isBlank(s) || s.contains("[unknown-version]")) {
                // unless it is blank or does not provide version information
                s = source.toString();
            }

            if (s.startsWith(GAV_PREFIX)) {
                s = s.replace(versionSuffix, ":${project.version}");
            }

            return "#} " + s + " ";
        }

    }

    private static class BomEntryData {
        final boolean isResolutionEntryPoint;
        final Set<Ga> transitiveDependencies;
        final Set<Ga> exclusions;

        public BomEntryData(boolean isResolutionEntryPoint, Set<Ga> transitiveDependencies, Set<Ga> exclusions) {
            this.isResolutionEntryPoint = isResolutionEntryPoint;
            this.transitiveDependencies = transitiveDependencies;
            this.exclusions = exclusions;
        }
    }

    private static class BomEntryTransformationData {
        final BomEntryTransformation bomEntryTransformation;
        final ContainerElement containerElement;

        public BomEntryTransformationData(BomEntryTransformation bomEntryTransformation, ContainerElement containerElement) {
            this.bomEntryTransformation = bomEntryTransformation;
            this.containerElement = containerElement;
        }

        public void addExclusions(Set<Ga> missingExclusions) {
            Set<Ga> existingExclusions = bomEntryTransformation.internalExclusions;
            if (!existingExclusions.containsAll(missingExclusions)) {
                existingExclusions.addAll(missingExclusions);
                containerElement.addOrSetChildTextElement("addExclusions",
                        existingExclusions.stream().map(Ga::toString).collect(Collectors.joining(",")));
                final Optional<ContainerElement> exclusions = containerElement.getChildContainerElement("exclusions");
                if (exclusions.isPresent()) {
                    exclusions.get().remove(true, true);
                }
            }
        }

        public static BomEntryTransformationData create(GavPattern pattern, Set<Ga> missingExclusions,
                ContainerElement parent) {
            final BomEntryTransformation transformation = new BomEntryTransformation();
            transformation.setGavPattern(pattern.toString());
            final String exclusions = missingExclusions.stream().map(Ga::toString).collect(Collectors.joining(","));
            transformation.setAddExclusions(exclusions);

            ContainerElement node = parent.addChildContainerElement("bomEntryTransformation");

            node.addChildTextElement("gavPattern", pattern.toString());
            node.addChildTextElement("addExclusions", exclusions);

            return new BomEntryTransformationData(transformation, node);
        }
    }

    private static class RequiredGas {

        private final Set<Ga> gas;
        private final Map<Ga, BomEntryData> transitivesByBomEntry;

        public RequiredGas(Set<Ga> gas, Map<Ga, BomEntryData> transitivesByBomEntry) {
            this.gas = gas;
            this.transitivesByBomEntry = transitivesByBomEntry;
        }

    }

    public static enum InstallFlavor {
        FULL, REDUCED, REDUCED_VERBOSE, ORIGINAL
    }

    private final List<String> resolutionEntryPointIncludes;
    private final List<String> resolutionEntryPointExcludes;
    private final List<String> resolutionSuspects;
    private final List<String> originExcludes;
    private final List<FlattenBomTask.BomEntryTransformation> bomEntryTransformations;
    private final GavSet requiredBomEntries;
    private final OnFailure onCheckFailure;
    private final Model effectivePomModel;
    private final String version;
    private final Path basePath;
    private final Path rootModuleDirectory;
    private final Path fullPomPath;
    private final Path reducedVerbosePamPath;
    private final Path reducedPomPath;
    private final Charset charset;
    private final Log log;
    private final List<RemoteRepository> repositories;
    private final RepositorySystem repoSystem;
    private final RepositorySystemSession repoSession;
    private final Predicate<Profile> profiles;
    private final boolean format;
    private final SimpleElementWhitespace simpleElementWhitespace;
    private final MavenProject project;
    private final FlattenBomTask.InstallFlavor installFlavor;
    private final boolean quickly;
    private final GavSet bannedDependencies;
    private final List<Dependency> ownManagedDependencies;
    private static final Pattern LOCATION_COMMENT_PATTERN = Pattern.compile("\\s*\\Q<!--#}\\E");
    public static final String DEFAULT_FLATTENED_REDUCED_VERBOSE_POM_FILE = "src/main/generated/flattened-reduced-verbose-pom.xml";
    public static final String DEFAULT_FLATTENED_REDUCED_POM_FILE = "src/main/generated/flattened-reduced-pom.xml";
    public static final String DEFAULT_FLATTENED_FULL_POM_FILE = "src/main/generated/flattened-full-pom.xml";
    public static final String ORG_APACHE_CAMEL_QUARKUS_GROUP_ID = "org.apache.camel.quarkus";
    private static final GavSet toResolveDependencies = GavSet.builder()
            .excludes(FlattenBomTask.ORG_APACHE_CAMEL_QUARKUS_GROUP_ID, "io.quarkus")
            .build();
    private static final Comparator<? super Exclusion> EXCLUSION_COMPARATOR = Comparator.comparing(Exclusion::getGroupId)
            .thenComparing(Exclusion::getArtifactId);

    public FlattenBomTask(List<String> resolutionEntryPointIncludes, List<String> resolutionEntryPointExcludes,
            List<String> resolutionSuspects, List<String> originExcludes,
            List<FlattenBomTask.BomEntryTransformation> bomEntryTransformations,
            List<String> requiredBomEntryIncludes, List<String> requiredBomEntryExcludes,
            OnFailure onCheckFailure,
            MavenProject project,
            Path rootModuleDirectory, Path fullPomPath, Path reducedVerbosePamPath,
            Path reducedPomPath, Charset charset, Log log, List<RemoteRepository> repositories, RepositorySystem repoSystem,
            RepositorySystemSession repoSession, Predicate<Profile> profiles, boolean format,
            SimpleElementWhitespace simpleElementWhitespace, FlattenBomTask.InstallFlavor installFlavor, boolean quickly,
            GavSet bannedDependencies) {
        this.resolutionEntryPointIncludes = resolutionEntryPointIncludes;
        this.resolutionEntryPointExcludes = resolutionEntryPointExcludes;
        this.resolutionSuspects = resolutionSuspects;
        this.originExcludes = originExcludes;
        this.bomEntryTransformations = mergeTransformations(rootModuleDirectory, bomEntryTransformations, charset);
        this.requiredBomEntries = GavSet.builder()
                .includes(requiredBomEntryIncludes == null ? Collections.emptyList() : requiredBomEntryIncludes)
                .excludes(requiredBomEntryExcludes == null ? Collections.emptyList() : requiredBomEntryExcludes)
                .build();
        this.onCheckFailure = onCheckFailure;
        this.project = project;
        this.effectivePomModel = project.getModel();
        this.basePath = project.getBasedir().toPath();
        final Gav self = new Gav(project.getGroupId(), project.getArtifactId(), project.getVersion());
        this.ownManagedDependencies = project.getModel().getDependencyManagement().getDependencies().stream()
                .filter(dep -> !"import".equals(dep.getScope()))
                .filter(dep -> self.equals(Gav.of(dep.getLocation("artifactId").getSource().getModelId())))
                .collect(Collectors.toList());
        this.version = project.getVersion();
        this.rootModuleDirectory = rootModuleDirectory;
        this.fullPomPath = resolve(this.basePath, fullPomPath, FlattenBomTask.DEFAULT_FLATTENED_FULL_POM_FILE);
        this.reducedVerbosePamPath = resolve(this.basePath, reducedVerbosePamPath,
                FlattenBomTask.DEFAULT_FLATTENED_REDUCED_VERBOSE_POM_FILE);
        this.reducedPomPath = resolve(this.basePath, reducedPomPath, FlattenBomTask.DEFAULT_FLATTENED_REDUCED_POM_FILE);
        this.charset = charset;
        this.log = log;
        this.repositories = repositories;
        this.repoSystem = repoSystem;
        this.repoSession = repoSession;
        this.profiles = profiles;
        this.format = format;
        this.simpleElementWhitespace = simpleElementWhitespace;
        this.installFlavor = installFlavor;
        this.quickly = quickly;
        this.bannedDependencies = bannedDependencies;
    }

    static List<FlattenBomTask.BomEntryTransformation> mergeTransformations(Path rootModuleDirectory,
            List<FlattenBomTask.BomEntryTransformation> bomEntryTransformations, Charset charset) {
        final List<FlattenBomTask.BomEntryTransformation> result = new ArrayList<>();
        final Path prodArtifacts = rootModuleDirectory
                .resolve("product/src/main/generated/transitive-dependencies-non-productized.txt");
        if (Files.isRegularFile(prodArtifacts)) {
            try {
                Files.readAllLines(prodArtifacts, charset).stream()
                        .filter(line -> !line.isBlank())
                        .map(line -> new BomEntryTransformation(line, "[\\-\\.]redhat-\\d+$/", null, null))
                        .forEach(result::add);
            } catch (IOException e) {
                throw new RuntimeException("Could not read " + prodArtifacts, e);
            }
        }
        result.addAll(bomEntryTransformations);
        return result;
    }

    static Path resolve(Path basePath, Path relPath, String defaultPath) {
        return basePath.resolve(relPath == null ? Paths.get(defaultPath) : relPath);
    }

    public Path execute() {
        if (!quickly) {
            final GavSet excludedByOrigin = GavSet.builder()
                    .includes(originExcludes == null ? Collections.emptyList() : originExcludes)
                    .build();
            final GavSet resolveSet = GavSet.builder()
                    .includes(resolutionEntryPointIncludes == null ? Collections.emptyList() : resolutionEntryPointIncludes)
                    .excludes(resolutionEntryPointExcludes == null ? Collections.emptyList() : resolutionEntryPointExcludes)
                    .build();

            /* Get the effective pom */
            final DependencyManagement effectiveDependencyManagement = effectivePomModel.getDependencyManagement();
            final List<Dependency> originalConstrains;
            if (effectiveDependencyManagement == null) {
                originalConstrains = Collections.emptyList();
            } else {
                List<Dependency> deps = effectiveDependencyManagement.getDependencies();
                originalConstrains = deps == null
                        ? Collections.emptyList()
                        : Collections.unmodifiableList(deps.stream()
                                .map(Dependency::clone)
                                .peek(dep -> {
                                    if (!bomEntryTransformations.isEmpty()) {
                                        bomEntryTransformations.stream()
                                                .filter(transformation -> transformation.getGavPattern().matches(
                                                        dep.getGroupId(),
                                                        dep.getArtifactId(), dep.getVersion()))
                                                .peek(transformation -> dep
                                                        .setVersion(transformation.replaceVersion(dep.getVersion())))
                                                .forEach(transformation -> {
                                                    final List<Exclusion> depExclusions = dep.getExclusions();
                                                    transformation.getAddExclusions().stream()
                                                            .filter(newExcl -> depExclusions.stream()
                                                                    .noneMatch(oldExcl -> EXCLUSION_COMPARATOR.compare(oldExcl,
                                                                            newExcl) == 0))
                                                            .forEach(depExclusions::add);
                                                    depExclusions.sort(EXCLUSION_COMPARATOR);
                                                });
                                    }
                                })
                                .collect(Collectors.toList()));
            }

            /* Collect the GAs required by our extensions */
            final RequiredGas requiredGas = collectRequiredGas(originalConstrains, ownManagedDependencies, resolveSet);

            /* Filter out constraints managed in io.quarkus:quarkus-bom */
            final Set<Ga> filteredGas = new TreeSet<>();
            final List<Dependency> filteredConstraints = Collections.unmodifiableList(originalConstrains.stream()
                    /* Filter by origin */
                    .filter(dep -> {
                        final Gav locationGav = Gav.of(dep.getLocation("artifactId").getSource().getModelId());
                        final boolean keep = !excludedByOrigin.contains(locationGav);
                        // System.out.println(
                        // (keep ? "Keeping" : "Ignoring") + " constraint " + dep.getGroupId() + ":" +
                        // dep.getArtifactId()
                        // + " defined in " + locationGav);
                        return keep;
                    })
                    /* Exclude non-required constraints */
                    .filter(dep -> requiredGas.gas.contains(new Ga(dep.getGroupId(), dep.getArtifactId())))
                    .peek(dep -> filteredGas.add(new Ga(dep.getGroupId(), dep.getArtifactId())))
                    .collect(Collectors.toList()));
            final Map<Ga, BomEntryData> filteredTransitivesByBomEntry = Collections.unmodifiableMap(
                    requiredGas.transitivesByBomEntry.entrySet().stream()
                            .filter(en -> filteredGas.contains(en.getKey()))
                            .collect(Collectors.toMap(Entry::getKey, Entry::getValue)));

            checkRequiredConstraints(requiredGas.gas, filteredConstraints);
            checkBannedDependencies(filteredTransitivesByBomEntry);

            StringFormatter formatter = new InputLocationStringFormatter(version);
            write(originalConstrains, fullPomPath, effectivePomModel, charset, true, formatter);
            write(filteredConstraints, reducedVerbosePamPath, effectivePomModel, charset, true, formatter);
            write(filteredConstraints, reducedPomPath, effectivePomModel, charset, false, formatter);
        }
        final Path result;
        switch (installFlavor) {
        case FULL:
            result = fullPomPath;
            break;
        case REDUCED:
            result = reducedPomPath;
            break;
        case REDUCED_VERBOSE:
            result = reducedVerbosePamPath;
            break;
        case ORIGINAL:
            /* nothing to do */
            result = project.getFile().toPath();
            break;
        default:
            throw new IllegalStateException(
                    "Unexpected " + FlattenBomTask.InstallFlavor.class.getSimpleName() + ": " + installFlavor);
        }
        project.setPomFile(result.toFile());
        return result;

    }

    RequiredGas collectRequiredGas(List<Dependency> originalFlattenedConstrains, List<Dependency> ownManagedDependencies,
            GavSet resolveSet) {

        final MavenSourceTree t = MavenSourceTree.of(rootModuleDirectory.resolve("pom.xml"), charset);
        final Set<Gavtcs> depsToResolve = collectDependenciesToResolve(originalFlattenedConstrains, resolveSet, t);

        /* Assume that the current BOM's parent is both installed already and that it has no dependencies */
        final Parent parent = effectivePomModel.getParent();
        final DefaultArtifact emptyInstalledArtifact = new DefaultArtifact(
                parent.getGroupId(),
                parent.getArtifactId(),
                null,
                "pom",
                parent.getVersion());
        final Set<Ga> ownGas = t.getModulesByGa().keySet();
        log.debug("Constraints");
        final List<org.eclipse.aether.graph.Dependency> aetherConstraints = originalFlattenedConstrains.stream()
                .filter(dep -> !ownGas.contains(new Ga(dep.getGroupId(), dep.getArtifactId())))
                .map(dep -> new org.eclipse.aether.graph.Dependency(
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
                                .collect(Collectors.toList())))
                .peek(dep -> log.debug(" - " + dep + " " + dep.getExclusions()))
                .collect(Collectors.toList());

        final List<GavPattern> suspects = resolutionSuspects == null
                ? Collections.emptyList()
                : resolutionSuspects.stream().map(GavPattern::of).collect(Collectors.toList());

        final GavSet collectorExcludes = GavSet.builder().include(parent.getGroupId() + ":" + parent.getArtifactId())
                .build();
        final Set<Ga> allTransitives = new TreeSet<>();
        final Map<Ga, BomEntryData> transitivesByBomEntry = new LinkedHashMap<>();
        for (Dependency bomEntry : ownManagedDependencies) {
            final Gavtcs entry = toGavtcs(bomEntry);

            final FlattenBomTask.DependencyCollector collector = new DependencyCollector(collectorExcludes);
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
                final DependencyNode rootNode = repoSystem.collectDependencies(repoSession, request).getRoot();
                rootNode.accept(collector);
            } catch (DependencyCollectionException | IllegalArgumentException e) {
                throw new RuntimeException("Could not resolve dependencies of " + entry, e);
            }

            if (!suspects.isEmpty()) {
                for (Iterator<GavPattern> it = suspects.iterator(); it.hasNext();) {
                    final GavPattern pat = it.next();
                    if (collector.allTransitives.stream()
                            .anyMatch(ga -> pat.matches(ga))) {
                        log.warn("Suspect " + pat + " pulled via " + entry);
                    }
                }
            }
            final boolean isResolutionEntryPoint = depsToResolve.contains(entry);
            transitivesByBomEntry.put(entry.toGa(),
                    new BomEntryData(isResolutionEntryPoint, collector.allTransitives, entry.getExclusions()));
            if (isResolutionEntryPoint) {
                allTransitives.addAll(collector.allTransitives);
            }
        }

        checkManagedCamelQuarkusArtifacts(t, originalFlattenedConstrains);

        allTransitives.addAll(t.getModulesByGa().keySet());
        return new RequiredGas(Collections.unmodifiableSet(allTransitives), transitivesByBomEntry);
    }

    Set<Gavtcs> collectDependenciesToResolve(List<Dependency> originalConstrains, GavSet entryPoints, MavenSourceTree t) {
        final ExpressionEvaluator evaluator = t.getExpressionEvaluator(profiles);
        final Map<Ga, Module> modulesByGa = t.getModulesByGa();
        final Set<Gavtcs> result = new LinkedHashSet<>();
        final Set<String> wantedScopes = new HashSet<>(Arrays.asList("compile", "provided"));
        originalConstrains.stream()
                .filter(dep -> entryPoints.contains(dep.getGroupId(), dep.getArtifactId(), dep.getVersion()))
                .forEach(mvnDep -> {
                    final Ga ga = new Ga(mvnDep.getGroupId(), mvnDep.getArtifactId());
                    final Module module = modulesByGa.get(ga);

                    if (module == null) {
                        /*
                         * External artifact - if it was selected by resolutionEntryPointIncludes, then we
                         * want to have it in the entry points set
                         */
                        result.add(toGavtcs(mvnDep));
                    } else {
                        /* Our own module */
                        t.collectOwnDependencies(ga, profiles).stream()
                                .filter(dep -> wantedScopes.contains(dep.getScope()))
                                .map(dep -> {

                                    final String groupId = evaluator.evaluate(dep.getGroupId());
                                    final String artifactId = evaluator.evaluate(dep.getArtifactId());
                                    final String type = dep.getType() == null ? "jar" : dep.getType();
                                    final String classifier = dep.getClassifier() == null ? null
                                            : evaluator.evaluate(dep.getClassifier());
                                    return new Gavtcs(
                                            groupId,
                                            artifactId,
                                            null,
                                            type,
                                            classifier, null);
                                })
                                .filter(dep -> FlattenBomTask.toResolveDependencies.contains(dep.getGroupId(),
                                        dep.getArtifactId()))
                                .map(gavtcs -> {
                                    final String version = originalConstrains.stream()
                                            .filter(d -> gavtcs.getGroupId().equals(d.getGroupId())
                                                    && gavtcs.getArtifactId().equals(d.getArtifactId())
                                                    && compare(gavtcs.getType(), d.getType(), "jar")
                                                    && compare(gavtcs.getClassifier(), d.getClassifier(), ""))
                                            .map(Dependency::getVersion)
                                            .findFirst()
                                            /*
                                             * If the given gatc is not found in the set of original constraints,
                                             * it is an artifact managed in a BOM distinct from ours (e.g. in
                                             * quarkus-bom).
                                             * Transitives of artifacts managed in quarkus might matter in some cases.
                                             * Maybe we should resolve using all available BOMs
                                             */
                                            .orElse(null);
                                    final SortedSet<Ga> exclusions = getExclusions(mvnDep);
                                    return new Gavtcs(
                                            gavtcs.getGroupId(),
                                            gavtcs.getArtifactId(),
                                            version,
                                            gavtcs.getType(),
                                            gavtcs.getClassifier(),
                                            null,
                                            exclusions);
                                })
                                .filter(gavtcs -> gavtcs.getVersion() != null)
                                .forEach(result::add);
                    }
                });
        return result;
    }

    static SortedSet<Ga> getExclusions(Dependency mvnDep) {
        return (mvnDep.getExclusions() == null ? Collections.<Exclusion> emptyList()
                : mvnDep.getExclusions())
                .stream()
                .map(excl -> new Ga(excl.getGroupId(), excl.getArtifactId()))
                .collect(Collectors.toCollection(TreeSet::new));
    }

    static Gavtcs toGavtcs(Dependency mvnDep) {
        final SortedSet<Ga> exclusions = getExclusions(mvnDep);
        return new Gavtcs(
                mvnDep.getGroupId(),
                mvnDep.getArtifactId(),
                mvnDep.getVersion(),
                mvnDep.getType(),
                mvnDep.getClassifier(),
                null,
                exclusions);
    }

    void checkManagedCamelQuarkusArtifacts(MavenSourceTree t, List<Dependency> originalConstrains) {
        final Set<Ga> cqGas = t.getModulesByGa().keySet();
        final Set<Ga> managedCqGas = new TreeSet<Ga>();

        /* Check whether all org.apache.camel.quarkus:* entries managed in the BOM exist in the source tree */
        final String staleCqArtifacts = originalConstrains.stream()
                .filter(dep -> dep.getGroupId().equals(FlattenBomTask.ORG_APACHE_CAMEL_QUARKUS_GROUP_ID))
                .peek(dep -> managedCqGas.add(new Ga(dep.getGroupId(), dep.getArtifactId())))
                .filter(dep -> version.equals(dep.getVersion()))
                .map(dep -> new Ga(dep.getGroupId(), dep.getArtifactId()))
                .filter(ga -> !cqGas.contains(ga))
                .map(Ga::toString)
                .distinct()
                .sorted()
                .collect(Collectors.joining("\n    "));
        if (!staleCqArtifacts.isEmpty()) {
            String msg = "Please remove these non-existent org.apache.camel.quarkus:* entries managed in camel-quarkus-bom:\n\n    "
                    + staleCqArtifacts
                    + "\n\n";
            reportFailure(msg);
        }

        /* Check whether all extensions have runtime artifacts managed */
        final String missingRuntimeEntries = managedCqGas.stream()
                .filter(ga -> ga.getArtifactId().endsWith("-deployment"))
                .map(ga -> new Ga(ga.getGroupId(), toRuntimeArtifactId(ga.getArtifactId())))
                .filter(ga -> !managedCqGas.contains(ga))
                .map(Ga::toString)
                .collect(Collectors.joining("\n    "));
        if (!missingRuntimeEntries.isEmpty()) {
            String msg = "Please add these entries to camel-quarkus-bom:\n\n    "
                    + missingRuntimeEntries
                    + "\n\n";
            reportFailure(msg);
        }

        /* Check whether all extensions have deployment artifacts managed */
        final String missingDeploymentEntries = managedCqGas.stream()
                .filter(ga -> !ga.getArtifactId().endsWith("-deployment"))
                .map(ga -> new Ga(ga.getGroupId(), ga.getArtifactId() + "-deployment"))
                .filter(ga -> !managedCqGas.contains(ga) && cqGas.contains(ga))
                .map(Ga::toString)
                .collect(Collectors.joining("\n    "));
        if (!missingDeploymentEntries.isEmpty()) {
            String msg = "Please add these entries to camel-quarkus-bom:\n\n    "
                    + missingDeploymentEntries
                    + "\n\n";
            reportFailure(msg);
        }

    }

    static String toRuntimeArtifactId(String deploymentArtifactId) {
        return deploymentArtifactId.substring(0, deploymentArtifactId.length() - "-deployment".length());
    }

    void checkRequiredConstraints(Set<Ga> allTransitives, List<Dependency> originalConstrains) {
        final List<String> expectedRequiredBomEntries = allTransitives.stream()
                .filter(ga -> requiredBomEntries.contains(ga))
                .filter(ga -> !bannedDependencies.contains(ga))
                .map(Ga::toString)
                .sorted()
                .collect(Collectors.toList());

        final List<String> actualRequiredBomEntries = originalConstrains.stream()
                .filter(dep -> requiredBomEntries.contains(dep.getGroupId(), dep.getArtifactId()))
                .map(dep -> dep.getGroupId() + ":" + dep.getArtifactId())
                .distinct()
                .sorted()
                .collect(Collectors.toList());

        final List<Delta<String>> diffs = DiffUtils.diff(expectedRequiredBomEntries, actualRequiredBomEntries).getDeltas();
        if (!diffs.isEmpty()) {
            String msg = "Too little or too much required constraints in " + project.getArtifactId() + ":\n\n    "
                    + diffs.stream().map(Delta::toString).collect(joining("\n    "))
                    + "\n\nConsider adding, removing or excluding them in the BOM\n\n";
            reportFailure(msg);
        }
    }

    public void reportFailure(String msg) {
        switch (onCheckFailure) {
        case FAIL:
            throw new RuntimeException(msg);
        case WARN:
            log.warn(msg);
            break;
        case IGNORE:
            break;
        default:
            throw new IllegalStateException("Unexpected " + OnFailure.class + " value " + onCheckFailure);
        }
    }

    void checkBannedDependencies(Map<Ga, BomEntryData> filteredTransitivesByBomEntry) {

        log.debug("Banned patterns " + bannedDependencies);

        /* A map from BOM entry Ga to map from banned Ga to GavPatterns matching the banned Ga */
        final Map<Ga, Set<Ga>> missingBannedDeps = new LinkedHashMap<>();
        for (Entry<Ga, BomEntryData> entry : filteredTransitivesByBomEntry.entrySet()) {
            final Ga bomEntry = entry.getKey();

            if (bannedDependencies.contains(bomEntry)) {
                throw new IllegalStateException(
                        "The BOM contains an entry " + bomEntry + " that is banned.\nBanned patterns: " + bannedDependencies);
            }

            final Set<Ga> transitives = entry.getValue().transitiveDependencies;
            final Set<Ga> missingExclusions = transitives.stream()
                    .filter(bannedDependencies::contains)
                    .collect(Collectors.toCollection(TreeSet::new));

            /* Add the exclusions defined in bomEntryTransformations to the matching BOM entries */
            Set<Ga> availableExclusions = entry.getValue().exclusions;
            bomEntryTransformations.stream()
                    .filter(transformation -> transformation.getGavPattern().matches(bomEntry))
                    .map(BomEntryTransformation::getAddExclusions)
                    .flatMap(List::stream)
                    .map(exclusion -> new Ga(exclusion.getGroupId(), exclusion.getArtifactId()))
                    .filter(exclusion -> !availableExclusions.contains(exclusion))
                    .forEach(missingExclusions::add);

            if (!missingExclusions.isEmpty()) {
                missingBannedDeps.put(bomEntry, missingExclusions);
            }
        }

        if (!missingBannedDeps.isEmpty()) {
            if (format) {
                new PomTransformer(basePath.resolve("pom.xml"), charset, simpleElementWhitespace)
                        .transform((org.w3c.dom.Document doc, TransformationContext context) -> {

                            final Set<NodeGavtcs> deps = context.getManagedDependencies();
                            final Set<Ga> doneMissingBannedDeps = new TreeSet<>();
                            missingBannedDeps.forEach((bomEntry, missingExclusions) -> {
                                deps.stream()
                                        .filter(dep -> dep.toGa().equals(bomEntry))
                                        .forEach(dep -> {
                                            doneMissingBannedDeps.add(bomEntry);
                                            final ContainerElement exclusionsNode = dep.getNode()
                                                    .getOrAddChildContainerElement("exclusions");
                                            missingExclusions.forEach(
                                                    missingExclusion -> FlattenBomTask.addExclusion(exclusionsNode,
                                                            missingExclusion));
                                        });
                            });

                            final ContainerElement cqMavenPluginNode = context
                                    .getContainerElement("project", "build", "plugins")
                                    .orElseThrow()
                                    .childElementsStream()
                                    .map(ContainerElement::asGavtcs)
                                    .filter(pluginNode -> pluginNode.getGroupId().equals("org.l2x6.cq")
                                            && pluginNode.getArtifactId().equals("cq-maven-plugin"))
                                    .findFirst()
                                    .orElseThrow(() -> new IllegalStateException(
                                            "Could not find org.l2x6.cq:cq-maven-plugin in " + context.getXPath()))
                                    .getNode();

                            final ContainerElement flattenBomExecutionNode = cqMavenPluginNode
                                    .getChildContainerElement("executions")
                                    .orElseThrow(() -> new IllegalStateException(
                                            "Could not find org.l2x6.cq:cq-maven-plugin/executions in "
                                                    + context.getXPath()))
                                    .childElementsStream()
                                    .filter(executionNode -> {
                                        Optional<ContainerElement> idNode = executionNode.getChildContainerElement("id");
                                        return idNode.isPresent()
                                                && idNode.get().getNode().getTextContent().equals("flatten-bom");
                                    })
                                    .findFirst()
                                    .orElseThrow(() -> new IllegalStateException(
                                            "Could not find flatten-bom execution of org.l2x6.cq:cq-maven-plugin in "
                                                    + context.getXPath()));
                            final ContainerElement bomEntryTransformationsNode = flattenBomExecutionNode
                                    .getOrAddChildContainerElement("configuration")
                                    .getOrAddChildContainerElement("bomEntryTransformations");
                            List<Entry<Ga, Set<Ga>>> missingTransformations = missingBannedDeps.entrySet().stream()
                                    .filter(en -> !doneMissingBannedDeps.contains(en.getKey()))
                                    .collect(Collectors.toList());

                            if (!missingTransformations.isEmpty()) {
                                final Map<GavPattern, BomEntryTransformationData> transformations = new TreeMap<>();

                                bomEntryTransformationsNode
                                        .childElementsStream()
                                        .map(bomEntryTransformationNode -> new BomEntryTransformationData(
                                                new BomEntryTransformation(
                                                        bomEntryTransformationNode.getChildContainerElement("gavPattern")
                                                                .get()
                                                                .getNode().getTextContent(),
                                                        bomEntryTransformationNode
                                                                .getChildContainerElement("versionReplacement")
                                                                .map(node -> node.getNode().getTextContent())
                                                                .orElse(null),
                                                        bomEntryTransformationNode.getChildContainerElement("exclusions")
                                                                .map(node -> node.getNode().getTextContent())
                                                                .orElse(null),
                                                        bomEntryTransformationNode.getChildContainerElement("addExclusions")
                                                                .map(node -> node.getNode().getTextContent())
                                                                .orElse(null)),
                                                bomEntryTransformationNode)

                                        )
                                        .forEach(bomEntryTransformationData -> {
                                            BomEntryTransformationData oldEntry = transformations
                                                    .get(bomEntryTransformationData.bomEntryTransformation.getGavPattern());
                                            if (oldEntry != null) {
                                                throw new IllegalStateException(
                                                        "Cannot handle bomEntryTransformations with the same gavPattern: "
                                                                + oldEntry.bomEntryTransformation + " vs. "
                                                                + bomEntryTransformationData.bomEntryTransformation
                                                                + "; please merge them manually");
                                            } else {
                                                transformations.put(
                                                        bomEntryTransformationData.bomEntryTransformation.getGavPattern(),
                                                        bomEntryTransformationData);
                                            }
                                        });

                                missingTransformations.stream()
                                        .forEach(en -> {
                                            final GavPattern pattern = GavPattern.of(en.getKey().toString());
                                            final Set<Ga> missingExclusions = en.getValue();
                                            final BomEntryTransformationData oldTransformation = transformations
                                                    .get(pattern);
                                            if (oldTransformation != null) {
                                                /*
                                                 * Add the missing exclusions to an existing bomEntryTransformation
                                                 * node
                                                 */
                                                oldTransformation.addExclusions(missingExclusions);
                                            } else {
                                                /* Create a new bomEntryTransformation node */
                                                final BomEntryTransformationData bomEntryTransformationData = BomEntryTransformationData
                                                        .create(
                                                                pattern,
                                                                missingExclusions,
                                                                bomEntryTransformationsNode);
                                                transformations.put(pattern, bomEntryTransformationData);
                                            }
                                        });
                            }
                        });
            }

            if (!missingBannedDeps.isEmpty()) {

                final StringBuilder msg = new StringBuilder("Missing exclusions in ")
                        .append(effectivePomModel.getArtifactId())
                        .append(":\n");
                missingBannedDeps
                        .forEach((gavtcs, missingExclusions) -> msg
                                .append("\n    ")
                                .append(gavtcs)
                                .append(" pulls banned dependencies ")
                                .append(missingExclusions));
                throw new RuntimeException(msg.append(
                        "\n\nYou may want to consider running\n\n    mvn process-resources -Dcq.flatten-bom.format\n\nto fix the named issues in this BOM")
                        .toString());
            }
        }
    }

    static String reformat(String xml, Charset charset) {
        SAXBuilder builder = new SAXBuilder();
        builder.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        builder.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        try {
            Document effectiveDocument = builder.build(new StringReader(xml));

            StringWriter w = new StringWriter();
            Format format = Format.getPrettyFormat();
            format.setEncoding(charset.name());
            format.setLineSeparator("\n");
            format.setOmitDeclaration(false);
            XMLOutputter out = new XMLOutputter(format);
            out.output(effectiveDocument, w);

            return LOCATION_COMMENT_PATTERN.matcher(w.toString()).replaceAll("<!--");
        } catch (JDOMException | IOException e) {
            throw new RuntimeException("Could not reformat ", e);
        }
    }

    static void addExclusion(ContainerElement exclusions, Ga newExclusion) {
        Node refNode = null;
        for (ContainerElement dep : exclusions.childElements()) {
            final Ga depGavtcs = dep.asGavtcs().toGa();
            int comparison = newExclusion.compareTo(depGavtcs);
            if (comparison == 0) {
                /* the given exclusion is available, no need to add it */
                return;
            }
            if (refNode == null && comparison < 0) {
                refNode = dep.previousSiblingInsertionRefNode();
            }
        }

        if (refNode == null) {
            refNode = exclusions.getOrAddLastIndent();
        }
        final ContainerElement dep = exclusions.addChildContainerElement("exclusion", refNode, false, false);
        dep.addChildTextElement("groupId", newExclusion.getGroupId());
        dep.addChildTextElement("artifactId", newExclusion.getArtifactId());
    }

    static boolean compare(String pomTunerValue, String mavenValue, String defaultValue) {
        if (mavenValue == null || mavenValue.isEmpty() || defaultValue.equals(mavenValue)) {
            return pomTunerValue == null || pomTunerValue.isEmpty() || defaultValue.equals(pomTunerValue);
        }
        return mavenValue.equals(pomTunerValue);
    }

    static void write(List<Dependency> finalConstraints, Path flattenedPomPath, Model project, Charset charset,
            boolean verbose, StringFormatter formatter) {
        final Model model = new Model();
        model.setModelVersion(project.getModelVersion());
        model.setGroupId(project.getGroupId());
        model.setArtifactId(project.getArtifactId());
        model.setVersion(project.getVersion());
        model.setPackaging("pom");
        model.setName(project.getName());
        model.setDescription(project.getDescription());
        model.setUrl(project.getUrl());
        model.setLicenses(project.getLicenses());
        model.setDevelopers(project.getDevelopers());
        model.setScm(project.getScm());
        model.setIssueManagement(project.getIssueManagement());
        model.setDistributionManagement(project.getDistributionManagement());
        final DependencyManagement dm = new DependencyManagement();
        dm.setDependencies(finalConstraints);
        model.setDependencyManagement(dm);

        try {
            Files.createDirectories(flattenedPomPath.getParent());
        } catch (IOException e) {
            throw new RuntimeException("Could not create " + flattenedPomPath.getParent(), e);
        }

        final String newContent;
        final StringWriter sw = new StringWriter();
        try {
            if (verbose) {
                MavenXpp3WriterEx xpp3Writer = new MavenXpp3WriterEx();
                xpp3Writer.setStringFormatter(formatter);
                xpp3Writer.write(sw, model);
            } else {
                new MavenXpp3Writer().write(sw, model);
            }
        } catch (IOException e1) {
            throw new RuntimeException("Could not serialize pom.xml model: \n\n" + model);
        }
        newContent = FlattenBomTask.reformat(sw.toString(), charset);

        final String originalContent;
        if (Files.exists(flattenedPomPath)) {
            try {
                originalContent = new String(Files.readAllBytes(flattenedPomPath), charset);
            } catch (IOException e) {
                throw new RuntimeException("Could not read " + flattenedPomPath, e);
            }
        } else {
            originalContent = "";
        }

        if (!newContent.equals(originalContent)) {
            try (Writer out = Files.newBufferedWriter(flattenedPomPath, charset)) {
                out.write(newContent);
            } catch (IOException e) {
                throw new RuntimeException("Could not write " + flattenedPomPath, e);
            }
        }
    }

}
