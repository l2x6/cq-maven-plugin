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
package org.l2x6.cq.maven;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.xml.XMLConstants;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.TransformerFactoryConfigurationError;
import javax.xml.transform.dom.DOMResult;
import javax.xml.transform.stream.StreamSource;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.DependencyManagement;
import org.apache.maven.model.Exclusion;
import org.apache.maven.model.InputLocation;
import org.apache.maven.model.InputSource;
import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.apache.maven.model.io.xpp3.MavenXpp3Writer;
import org.apache.maven.model.io.xpp3.MavenXpp3WriterEx;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
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
import org.l2x6.cq.common.OnFailure;
import org.l2x6.pom.tuner.ExpressionEvaluator;
import org.l2x6.pom.tuner.MavenSourceTree;
import org.l2x6.pom.tuner.MavenSourceTree.ActiveProfiles;
import org.l2x6.pom.tuner.PomTunerUtils;
import org.l2x6.pom.tuner.model.Ga;
import org.l2x6.pom.tuner.model.Gav;
import org.l2x6.pom.tuner.model.GavPattern;
import org.l2x6.pom.tuner.model.GavSet;
import org.l2x6.pom.tuner.model.Gavtcs;
import org.l2x6.pom.tuner.model.Module;
import org.l2x6.pom.tuner.model.Profile;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import static java.util.stream.Collectors.joining;

/**
 * Flattens the dependency management section of the current pom.xml file.
 *
 * @since 2.24.0
 */
@Mojo(name = "flatten-bom", threadSafe = true, requiresProject = true)
public class FlattenBomMojo extends AbstractMojo {

    /**
     * The Maven project.
     *
     * @since 2.24.0
     */
    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    /**
     * Directory where the changes should be performed. Default is the current directory of the current Java process.
     *
     * @since 2.24.0
     */
    @Parameter(property = "cq.basedir", defaultValue = "${project.basedir}")
    File basedir;

    /**
     * Encoding to read and write files in the current source tree
     *
     * @since 2.24.0
     */
    @Parameter(defaultValue = "${project.build.sourceEncoding}", required = true, property = "project.build.sourceEncoding")
    String encoding;
    Charset charset;

    /**
     * The root directory of the Camel Quarkus source tree.
     *
     * @since 2.24.0
     */
    @Parameter(defaultValue = "${maven.multiModuleProjectDirectory}", readonly = true)
    File multiModuleProjectDirectory;
    Path rootModuleDirectory;

    /**
     * Skip the execution of this mojo.
     *
     * @since 2.24.0
     */
    @Parameter(property = "cq.flatten-bom.skip", defaultValue = "false")
    boolean skip;

    /**
     * A list of {@link GavPattern}s to match against the GAVs of BOMs in which the given BOM entry is defined.
     * The entries satisfying this criterion will be excluded from the resulting flattened BOM.
     * <p>
     * We will typically want to exclude entries defined in {@code io.quarkus:quarkus-bom}. To do so, we would have to
     * set the following:
     *
     * <pre>
     * {@code
     * <originExcludes>
     *   <originExclude>io.quarkus:quarkus-bom</originExclude>
     * </originExcludes>
     * }
     * </pre>
     *
     * @since 2.24.0
     */
    @Parameter(property = "cq.originExcludes")
    String[] originExcludes;

    /**
     *
     * @since 2.24.0
     */
    @Parameter(property = "cq.resolutionEntryPointIncludes")
    String[] resolutionEntryPointIncludes;

    /**
     *
     * @since 2.24.0
     */
    @Parameter(property = "cq.resolutionEntryPointExcludes")
    String[] resolutionEntryPointExcludes;

    /**
     * As list of GAV patterns whose origin will be logged. Useful when searching on which BOM entry some specific
     * exclusion
     * needs to be placed.
     *
     * @since 2.24.0
     */
    @Parameter(property = "cq.resolutionSuspects")
    String[] resolutionSuspects;

    /**
     * Where to store the non-reduced flattened BOM. An absolute path or a path relative to <code>${basedir}</code>.
     * Useful as a base for comparisons with {@link #flattenedReducedVerbosePomFile}.
     *
     * @since 2.24.0
     */
    @Parameter(defaultValue = "src/main/generated/flattened-full-pom.xml", property = "cq.flattenedFullPomFile")
    File flattenedFullPomFile;

    /**
     * Where to store the reduced flattened BOM. An absolute path or a path relative to <code>${basedir}</code>.
     *
     * @since 2.24.0
     */
    @Parameter(defaultValue = "src/main/generated/flattened-reduced-pom.xml", property = "cq.flattenedReducedPomFile")
    File flattenedReducedPomFile;

    /**
     * Where to store the reduced flattened BOM with comments about the origin of individual entries. An absolute path
     * or a path relative to <code>${basedir}</code>.
     *
     * @since 2.24.0
     */
    @Parameter(defaultValue = "src/main/generated/flattened-reduced-verbose-pom.xml", property = "cq.flattenedReducedVerbosePomFile")
    File flattenedReducedVerbosePomFile;

    /**
     * If {@code true}, {@link #flattenedFullPomFile} and {@link #flattenedReducedPomFile} will be written,
     * but the reduced flattened BOM will not get effective in the current Maven build. I.e. when invoking
     * {@code mvn install -Dcq.flatten-bom.dryRun} the original non-flattened BOM will be installed.
     * This might be useful for testing and debugging purposes.
     *
     * @since 2.24.0
     */
    @Parameter(defaultValue = "false", property = "cq.flatten-bom.dryRun")
    boolean dryRun;

    /**
     * What should happen when the checks performed by this plugin fail. Possible values: {@code WARN}, {@code FAIL},
     * {@code IGNORE}.
     *
     * @since 2.24.0
     */
    @Parameter(property = "cq.onCheckFailure", defaultValue = "FAIL")
    OnFailure onCheckFailure;

    /**
     * What should happen when the checks performed by this plugin fail. Possible values: {@code WARN}, {@code FAIL},
     * {@code IGNORE}.
     *
     * @since 2.24.0
     */
    @Parameter(property = "cq.onCheckFailure", defaultValue = "FAIL")
    List<AddExclusion> addExclusions;

    @Component
    RepositorySystem repoSystem;

    @Parameter(defaultValue = "${repositorySystemSession}", readonly = true, required = true)
    RepositorySystemSession repoSession;

    @Parameter(defaultValue = "${project.remoteProjectRepositories}", readonly = true, required = true)
    List<RemoteRepository> repositories;

    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    MavenSession session;

    private static final GavSet toResolveDependencies = GavSet.builder().excludes("org.apache.camel.quarkus", "io.quarkus")
            .build();

    private static final Pattern LOCATION_COMMENT_PATTERN = Pattern.compile("\\s*\\Q<!--#}\\E");

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("Skipping as requested by the user");
            return;
        }
        charset = Charset.forName(encoding);
        rootModuleDirectory = multiModuleProjectDirectory.toPath().toAbsolutePath().normalize();

        final GavSet excludedByOrigin = GavSet.builder().includes(originExcludes == null ? new String[0] : originExcludes)
                .build();
        final GavSet resolveSet = GavSet.builder()
                .includes(resolutionEntryPointIncludes == null ? new String[0] : resolutionEntryPointIncludes)
                .excludes(resolutionEntryPointExcludes == null ? new String[0] : resolutionEntryPointExcludes)
                .build();

        /* Get the effective pom */
        final Model effectivePomModel = project.getModel();
        final DependencyManagement effectiveDependencyManagement = effectivePomModel.getDependencyManagement();
        final List<Dependency> originalConstrains;
        if (effectiveDependencyManagement == null) {
            originalConstrains = Collections.emptyList();
        } else {
            final List<Dependency> deps = effectiveDependencyManagement.getDependencies();
            originalConstrains = deps == null
                    ? Collections.emptyList()
                    : Collections.unmodifiableList(deps.stream()
                            .map(Dependency::clone)
                            .peek(dep -> {
                                if (addExclusions != null && !addExclusions.isEmpty()) {
                                    addExclusions.stream()
                                            .filter(exclItem -> exclItem.getGavPattern().matches(dep.getGroupId(),
                                                    dep.getArtifactId(), dep.getVersion()))
                                            .flatMap(exclItem -> Stream.of(exclItem.getExclusions().split("[,\\s]+")))
                                            .map(exclusion -> {
                                                final String[] parts = exclusion.split(":");
                                                final Exclusion excl = new Exclusion();
                                                excl.setGroupId(parts[0]);
                                                excl.setArtifactId(parts[1]);
                                                //getLog().warn("Adding exclusion " + excl + " to " + dep);
                                                return excl;
                                            })
                                            .forEach(excl -> dep.getExclusions().add(excl));
                                }
                            })
                            .collect(Collectors.toList()));
        }

        /* Collect the GAs required by our extensions */
        final Set<Ga> requiredGas = collectRequiredGas(originalConstrains, resolveSet);

        /* Filter out constraints managed in io.quarkus:quarkus-bom */
        final List<Dependency> filteredConstraints = Collections.unmodifiableList(originalConstrains.stream()
                /* Filter by origin */
                .filter(dep -> {
                    final Gav locationGav = Gav.of(dep.getLocation("artifactId").getSource().getModelId());
                    return !excludedByOrigin.contains(locationGav.getGroupId(), locationGav.getArtifactId(),
                            locationGav.getVersion());
                })
                /* Exclude non-required constraints */
                .filter(dep -> requiredGas.contains(new Ga(dep.getGroupId(), dep.getArtifactId())))
                .collect(Collectors.toList()));

        write(originalConstrains, basedir.toPath().resolve(flattenedFullPomFile.toPath()), project, charset, true);
        write(filteredConstraints, basedir.toPath().resolve(flattenedReducedVerbosePomFile.toPath()), project, charset, true);

        final Path reducedPomPath = basedir.toPath().resolve(flattenedReducedPomFile.toPath());
        write(filteredConstraints, reducedPomPath, project, charset, false);

        if (!dryRun) {
            project.setPomFile(reducedPomPath.toFile());
        }
    }

    static void write(List<Dependency> finalConstraints, Path flattenedPomPath, MavenProject project, Charset charset,
            boolean verbose) {
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
        try (Writer out = Files.newBufferedWriter(flattenedPomPath, charset)) {
            final StringWriter sw = new StringWriter();
            if (verbose) {
                MavenXpp3WriterEx xpp3Writer = new MavenXpp3WriterEx();
                xpp3Writer.setStringFormatter(new InputLocationStringFormatter());
                xpp3Writer.write(sw, model);
            } else {
                new MavenXpp3Writer().write(sw, model);
            }
            out.write(reformat(sw.toString(), charset));
        } catch (IOException e) {
            throw new RuntimeException("Could not write " + flattenedPomPath, e);
        }
    }

    Set<Ga> collectRequiredGas(List<Dependency> originalConstrains, GavSet resolveSet) {

        final MavenSourceTree t = MavenSourceTree.of(rootModuleDirectory.resolve("pom.xml"), charset);
        final Set<Gavtcs> depsToResolve = collectDependenciesToResolve(originalConstrains, resolveSet, t);

        /* Assume that the current BOM's parent is both installed already and that it has no dependencies */
        final Parent parent = project.getModel().getParent();
        final DefaultArtifact emptyInstalledArtifact = new DefaultArtifact(
                parent.getGroupId(),
                parent.getArtifactId(),
                null,
                "pom",
                parent.getVersion());
        final Set<Ga> ownGas = t.getModulesByGa().keySet();
        getLog().debug("Constraints");
        final List<org.eclipse.aether.graph.Dependency> aetherConstraints = originalConstrains.stream()
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
                                .map(e -> new org.eclipse.aether.graph.Exclusion(e.getGroupId(), e.getArtifactId(), "*", "*"))
                                .collect(Collectors.toList())))
                .peek(dep -> getLog().debug(" - " + dep + " " + dep.getExclusions()))
                .collect(Collectors.toList());

        final List<GavPattern> suspects = resolutionSuspects == null
                ? Collections.emptyList()
                : Stream.of(resolutionSuspects).map(GavPattern::of).collect(Collectors.toList());

        final GavSet collectorExcludes = GavSet.builder().include(parent.getGroupId() + ":" + parent.getArtifactId()).build();
        final Set<Ga> allTransitives = new TreeSet<>();
        final Map<Gavtcs, Set<Ga>> transitivesByBomEntry = new LinkedHashMap<>();
        for (Gavtcs entryPoint : depsToResolve) {

            final DependencyCollector collector = new DependencyCollector(collectorExcludes);
            final CollectRequest request = new CollectRequest()
                    .setRoot(new org.eclipse.aether.graph.Dependency(emptyInstalledArtifact, null))
                    .setRepositories(repositories)
                    .setManagedDependencies(aetherConstraints)
                    .setDependencies(
                            Collections.singletonList(
                                    new org.eclipse.aether.graph.Dependency(
                                            new DefaultArtifact(
                                                    entryPoint.getGroupId(),
                                                    entryPoint.getArtifactId(),
                                                    entryPoint.getType(),
                                                    entryPoint.getVersion()),
                                            null)));
            try {
                final DependencyNode rootNode = repoSystem.collectDependencies(repoSession, request).getRoot();
                rootNode.accept(collector);
            } catch (DependencyCollectionException | IllegalArgumentException e) {
                throw new RuntimeException("Could not resolve dependencies of " + entryPoint, e);
            }

            if (!suspects.isEmpty()) {
                for (Iterator<GavPattern> it = suspects.iterator(); it.hasNext();) {
                    final GavPattern pat = it.next();
                    if (collector.allTransitives.stream().anyMatch(ga -> pat.matches(ga.getGroupId(), ga.getArtifactId()))) {
                        getLog().warn("Suspect " + pat + " pulled via " + entryPoint);
                    }
                }
            }
            transitivesByBomEntry.put(entryPoint, collector.allTransitives);
            allTransitives.addAll(collector.allTransitives);
        }

        checkManagedCamelArtifacts(allTransitives, originalConstrains);
        checkManagedCamelQuarkusArtifacts(t, originalConstrains);
        checkBannedDependencies(transitivesByBomEntry);

        allTransitives.addAll(t.getModulesByGa().keySet());
        return Collections.unmodifiableSet(allTransitives);
    }

    void checkBannedDependencies(Map<Gavtcs, Set<Ga>> transitivesByBomEntry) {

        final org.w3c.dom.Document document;
        final Path path = rootModuleDirectory.resolve("pom.xml");
        try {
            final Transformer transformer = TransformerFactory.newInstance().newTransformer();
            final DOMResult result = new DOMResult();
            try (Reader r = Files.newBufferedReader(path, charset)) {
                transformer.transform(new StreamSource(r), result);
                document = (org.w3c.dom.Document) result.getNode();
            } catch (IOException e) {
                throw new RuntimeException("Could not read " + path, e);
            } catch (TransformerException e) {
                throw new RuntimeException("Could not parse " + path, e);
            }
        } catch (TransformerConfigurationException | TransformerFactoryConfigurationError e) {
            throw new RuntimeException(e);
        }

        final XPath xPath = XPathFactory.newInstance().newXPath();
        final String expr = "/" + PomTunerUtils.anyNs("bannedDependencies", "excludes", "exclude");
        getLog().warn("banned patterns " + expr);
        final List<GavPattern> bannedPatterns = new ArrayList<>();
        try {
            final NodeList nodes = (NodeList) xPath.evaluate(expr, document, XPathConstants.NODESET);
            for (int i = 0; i < nodes.getLength(); i++) {
                final Node n = nodes.item(i);
                final String bannedPattern = n.getTextContent();
                bannedPatterns.add(GavPattern.of(bannedPattern));
            }
        } catch (XPathExpressionException e) {
            throw new RuntimeException("Could not evaluate " + expr + " on " + path, e);
        }

        getLog().warn("banned patterns " + bannedPatterns);

        final StringBuilder msg = new StringBuilder();

        for (Entry<Gavtcs, Set<Ga>> entry : transitivesByBomEntry.entrySet()) {
            final Set<Ga> transitives = entry.getValue();
            final String bannedEntryPoints = transitives.stream()
                    .flatMap(ga -> bannedPatterns.stream().filter(pat -> pat.matches(ga.getGroupId(), ga.getArtifactId())))
                    .map(GavPattern::toString)
                    .collect(Collectors.joining(", "));
            if (!bannedEntryPoints.isEmpty()) {
                final Gavtcs gavtcs = entry.getKey();
                msg.append("\n    ").append(gavtcs).append(" pulls banned dependencies ").append(bannedEntryPoints);
            }
        }

        if (msg.length() > 0) {
            throw new RuntimeException("Missing exclusions in " + project.getArtifactId() + ":\n" + msg.toString());
        }

    }

    void checkManagedCamelQuarkusArtifacts(MavenSourceTree t, List<Dependency> originalConstrains) {
        final Set<Ga> cqGas = t.getModulesByGa().keySet();
        final Set<Ga> managedCqGas = new TreeSet<Ga>();

        /* Check whether all org.apache.camel.quarkus:* entries managed in the BOM exist in the source tree */
        final String staleCqArtifacts = originalConstrains.stream()
                .filter(dep -> dep.getGroupId().equals("org.apache.camel.quarkus"))
                .map(dep -> new Ga(dep.getGroupId(), dep.getArtifactId()))
                .peek(managedCqGas::add)
                .filter(ga -> !cqGas.contains(ga))
                .map(Ga::toString)
                .distinct()
                .sorted()
                .collect(Collectors.joining("\n    "));
        if (!staleCqArtifacts.isEmpty()) {
            String msg = "Please remove these non-existent org.apache.camel:* entries managed in camel-quarkus-bom:\n\n    "
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

    void checkManagedCamelArtifacts(Set<Ga> allTransitives, List<Dependency> originalConstrains) {
        final List<String> requiredCamelArtifacts = allTransitives.stream()
                .filter(ga -> ga.getGroupId().equals("org.apache.camel"))
                .map(Ga::toString)
                .sorted()
                .collect(Collectors.toList());

        final List<String> managedCamelArtifacts = originalConstrains.stream()
                .filter(dep -> dep.getGroupId().equals("org.apache.camel"))
                .map(dep -> dep.getGroupId() + ":" + dep.getArtifactId())
                .distinct()
                .sorted()
                .collect(Collectors.toList());

        final List<Delta<String>> diffs = DiffUtils.diff(requiredCamelArtifacts, managedCamelArtifacts).getDeltas();
        if (!diffs.isEmpty()) {
            String msg = "Too little or too much org.apache.camel:* entries in camel-quarkus-bom:\n\n    "
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
            getLog().warn(msg);
            break;
        case IGNORE:
            break;
        default:
            throw new IllegalStateException("Unexpected " + OnFailure.class + " value " + onCheckFailure);
        }
    }

    Set<Gavtcs> collectDependenciesToResolve(List<Dependency> originalConstrains, GavSet entryPoints, MavenSourceTree t) {
        final Predicate<Profile> profiles = getProfiles();
        final ExpressionEvaluator evaluator = t.getExpressionEvaluator(profiles);
        final Map<Ga, Module> modulesByGa = t.getModulesByGa();
        final Set<Gavtcs> result = new LinkedHashSet<>();
        final Set<String> wantedScopes = new HashSet<>(Arrays.asList("compile", "provided"));
        originalConstrains.stream()
                .filter(dep -> entryPoints.contains(dep.getGroupId(), dep.getArtifactId(), dep.getVersion()))
                .map(dep -> new Ga(dep.getGroupId(), dep.getArtifactId()))
                .forEach(ga -> {
                    final Module module = modulesByGa.get(ga);
                    if (module != null) {
                        t.collectOwnDependencies(ga, profiles).stream()
                                .filter(dep -> wantedScopes.contains(dep.getScope()))
                                .map(dep -> {

                                    final String groupId = evaluator.evaluate(dep.getGroupId());
                                    final String artifactId = evaluator.evaluate(dep.getArtifactId());
                                    final String type = dep.getType() == null ? "jar" : dep.getType();
                                    final String classifier = dep.getClassifier() == null ? null
                                            : evaluator.evaluate(dep.getClassifier());
                                    final String version = originalConstrains.stream()
                                            .filter(d -> groupId.equals(d.getGroupId())
                                                    && artifactId.equals(d.getArtifactId())
                                                    && compare(type, d.getType(), "jar")
                                                    && compare(classifier, d.getClassifier(), ""))
                                            .map(Dependency::getVersion)
                                            .findFirst()
                                            .orElseThrow();
                                    return new Gavtcs(
                                            groupId,
                                            artifactId,
                                            version,
                                            type,
                                            classifier, null);

                                })
                                .filter(dep -> toResolveDependencies.contains(dep.getGroupId(), dep.getArtifactId()))
                                .forEach(result::add);
                    }
                });
        return result;
    }

    static boolean compare(String pomTunerValue, String mavenValue, String defaultValue) {
        if (mavenValue == null || mavenValue.isEmpty() || defaultValue.equals(mavenValue)) {
            return pomTunerValue == null || pomTunerValue.isEmpty() || defaultValue.equals(pomTunerValue);
        }
        return mavenValue.equals(pomTunerValue);
    }

    Predicate<Profile> getProfiles() {
        final Predicate<Profile> profiles = ActiveProfiles.of(
                session.getCurrentProject().getActiveProfiles().stream()
                        .map(org.apache.maven.model.Profile::getId)
                        .toArray(String[]::new));
        return profiles;
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

    private static class InputLocationStringFormatter
            extends InputLocation.StringFormatter {

        public String toString(InputLocation location) {
            InputSource source = location.getSource();

            String s = source.getModelId(); // by default, display modelId

            if (StringUtils.isBlank(s) || s.contains("[unknown-version]")) {
                // unless it is blank or does not provide version information
                s = source.toString();
            }

            return "#} " + s + ((location.getLineNumber() >= 0) ? ", line " + location.getLineNumber() : "") + " ";
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

    public static class AddExclusion {
        private GavPattern gavPattern;
        private String exclusions;

        public String getExclusions() {
            return exclusions;
        }

        public void setExclusions(String exclusions) {
            this.exclusions = exclusions;
        }

        public GavPattern getGavPattern() {
            return gavPattern;
        }

        public void setGavPattern(String gavPattern) {
            this.gavPattern = GavPattern.of(gavPattern);
        }
    }

}
