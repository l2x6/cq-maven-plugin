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

import com.google.gson.Gson;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.Charset;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.shared.utils.io.DirectoryScanner;
import org.assertj.core.api.Assertions;
import org.l2x6.cq.common.CqCommonUtils;
import org.l2x6.maven.utils.Ga;
import org.l2x6.maven.utils.Gavtcs;
import org.l2x6.maven.utils.MavenSourceTree;
import org.l2x6.maven.utils.MavenSourceTree.ActiveProfiles;
import org.l2x6.maven.utils.MavenSourceTree.Dependency;
import org.l2x6.maven.utils.MavenSourceTree.Expression;
import org.l2x6.maven.utils.MavenSourceTree.Expression.NoSuchPropertyException;
import org.l2x6.maven.utils.MavenSourceTree.Module;
import org.l2x6.maven.utils.MavenSourceTree.Module.Profile;
import org.l2x6.maven.utils.PomTransformer;
import org.l2x6.maven.utils.PomTransformer.SimpleElementWhitespace;
import org.l2x6.maven.utils.PomTransformer.Transformation;

/**
 */
@Mojo(name = "prod-excludes", threadSafe = true, requiresProject = false, inheritByDefault = false)
public class ProdExcludesMojo extends AbstractMojo {
    enum Edition {
        PRODUCT(new HashSet<>(
                Arrays.asList("${camel-quarkus.version}", "${project.version}")), "${camel-quarkus.version}"),
        COMMUNITY(Collections
                .singleton("${camel-quarkus-community.version}"), "${camel-quarkus-community.version}");

        Edition(Set<String> versionExpressions, String preferredVersionExpression) {
            this.versionExpressions = versionExpressions;
            this.preferredVersionExpression = preferredVersionExpression;
        }

        private final Set<String> versionExpressions;
        private final String preferredVersionExpression;
    }

    static final String CAMEL_QUARKUS_PRODUCT_SOURCE_JSON_PATH = "product/src/main/resources/camel-quarkus-product-source.json";
    static final String MODULE_COMMENT = "disabled by cq-prod-maven-plugin:prod-excludes";
    /**
     * The basedir
     *
     * @since 0.40.0
     */
    @Parameter(property = "cq.basedir", defaultValue = "${project.basedir}")
    File basedir;

    /**
     * Encoding to read and write files in the current source tree
     *
     * @since 0.40.0
     */
    @Parameter(defaultValue = "utf-8", required = true, property = "cq.encoding")
    String encoding;
    Charset charset;

    @Parameter(defaultValue = CAMEL_QUARKUS_PRODUCT_SOURCE_JSON_PATH, required = true, property = "cq.productJson")
    File productJson;

    /**
     * Skip the execution of this mojo.
     *
     * @since 0.40.0
     */
    @Parameter(property = "cq.prod-artifacts.skip", defaultValue = "false")
    boolean skip;

    /**
     * A list of {@link DirectoryScanner}s selecting integration test {@code pom.xml} files.
     *
     * @since 1.4.0
     */
    @Parameter
    List<DirectoryScanner> integrationTests;

    /**
     * How to format simple XML elements ({@code <elem/>}) - with or without space before the slash.
     *
     * @since 1.1.0
     */
    @Parameter(property = "cq.simpleElementWhitespace", defaultValue = "SPACE")
    SimpleElementWhitespace simpleElementWhitespace;

    /**
     * Overridden by {@link ProdExcludesCheckMojo}.
     *
     * @return {@code always false}
     */
    protected boolean isChecking() {
        return false;
    }

    /** {@inheritDoc} */
    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("Skipping as requested by the user");
            return;
        }
        charset = Charset.forName(encoding);
        if (integrationTests == null) {
            integrationTests = Collections.emptyList();
        }

        final Path absProdJson = basedir.toPath().resolve(productJson.toPath());
        final Set<Ga> includes = new TreeSet<Ga>();
        final Set<Ga> requiredExtensions = new TreeSet<Ga>();
        final Map<Ga, Set<Ga>> allowedMixedTests = new TreeMap<>();
        try (Reader r = Files.newBufferedReader(absProdJson, charset)) {
            @SuppressWarnings("unchecked")
            final Map<String, Object> json = new Gson().fromJson(r, Map.class);
            @SuppressWarnings("unchecked")
            final Map<String, Object> extensions = (Map<String, Object>) json.get("extensions");
            for (Entry<String, Object> en : extensions.entrySet()) {
                final String artifactId = en.getKey();
                final Ga extensionGa = new Ga("org.apache.camel.quarkus", artifactId);
                requiredExtensions.add(extensionGa);
                includes.add(extensionGa);
                includes.add(new Ga("org.apache.camel.quarkus", artifactId + "-deployment"));

                @SuppressWarnings("unchecked")
                final List<String> allowedMixedTestsList = (List<String>) ((Map<String, Object>) en.getValue())
                        .get("allowedMixedTests");
                if (allowedMixedTestsList != null) {
                    final Set<Ga> moduleAllowedMixedTests = allowedMixedTestsList.stream()
                            .map(a -> new Ga("org.apache.camel.quarkus", a))
                            .collect(Collectors.toCollection(TreeSet::new));
                    allowedMixedTests.put(extensionGa, moduleAllowedMixedTests);
                }
            }
            @SuppressWarnings("unchecked")
            final List<String> additionalProductizedArtifacts = (List<String>) json.get("additionalProductizedArtifacts");
            if (additionalProductizedArtifacts != null) {
                for (String artifactId : additionalProductizedArtifacts) {
                    includes.add(new Ga("org.apache.camel.quarkus", artifactId));
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Could not read " + absProdJson, e);
        }

        /*
         * Let's edit the pom.xml files out of the real source tree if we are just checking or pom editing is not
         * desired
         */
        final Path workRoot = isChecking() ? copyPoms(basedir.toPath()) : basedir.toPath();

        final Map<String, Set<String>> foundMixedTests = Stream.of("jvm", "native")
                .map(k -> new SimpleImmutableEntry<String, Set<String>>(k, new TreeSet<>()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        for (String mixedKey : foundMixedTests.keySet()) {
            final Path mixedModulePath = workRoot.resolve("product/integration-tests-mixed-" + mixedKey + "/pom.xml");
            new PomTransformer(mixedModulePath, charset, simpleElementWhitespace)
                    .transform(Transformation.removeAllModules("mixed", true, true));
        }

        final Path catalogPomPath = workRoot.resolve("catalog/pom.xml");
        /* Remove all virtual deps from the Catalog */
        new PomTransformer(catalogPomPath, charset, simpleElementWhitespace)
                .transform(Transformation.removeDependency(
                        false,
                        true,
                        gavtcs -> gavtcs.isVirtual()));

        final Path rootPomPath = workRoot.resolve("pom.xml");
        MavenSourceTree tree = MavenSourceTree.of(rootPomPath, charset, Dependency::isVirtual);
        final Predicate<Profile> profiles = ActiveProfiles.of();

        /* Re-link any previously commented modules */
        tree = tree.relinkModules(charset, simpleElementWhitespace, MODULE_COMMENT);

        /* Add the modules required by the includes */
        Set<Ga> expandedIncludes = tree.findRequiredModules(includes, profiles);

        /* Tests */
        final Map<Ga, Map<Ga, Set<Ga>>> uncoveredExtensions = new TreeMap<>();
        analyzeTestCoverage(tree, expandedIncludes, profiles, uncoveredExtensions, allowedMixedTests, foundMixedTests);

        getLog().debug("Required modules:");
        expandedIncludes.stream()
                .map(Ga::getArtifactId)
                .sorted()
                .forEach(a -> getLog().debug(" - " + a));

        tree.unlinkModules(expandedIncludes, profiles, charset, simpleElementWhitespace,
                (Set<String> unlinkModules) -> Transformation.commentModules(unlinkModules, MODULE_COMMENT));
        /* Fix the virtual deps in the Catalog */
        final Set<Gavtcs> allVirtualExtensions = requiredExtensions.stream()
                .map(ga -> new Gavtcs(ga.getGroupId(), ga.getArtifactId(), null))
                .map(gavtcs -> gavtcs.toVirtual())
                .collect(Collectors.toSet());
        CqCommonUtils.updateVirtualDependencies(charset, simpleElementWhitespace, allVirtualExtensions, catalogPomPath);

        /* BOMs */
        updateBoms(tree, expandedIncludes, profiles);
        if (!isChecking()) {
            /* Always fix the application BOM */
            final Path src = workRoot.resolve("poms/bom/pom.xml");
            final Path dest = basedir.toPath().resolve("poms/bom/pom.xml");
            try {
                Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                throw new RuntimeException("Could not copy " + src + " to " + dest);
            }
        }

        /* Enable the mixed tests in special modules */
        for (Entry<String, Set<String>> mixedEntry : foundMixedTests.entrySet()) {
            final Path mixedModulePath = tree.getRootDirectory()
                    .resolve("product/integration-tests-mixed-" + mixedEntry.getKey() + "/pom.xml");
            new PomTransformer(mixedModulePath, charset, simpleElementWhitespace)
                    .transform(Transformation.addModules("mixed", mixedEntry.getValue()));
        }
        new PomTransformer(workRoot.resolve("pom.xml"), charset, simpleElementWhitespace)
                .transform(
                        Transformation.uncommentModules(MODULE_COMMENT, m -> m.equals("product")));

        if (isChecking()) {
            assertPomsMatch(workRoot, basedir.toPath());
        }

        if (!uncoveredExtensions.isEmpty()) {

            final StringBuilder sb = new StringBuilder("Unable to find tests for extensions:\n");
            for (Entry<Ga, Map<Ga, Set<Ga>>> ext : uncoveredExtensions.entrySet()) {
                sb.append(" - Extension ").append(ext.getKey().getArtifactId()).append(":\n");
                if (ext.getValue().isEmpty()) {
                    sb.append("   - no test found\n");
                } else {
                    for (Entry<Ga, Set<Ga>> test : ext.getValue().entrySet()) {
                        sb.append("   - Test ").append(test.getKey().getArtifactId())
                                .append(" has unsatisfied dependencies:\n");
                        for (Ga dep : test.getValue()) {
                            sb.append("     - ").append(dep.getArtifactId()).append("\n");
                        }
                    }
                }
            }

            sb.append(".\n\nConsider adding those tests manually via additionalProductizedArtifacts in ")
                    .append(CAMEL_QUARKUS_PRODUCT_SOURCE_JSON_PATH);
            throw new MojoFailureException(sb.toString());
        }

    }

    void updateBoms(MavenSourceTree tree, Set<Ga> expandedIncludes, Predicate<Profile> profiles) {

        for (Module module : tree.getModulesByGa().values()) {
            final List<Transformation> transformations = new ArrayList<>();
            for (Profile profile : module.getProfiles()) {
                final Map<Edition, List<Ga>> gasByVersion = Stream
                        .of(Edition.values())
                        .collect(Collectors.toMap(x -> x, x -> new ArrayList<Ga>(), (m1, m2) -> m2, LinkedHashMap::new));
                if (profiles.test(profile)) {
                    for (Dependency managedDep : profile.getDependencyManagement()) {
                        final Ga depGa = managedDep.resolveGa(tree, profiles);
                        if (depGa.getGroupId().equals("org.apache.camel.quarkus")) {
                            final String rawExpression = managedDep.getVersion().getRawExpression();
                            if (!expandedIncludes.contains(depGa) && !rawExpression
                                    .equals("${camel-quarkus-community.version}")) {

                            }
                            final Edition edition = expandedIncludes.contains(depGa)
                                    ? Edition.PRODUCT
                                    : Edition.COMMUNITY;
                            if (!edition.versionExpressions.contains(rawExpression)
                                    && !"${project.version}".equals(rawExpression)) {
                                gasByVersion.get(edition).add(depGa);
                            }
                        }
                    }
                }
                gasByVersion.entrySet().stream()
                        .filter(en -> !en.getValue().isEmpty())
                        .forEach(en -> transformations
                                .add(Transformation.setManagedDependencyVersion(profile.getId(),
                                        en.getKey().preferredVersionExpression, en.getValue())));
            }
            if (!transformations.isEmpty()) {
                new PomTransformer(tree.getRootDirectory().resolve(module.getPomPath()), charset, simpleElementWhitespace)
                        .transform(transformations);
            }
        }

    }

    static boolean isResolvable(MavenSourceTree tree, Ga ga, Predicate<Profile> profiles, String rawEpression) {
        final Expression expr = new Expression.NonConstant(rawEpression, ga);
        try {
            expr.evaluate(tree, profiles);
            return true;
        } catch (NoSuchPropertyException e) {
            return false;
        }
    }

    void analyzeTestCoverage(final MavenSourceTree tree, final Set<Ga> expandedIncludes, Predicate<Profile> profiles,
            Map<Ga, Map<Ga, Set<Ga>>> uncoveredExtensions, Map<Ga, Set<Ga>> allowedMixedTests,
            Map<String, Set<String>> foundMixedTests) {
        getLog().debug("Included extensions before considering tests:");
        final Set<Ga> expandedExtensions = expandedIncludes.stream()
                .filter(ga -> ga.getArtifactId().endsWith("-deployment"))
                .map(ga -> new Ga(ga.getGroupId(),
                        ga.getArtifactId().substring(0, ga.getArtifactId().length() - "-deployment".length())))
                .peek(ga -> getLog().debug(" - " + ga.getArtifactId()))
                .collect(Collectors.toCollection(TreeSet::new));

        final Map<Ga, Set<Ga>> testModules = new TreeMap<>();
        for (DirectoryScanner scanner : integrationTests) {
            scanner.scan();
            final Path base = scanner.getBasedir().toPath().toAbsolutePath().normalize();
            for (String scannerPath : scanner.getIncludedFiles()) {
                final Path pomXmlPath = base.resolve(scannerPath);
                final Path pomXmlRelPath = basedir.toPath().relativize(pomXmlPath);
                final Module testModule = tree.getModulesByPath().get(pomXmlRelPath.toString());
                if (testModule == null) {
                    throw new IllegalStateException("Could not find module for path " + pomXmlRelPath);
                }
                final Ga moduleGa = testModule.getGav().resolveGa(tree, profiles);
                final Set<Ga> deps = tree.collectTransitiveDependencies(moduleGa, profiles).stream()
                        .map(dep -> dep.resolveGa(tree, profiles))
                        /* keep only local extension dependencies */
                        .filter(dep -> tree.getModulesByGa().keySet()
                                .contains(new Ga(dep.getGroupId(), dep.getArtifactId() + "-deployment")))
                        .collect(Collectors.toSet());
                testModules.merge(moduleGa, deps, (oldSet, newSet) -> {
                    oldSet.addAll(newSet);
                    return oldSet;
                });
            }
        }
        getLog().debug("Found tests:");
        testModules.entrySet().forEach(m -> getLog().debug(" - " + m.getKey().getArtifactId() + ": " + m.getValue()));

        final Set<Ga> prodTests = new TreeSet<>();
        final Set<Ga> finalExpandedIncludes = expandedIncludes;
        getLog().info("Test coverage:");
        for (Ga extensionGa : expandedExtensions) {
            if (!extensionGa.getArtifactId().startsWith("camel-quarkus-support-")) {
                /* Do not analyze the test coverage of the ancillary extensions */
                final Set<Ga> extAllowedMixedTests = allowedMixedTests.getOrDefault(extensionGa, Collections.emptySet());
                boolean covered = false;
                final Map<Ga, Set<Ga>> testsWithMissingDependencies = new TreeMap<>();
                for (Entry<Ga, Set<Ga>> testModule : testModules.entrySet()) {
                    if (testModule.getValue().contains(extensionGa)) {
                        if (extAllowedMixedTests.contains(testModule.getKey())) {
                            /* This test is allowed to be mixed for this specific extension */
                            covered = true;
                            getLog().info(
                                    " - " + extensionGa.getArtifactId() + " is covered by an explicitly allowed mixed test "
                                            + testModule.getKey().getArtifactId());
                        } else if (expandedIncludes.containsAll(testModule.getValue())) {
                            /* This test covers the given extensionGa and all its deps are included */
                            prodTests.add(testModule.getKey());
                            covered = true;
                            getLog().info(
                                    " - " + extensionGa.getArtifactId() + " is covered by "
                                            + testModule.getKey().getArtifactId());
                        } else if (!covered) {
                            /* Store what is missing to be able to report later */
                            testsWithMissingDependencies.put(
                                    testModule.getKey(),
                                    testModule.getValue().stream()
                                            .filter(ga -> !finalExpandedIncludes.contains(ga))
                                            .collect(Collectors.toCollection(TreeSet::new)));
                        }
                    }
                }
                if (!covered) {
                    uncoveredExtensions.put(extensionGa, testsWithMissingDependencies);
                }
            }
        }
        expandedIncludes.addAll(prodTests);

        /* Tests may require some additional modules */
        final Set<Ga> newIncludes = tree.findRequiredModules(expandedIncludes, profiles);
        expandedIncludes.addAll(newIncludes);

        final Path mixedModuleDir = tree.getRootDirectory().resolve("product/integration-tests-mixed-jvm");
        for (Ga testGa : testModules.keySet()) {
            if (!prodTests.contains(testGa)) {
                /* This is a mixed test */
                final Module testModule = tree.getModulesByGa().get(testGa);
                final Path testModulePath = tree.getRootDirectory().resolve(testModule.getPomPath()).getParent();
                final String testModuleRelPath = mixedModuleDir.relativize(testModulePath).toString();
                if (testModule.getPomPath().startsWith("extensions-jvm")) {
                    /* This is a test of a JVM-only extension */
                    foundMixedTests.get("jvm").add(testModuleRelPath);
                } else {
                    /* This is a regular test of an extension that supports native */
                    foundMixedTests.get("native").add(testModuleRelPath);
                }
            }
        }

    }

    private Path copyPoms(Path src) {
        final Path dest = src.resolve("target/prod-excludes-work");
        CqCommonUtils.ensureDirectoryExistsAndEmpty(dest);
        visitPoms(src, file -> {
            final Path destPath = dest.resolve(src.relativize(file));
            try {
                Files.createDirectories(destPath.getParent());
                Files.copy(file, destPath, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                throw new RuntimeException("Could not copy " + file + " to " + destPath, e);
            }
        });
        return dest;
    }

    void assertPomsMatch(Path src, Path dest) {
        visitPoms(src, file -> {
            final Path destPath = dest.resolve(src.relativize(file));
            try {
                Assertions.assertThat(file).hasSameTextualContentAs(destPath);
            } catch (AssertionError e) {
                String msg = e.getMessage();
                final String changedContentAt = "Changed content at";
                int offset = msg.indexOf(changedContentAt);
                if (offset < 0) {
                    throw new IllegalStateException(
                            "Expected to find '" + changedContentAt + "' in the causing exception's message", e);
                }
                msg = "File [" + basedir.toPath().relativize(destPath) + "] is not in sync with "
                        + CAMEL_QUARKUS_PRODUCT_SOURCE_JSON_PATH + ":\n\n"
                        + msg.substring(offset)
                        + "\n\n Consider running mvn org.l2x6.cq:cq-prod-maven-plugin:prod-excludes -N\n\n";
                throw new RuntimeException(msg);
            }
        });
    }

    void visitPoms(Path src, Consumer<Path> pomConsumer) {
        try {
            Files.walkFileTree(src, new SimpleFileVisitor<Path>() {

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                        throws IOException {
                    final String dirName = dir.getFileName().toString();
                    if ((dirName.equals("target") || dirName.equals("src"))
                            && Files.isRegularFile(dir.getParent().resolve("pom.xml"))) {
                        return FileVisitResult.SKIP_SUBTREE;
                    } else {
                        return FileVisitResult.CONTINUE;
                    }
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    final String fileName = file.getFileName().toString();
                    if (fileName.equals("pom.xml")) {
                        pomConsumer.accept(file);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new RuntimeException("Could not visit pom.xml files under " + src, e);
        }
    }

}
