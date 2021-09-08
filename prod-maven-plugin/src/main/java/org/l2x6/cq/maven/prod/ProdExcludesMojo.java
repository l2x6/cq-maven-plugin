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
import java.util.AbstractMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.assertj.core.api.Assertions;
import org.l2x6.cq.common.CqCommonUtils;
import org.l2x6.maven.utils.Ga;
import org.l2x6.maven.utils.Gavtcs;
import org.l2x6.maven.utils.MavenSourceTree;
import org.l2x6.maven.utils.MavenSourceTree.ActiveProfiles;
import org.l2x6.maven.utils.MavenSourceTree.Dependency;
import org.l2x6.maven.utils.MavenSourceTree.Module.Profile;
import org.l2x6.maven.utils.PomTransformer;
import org.l2x6.maven.utils.PomTransformer.SimpleElementWhitespace;
import org.l2x6.maven.utils.PomTransformer.Transformation;

/**
 */
@Mojo(name = "prod-excludes", threadSafe = true, requiresProject = false, inheritByDefault = false)
public class ProdExcludesMojo extends AbstractMojo {
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
     * Remove the excluded module elements from {@code pom.xml} files.
     *
     * @since 1.1.0
     */
    @Parameter(property = "cq.unlinkExcludes", defaultValue = "false")
    boolean unlinkExcludes;

    /**
     * If {@code true} the tests coverage will be analyzed and the tests covering supported extensions will not be added
     * to {@code .mvn/excludes.txt}; otherwise no test coverage analysis will be performed and all tests will be added
     * to {@code .mvn/excludes.txt}.
     *
     * @since 1.2.0
     */
    @Parameter(property = "cq.includeTests", defaultValue = "false")
    boolean includeTests;

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

        final Path absProdJson = basedir.toPath().resolve(productJson.toPath());
        final Set<Ga> includes = new TreeSet<Ga>();
        final Set<Ga> requiredExtensions = new TreeSet<Ga>();
        try (Reader r = Files.newBufferedReader(absProdJson, charset)) {
            final Map<String, Object> json = new Gson().fromJson(r, Map.class);
            final Map<String, Object> extensions = (Map<String, Object>) json.get("extensions");
            for (String artifactId : extensions.keySet()) {
                final Ga extensionGa = new Ga("org.apache.camel.quarkus", artifactId);
                requiredExtensions.add(extensionGa);
                includes.add(extensionGa);
                includes.add(new Ga("org.apache.camel.quarkus", artifactId + "-deployment"));
            }
            final List<String> additionalProductizedArtifacts = (List<String>) json.get("additionalProductizedArtifacts");
            if (additionalProductizedArtifacts != null) {
                for (String artifactId : additionalProductizedArtifacts) {
                    includes.add(new Ga("org.apache.camel.quarkus", artifactId));
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Could not read " + absProdJson);
        }

        /*
         * Let's edit the pom.xml files out of the real source tree if we are just checking or pom editing is not
         * desired
         */
        final Path workRoot = (isChecking() || !unlinkExcludes) ? copyPoms(basedir.toPath()) : basedir.toPath();

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
        if (includeTests) {
            analyzeTestCoverage(tree, expandedIncludes, profiles, uncoveredExtensions);
        }

        getLog().debug("Required modules:");
        expandedIncludes.stream()
                .map(Ga::getArtifactId)
                .sorted()
                .forEach(a -> getLog().debug(" - " + a));

        /* Write the excludesSet to .mvn/excludes.txt */
        final Set<Ga> excludesSet = tree.complement(expandedIncludes);
        writeExcludesTxt(excludesSet);

        tree.unlinkModules(expandedIncludes, profiles, charset, simpleElementWhitespace,
                (Set<String> unlinkModules) -> {
                    if (isChecking() && unlinkExcludes && !unlinkModules.isEmpty()) {
                        throw new RuntimeException("The source tree does not match the content of "
                                + CAMEL_QUARKUS_PRODUCT_SOURCE_JSON_PATH
                                + ". Superfluous modules (the list might be incomplete):\n - "
                                + unlinkModules.stream()
                                        .collect(Collectors.joining("\n - "))
                                + ".\n\nConsider running mvn org.l2x6.cq:cq-prod-maven-plugin:prod-excludes -N");
                    }
                    return Transformation.commentModules(unlinkModules, MODULE_COMMENT);
                });
        /* Fix the virtual deps in the Catalog */
        final Set<Gavtcs> allVirtualExtensions = requiredExtensions.stream()
                .map(ga -> new Gavtcs(ga.getGroupId(), ga.getArtifactId(), null))
                .map(gavtcs -> gavtcs.toVirtual())
                .collect(Collectors.toSet());
        CqCommonUtils.updateVirtualDependencies(charset, simpleElementWhitespace, allVirtualExtensions, catalogPomPath);

        if (isChecking() && unlinkExcludes) {
            assertPomsMatch(workRoot, basedir.toPath());
        }

        if (includeTests && !uncoveredExtensions.isEmpty()) {
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

    void writeExcludesTxt(final Set<Ga> excludesSet) throws MojoFailureException {
        final String newExcludesTxtContent = excludesSet.stream()
                .sorted()
                .map(ga -> (":" + ga.getArtifactId() + "\n"))
                .collect(Collectors.joining());
        final Path excludesTxt = basedir.toPath().resolve(".mvn/excludes.txt");
        if (isChecking() && !Files.isRegularFile(excludesTxt)) {
            throw new MojoFailureException("The source tree does not match the content of "
                    + CAMEL_QUARKUS_PRODUCT_SOURCE_JSON_PATH
                    + ". .mvn/excludes.txt does not exist.\n\nConsider running mvn org.l2x6.cq:cq-prod-maven-plugin:prod-excludes -N");
        }
        final String oldExcludesTxtContent;
        try {
            oldExcludesTxtContent = Files.isRegularFile(excludesTxt)
                    ? new String(Files.readAllBytes(excludesTxt), charset) : "";
        } catch (IOException e) {
            throw new RuntimeException("Could not read " + excludesTxt, e);
        }
        if (!oldExcludesTxtContent.equals(newExcludesTxtContent)) {
            if (isChecking()) {
                throw new MojoFailureException("The content of .mvn/excludes.txt does not match the content of "
                        + CAMEL_QUARKUS_PRODUCT_SOURCE_JSON_PATH
                        + ". Consider running mvn org.l2x6.cq:cq-prod-maven-plugin:prod-excludes -N. Expected content of .mvn/excludes.txt:\n\n"
                        + newExcludesTxtContent);
            } else {
                try {
                    Files.createDirectories(excludesTxt.getParent());
                } catch (IOException e) {
                    throw new RuntimeException("Could not create directory " + excludesTxt.getParent(), e);
                }

                try {
                    Files.write(excludesTxt, newExcludesTxtContent.getBytes(charset));
                } catch (IOException e) {
                    throw new RuntimeException("Could not write to " + excludesTxt, e);
                }
            }
        }
    }

    void analyzeTestCoverage(final MavenSourceTree tree, final Set<Ga> expandedIncludes, Predicate<Profile> profiles,
            Map<Ga, Map<Ga, Set<Ga>>> uncoveredExtensions) {
        getLog().info("Included extensions before considering tests:");
        final Set<Ga> expandedExtensions = expandedIncludes.stream()
                .filter(ga -> ga.getArtifactId().endsWith("-deployment"))
                .map(ga -> new Ga(ga.getGroupId(),
                        ga.getArtifactId().substring(0, ga.getArtifactId().length() - "-deployment".length())))
                .peek(ga -> getLog().info(" - " + ga.getArtifactId()))
                .collect(Collectors.toCollection(TreeSet::new));

        getLog().info("Found tests:");
        final Map<Ga, Set<Ga>> testModules = tree.getModulesByPath().entrySet().stream()
                .filter(en -> en.getKey().startsWith("integration-test"))
                .map(Map.Entry::getValue)
                .map(module -> {
                    final Ga moduleGa = module.getGav().resolveGa(tree, profiles);
                    return new AbstractMap.SimpleImmutableEntry<Ga, Set<Ga>>(
                            moduleGa,
                            tree.collectTransitiveDependencies(moduleGa, profiles).stream()
                                    .map(dep -> dep.resolveGa(tree, profiles))
                                    /* keep only local extension dependencies */
                                    .filter(dep -> tree.getModulesByGa().keySet()
                                            .contains(new Ga(dep.getGroupId(), dep.getArtifactId() + "-deployment")))
                                    .collect(Collectors.toSet()));
                })
                .peek(module -> getLog().info(" - " + module.getKey().getArtifactId() + ": "
                        + module.getValue().stream().map(Ga::getArtifactId).collect(Collectors.joining(", "))))
                .collect(Collectors.toMap(Entry::getKey, Entry::getValue));

        final Set<Ga> includedTests = new TreeSet<>();
        final Set<Ga> finalExpandedIncludes = expandedIncludes;
        getLog().info("Test coverage:");
        for (Ga extensionGa : expandedExtensions) {
            boolean covered = false;
            final Map<Ga, Set<Ga>> testsWithMissingDependencies = new TreeMap<>();
            for (Entry<Ga, Set<Ga>> testModule : testModules.entrySet()) {
                if (testModule.getValue().contains(extensionGa)) {
                    if (expandedIncludes.containsAll(testModule.getValue())) {
                        /* This test covers the given extensionGa and all its deps are included */
                        includedTests.add(testModule.getKey());
                        covered = true;
                        getLog().info(
                                " - " + extensionGa.getArtifactId() + " is covered by " + testModule.getKey().getArtifactId());
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
        expandedIncludes.addAll(includedTests);

        /* Tests may require some additional modules */
        final Set<Ga> newIncludes = tree.findRequiredModules(expandedIncludes, profiles);
        expandedIncludes.addAll(newIncludes);
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
            Assertions.assertThat(file).hasSameTextualContentAs(destPath);
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
