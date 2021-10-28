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
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.assertj.core.api.Assertions;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.repository.RemoteRepository;
import org.l2x6.cq.common.CqCommonUtils;
import org.l2x6.pom.tuner.MavenSourceTree;
import org.l2x6.pom.tuner.MavenSourceTree.ActiveProfiles;
import org.l2x6.pom.tuner.PomTransformer;
import org.l2x6.pom.tuner.PomTransformer.SimpleElementWhitespace;
import org.l2x6.pom.tuner.PomTransformer.Transformation;
import org.l2x6.pom.tuner.PomTransformer.TransformationContext;
import org.l2x6.pom.tuner.PomTunerUtils;
import org.l2x6.pom.tuner.model.Dependency;
import org.l2x6.pom.tuner.model.Ga;
import org.l2x6.pom.tuner.model.Module;
import org.l2x6.pom.tuner.model.Profile;
import org.w3c.dom.Document;

/**
 * Unlink modules that should not be productized from Camel source tree based on
 * {@code product/src/main/resources/required-productized-camel-artifacts.txt}.
 *
 * @since 2.11.0
 */
@Mojo(name = "camel-prod-excludes", threadSafe = true, requiresProject = false, inheritByDefault = false)
public class CamelProdExcludesMojo extends AbstractMojo {

    static final String MODULE_COMMENT = "disabled by cq-prod-maven-plugin:camel-prod-excludes";
    static final String DEFAULT_REQUIRED_PRODUCTIZED_CAMEL_ARTIFACTS_TXT = "target/required-productized-camel-artifacts.txt";

    /**
     * The basedir
     *
     * @since 2.11.0
     */
    @Parameter(property = "cq.basedir", defaultValue = "${project.basedir}")
    File basedir;

    /**
     * Encoding to read and write files in the current source tree
     *
     * @since 2.11.0
     */
    @Parameter(defaultValue = "utf-8", required = true, property = "cq.encoding")
    String encoding;
    Charset charset;

    /**
     * Skip the execution of this mojo.
     *
     * @since 2.11.0
     */
    @Parameter(property = "cq.camel-prod-excludes.skip", defaultValue = "false")
    boolean skip;

    /**
     * How to format simple XML elements ({@code <elem/>}) - with or without space before the slash.
     *
     * @since 2.11.0
     */
    @Parameter(property = "cq.simpleElementWhitespace", defaultValue = "SPACE")
    SimpleElementWhitespace simpleElementWhitespace;

    /**
     * {@code artifactId}s that need to get productized in addition to
     * {@code product/src/main/resources/required-productized-camel-artifacts.txt}.
     *
     * @since 2.11.0
     */
    @Parameter(property = "cq.additionalProductizedArtifactIds", defaultValue = "")
    List<String> additionalProductizedArtifactIds;

    /**
     * Where to write a list of Camel artifacts required by Camel Quarkus productized extensions.
     * It is a text file one artifactId per line.
     *
     * @since 2.11.0
     */
    @Parameter(property = "cq.requiredProductizedCamelArtifacts", defaultValue = "${project.basedir}/"
            + DEFAULT_REQUIRED_PRODUCTIZED_CAMEL_ARTIFACTS_TXT)
    File requiredProductizedCamelArtifacts;

    /**
     * @since 2.11.0
     */
    @Parameter(defaultValue = "${project.remoteProjectRepositories}", readonly = true, required = true)
    List<RemoteRepository> repositories;

    @Parameter(property = "cq.camelCommunityVersion", defaultValue = "${camel-community.version}")
    String camelCommunityVersion;

    /**
     * @since 2.11.0
     */
    @Parameter(defaultValue = "${settings.localRepository}", readonly = true)
    String localRepository;

    @Component
    private RepositorySystem repoSystem;

    @Parameter(defaultValue = "${repositorySystemSession}", readonly = true, required = true)
    private RepositorySystemSession repoSession;

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
        if (additionalProductizedArtifactIds == null) {
            additionalProductizedArtifactIds = Collections.emptyList();
        }
        if (camelCommunityVersion == null || camelCommunityVersion.trim().isEmpty()) {
            camelCommunityVersion = "3.11.1";
        }

        /* Collect the initial set of includes */
        Set<Ga> includes;
        final Path basePath = basedir.toPath();
        try {
            includes = Files.lines(basePath.resolve(requiredProductizedCamelArtifacts.toPath()), charset)
                    .map(line -> new Ga("org.apache.camel", line))
                    .collect(Collectors.toCollection(TreeSet::new));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        /* Add the additional ones */
        additionalProductizedArtifactIds.stream()
                .map(artifactId -> new Ga("org.apache.camel", artifactId))
                .forEach(includes::add);
        /*
         * Let's edit the pom.xml files out of the real source tree if we are just checking or pom editing is not
         * desired
         */
        final Path workRoot = isChecking() ? copyPoms(basePath, basePath.resolve("target/prod-excludes-work")) : basePath;

        final Path rootPomPath = workRoot.resolve("pom.xml");
        new PomTransformer(rootPomPath, charset, simpleElementWhitespace)
                .transform(Transformation.addOrSetProperty("camel-community.version", camelCommunityVersion));

        final MavenSourceTree initialTree = MavenSourceTree.of(rootPomPath, charset, Dependency::isVirtual);
        final Predicate<Profile> profiles = ActiveProfiles.of();

        /* Re-link any previously commented modules */
        final MavenSourceTree fullTree = initialTree.relinkModules(charset, simpleElementWhitespace, MODULE_COMMENT);

        /* Use community versions of the plugins */
        Stream.of("dsl/camel-yaml-dsl/camel-yaml-dsl/pom.xml").forEach(relPath -> {
            new PomTransformer(workRoot.resolve(relPath), charset, simpleElementWhitespace)
                    .transform(Transformation.setTextValue("/" +
                            PomTunerUtils.anyNs("plugin", "version") + "[.." + PomTunerUtils.anyNs("groupId")
                            + "/text() = 'org.apache.camel']",
                            "${camel-community.version}"));
        });

        /* Make a copy of the originalFullTree */
        final Path originalFullTreeCopyDir = copyPoms(workRoot, basePath.resolve("target/originalFullTreeCopy"));

        /* Remove non-prod components from camel-allcomponents in the copy */
        new PomTransformer(originalFullTreeCopyDir.resolve("core/camel-allcomponents/pom.xml"), charset,
                simpleElementWhitespace)
                        .transform(Transformation.removeDependency(true, true, gavtcs -> !includes.contains(gavtcs.toGa())));

        /* Remove own plugins from the copy */
        Stream.of("dsl/camel-yaml-dsl/camel-yaml-dsl/pom.xml").forEach(relPath -> {
            new PomTransformer(originalFullTreeCopyDir.resolve(relPath), charset, simpleElementWhitespace)
                    .transform(Transformation.removePlugins(null, true, true,
                            gavtcs -> gavtcs.getGroupId().equals("org.apache.camel")));
        });

        /* Remove all own test deps and any camel-spring* deps in the copy */
        fullTree.getModulesByGa().values().forEach(module -> {
            final List<Transformation> transformations = new ArrayList<>();

            module.getProfiles().stream()
                    .filter(profile -> !profile.getDependencies().isEmpty())
                    .forEach(profile -> {
                        transformations.add(Transformation.removeDependencies(profile.getId(), true, true,
                                gavtcs -> "org.apache.camel".equals(gavtcs.getGroupId()) && ("test".equals(gavtcs.getScope())
                                        || gavtcs.getArtifactId().startsWith("camel-spring"))));
                    });

            if (!transformations.isEmpty()) {
                new PomTransformer(originalFullTreeCopyDir.resolve(module.getPomPath()), charset, simpleElementWhitespace)
                        .transform(transformations);
            }
        });

        /* Re-read the copy after the above changes */
        final MavenSourceTree originalFullTreeCopy = MavenSourceTree.of(originalFullTreeCopyDir.resolve("pom.xml"), charset,
                Dependency::isVirtual);

        /* Add the modules required by the includes */
        final Set<Ga> expandedIncludes = new TreeSet<>(originalFullTreeCopy.findRequiredModules(includes, profiles));
        getLog().info("expandedIncludes:");
        for (Ga ga : expandedIncludes) {
            getLog().info(" - " + ga.getArtifactId());
        }

        final Set<Ga> excludes = fullTree.complement(expandedIncludes);
        final String exclText = excludes.stream()
                .map(ga -> ":" + ga.getArtifactId())
                .sorted()
                .collect(Collectors.joining("\n"));
        try {
            Files.write(basePath.resolve(".mvn/excludes.txt"), exclText.getBytes(charset));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        updateVersions(fullTree, profiles);

        /* Comment all non-productized modules in the tree */
        fullTree.unlinkModules(expandedIncludes, profiles, charset, simpleElementWhitespace,
                (Set<String> unlinkModules) -> Transformation.commentModules(unlinkModules, MODULE_COMMENT));

        /* Replace ${project.version} with ${camel-community.version} where necessary */
        final MavenSourceTree reducedTree = MavenSourceTree.of(rootPomPath, charset, Dependency::isVirtual);
        reducedTree.getModulesByGa().values().forEach(module -> {
            final List<Transformation> transformations = new ArrayList<>();

            for (Profile profile : module.getProfiles()) {
                final Set<Ga> changeDeps = profile.getDependencies().stream()
                        .filter(dep -> "org.apache.camel".equals(dep.getGroupId().asConstant()) && dep.getVersion() != null)
                        .map(dep -> new Ga(dep.getGroupId().asConstant(), dep.getArtifactId().asConstant()))
                        .filter(excludes::contains)
                        .collect(Collectors.toCollection(HashSet::new));
                if (!changeDeps.isEmpty()) {
                    transformations
                            .add(Transformation.setDependencyVersion(profile.getId(), "${camel-community.version}",
                                    changeDeps));
                }
                final Set<Ga> changeManagedDeps = profile.getDependencyManagement().stream()
                        .filter(dep -> "org.apache.camel".equals(dep.getGroupId().asConstant()))
                        .map(dep -> new Ga(dep.getGroupId().asConstant(), dep.getArtifactId().asConstant()))
                        .filter(excludes::contains)
                        .collect(Collectors.toCollection(HashSet::new));
                if (!changeManagedDeps.isEmpty()) {
                    final String version = module.getPomPath().equals("bom/camel-bom/pom.xml") ? camelCommunityVersion
                            : "${camel-community.version}";
                    transformations.add(Transformation.setManagedDependencyVersion(profile.getId(),
                            version, excludes));
                }
            }
            if (!transformations.isEmpty()) {
                new PomTransformer(workRoot.resolve(module.getPomPath()), charset, simpleElementWhitespace)
                        .transform(transformations);
            }
        });
        Stream.of("pom.xml", "parent/pom.xml").forEach(relPath -> {
            new PomTransformer(workRoot.resolve(relPath), charset, simpleElementWhitespace)
                    .transform(Transformation.setTextValue("/" +
                            PomTunerUtils.anyNs("dependency", "version") + "[.." + PomTunerUtils.anyNs("artifactId")
                            + "/text() = 'camel-buildtools']",
                            "${camel-community.version}"));
        });

        if (isChecking()) {
            final MavenSourceTree finalTree = MavenSourceTree.of(rootPomPath, charset, Dependency::isVirtual);
            assertPomsMatch(workRoot, basePath, finalTree.getModulesByPath().keySet());
        }

    }

    void updateVersions(MavenSourceTree fullTree, Predicate<Profile> profiles) {
        /* Check that all modules have the same version - another version may have slipped in when backporting */
        final Module rootModule = fullTree.getRootModule();
        final String expectedVersion = rootModule.getGav().getVersion().asConstant();
        for (Module module : fullTree.getModulesByGa().values()) {
            if (!module.getPomPath().equals("pom.xml")) {
                final String moduleVersion = module.getParentGav().getVersion().asConstant();
                if (!expectedVersion.equals(moduleVersion)) {
                    final Path pomPath = fullTree.getRootDirectory().resolve(module.getPomPath());
                    new PomTransformer(pomPath, charset, simpleElementWhitespace)
                            .transform(
                                    (Document document, TransformationContext context) -> {
                                        context
                                                .getContainerElement("project", "parent")
                                                .ifPresent(
                                                        parent -> parent.addOrSetChildTextElement("version", expectedVersion));
                                    });
                }
            }
        }
    }

    private Path copyPoms(Path src, Path dest) {
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

    void assertPomsMatch(Path src, Path dest, Set<String> activeRelativePomPaths) {
        visitPoms(src, file -> {
            final Path relPomPath = src.relativize(file);
            if (activeRelativePomPaths.contains(PomTunerUtils.toUnixPath(relPomPath.toString()))) {
                final Path destPath = dest.resolve(relPomPath);
                try {
                    Assertions.assertThat(file).hasSameTextualContentAs(destPath);
                } catch (AssertionError e) {
                    String msg = e.getMessage();
                    final String contentAt = "content at line";
                    int offset = msg.indexOf(contentAt);
                    if (offset < 0) {
                        throw new IllegalStateException(
                                "Expected to find '" + contentAt + "' in the causing exception's message; found: "
                                        + e.getMessage(),
                                e);
                    }
                    while (msg.charAt(--offset) != '\n') {
                    }
                    msg = "File [" + basedir.toPath().relativize(destPath) + "] is not in sync with "
                            + requiredProductizedCamelArtifacts + ":\n\n"
                            + msg.substring(offset)
                            + "\n\n Consider running mvn org.l2x6.cq:cq-prod-maven-plugin:prod-excludes -N\n\n";
                    throw new RuntimeException(msg);
                }
            }
        });
    }

    void visitPoms(Path src, Consumer<Path> pomConsumer) {
        Set<Path> paths = new TreeSet<>();
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
                        paths.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new RuntimeException("Could not visit pom.xml files under " + src, e);
        }
        paths.stream()
                .forEach(pomConsumer);
    }

}
