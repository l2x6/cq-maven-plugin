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
package org.l2x6.cq.camel.maven.prod;

import com.google.common.base.Objects;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.apache.camel.maven.packaging.ComponentDslMojo;
import org.apache.commons.io.IOUtils;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.repository.RemoteRepository;
import org.l2x6.cq.common.CqCommonUtils;
import org.l2x6.cq.common.OnFailure;
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

    interface AnyVersionStyle {
        String getExpectedVersion(String literalVersion);
    }

    enum CamelVersionStyle implements AnyVersionStyle {
        PROJECT_VERSION {
            @Override
            public String getExpectedVersion(String literalVersion) {
                return "${project.version}";
            }
        },
        LITERAL {
            @Override
            public String getExpectedVersion(String literalVersion) {
                return literalVersion;
            }
        },
        NONE {
            @Override
            public String getExpectedVersion(String literalVersion) {
                return null;
            }
        };
    }

    enum CamelCommunityVersionStyle implements AnyVersionStyle {
        CAMEL_COMMUNITY_VERSION {
            @Override
            public String getExpectedVersion(String literalVersion) {
                return "${camel-community-version}";
            }
        },
        LITERAL {
            @Override
            public String getExpectedVersion(String literalVersion) {
                return literalVersion;
            }
        },
        NONE {
            @Override
            public String getExpectedVersion(String literalVersion) {
                return null;
            }
        };
    }

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
     * Skip the execution of the whole mojo.
     *
     * @since 2.11.0
     */
    @Parameter(property = "cq.camel-prod-excludes.skip", defaultValue = "false")
    boolean skip;

    /**
     * What should happen when the checks performed by this plugin fail. Possible values: {@code WARN}, {@code FAIL},
     * {@code IGNORE}.
     *
     * @since 2.13.0
     */
    @Parameter(property = "cq.onCheckFailure", defaultValue = "FAIL")
    OnFailure onCheckFailure;

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
     * <p>
     * Since 2.31.0 the elements can optionally prepended with a {@code groupId}.
     * Elements without {@code groupId} are interpreted as having implicit {@code groupId} {@code org.apache.camel}
     * <p>
     * Example:
     *
     * <pre>
     * {@code
     * <additionalProductizedArtifactIds>
     *     <additionalProductizedArtifactId>camel-foo</additionalProductizedArtifactId>
     *     <additionalProductizedArtifactId>org.bar:bar-baz</additionalProductizedArtifactId>
     * </addExclusion>
     * }
     * </pre>
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

    /**
     * @since 2.11.0
     */
    @Parameter(property = "cq.camelCommunityVersion", defaultValue = "${camel-community-version}")
    String camelCommunityVersion;

    Map<String, VersionStyle> versionStylesByPath;

    /**
     * @since 2.11.0
     */
    @Parameter(defaultValue = "${settings.localRepository}", readonly = true)
    String localRepository;

    @Component
    private RepositorySystem repoSystem;

    @Parameter(defaultValue = "${repositorySystemSession}", readonly = true, required = true)
    private RepositorySystemSession repoSession;

    @Parameter(property = "project", required = true, readonly = true)
    protected MavenProject project;
    @Component
    protected MavenProjectHelper projectHelper;

    private Predicate<Path> additionalFiles;

    /**
     * Overridden by {@link CamelProdExcludesCheckMojo}.
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
        additionalFiles = path -> false;

        /* Collect the initial set of includes */
        Set<Ga> includes;
        final Path basePath = basedir.toPath();
        try {
            includes = Files.lines(basePath.resolve(requiredProductizedCamelArtifacts.toPath()), charset)
                    .map(line -> new Ga("org.apache.camel", line.strip()))
                    .collect(Collectors.toCollection(TreeSet::new));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        /* Add the additional ones */
        additionalProductizedArtifactIds.stream()
                .map(artifactId -> (artifactId.contains(":") ? Ga.of(artifactId)
                        : new Ga("org.apache.camel", artifactId)))
                .forEach(includes::add);
        /*
         * Let's edit the pom.xml files out of the real source tree if we are just checking or pom editing is not
         * desired
         */
        final Path workRoot = isChecking()
                ? CqCommonUtils.copyPoms(basePath, basePath.resolve("target/prod-excludes-work"), additionalFiles) : basePath;

        final Path rootPomPath = workRoot.resolve("pom.xml");
        new PomTransformer(rootPomPath, charset, simpleElementWhitespace)
                .transform(Transformation.addOrSetProperty("camel-community-version", camelCommunityVersion));

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
                            "${camel-community-version}"));
        });

        /* Make a copy of the originalFullTree */
        final Path originalFullTreeCopyDir = CqCommonUtils.copyPoms(workRoot, basePath.resolve("target/originalFullTreeCopy"),
                additionalFiles);

        /* Remove non-prod components from camel-allcomponents in the copy */
        /* Find the first existing allcomponents dir */
        Path allComponents = Stream.of(
                "core/camel-allcomponents/pom.xml",
                "catalog/camel-allcomponents/pom.xml" // since Camel 3.17 or so
        )
                .map(originalFullTreeCopyDir::resolve)
                .filter(Files::isRegularFile)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Could not find allcomponents module; searched in core/camel-allcomponents/pom.xml and catalog/camel-allcomponents/pom.xml"));

        /* Remove non-prod components from camel-allcomponents in the copy */
        new PomTransformer(allComponents, charset,
                simpleElementWhitespace)
                .transform(
                        Transformation.removeDependency(true, true, gavtcs -> !includes.contains(gavtcs.toGa())));

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

        versionStylesByPath = new HashMap<>();
        fullTree.getModulesByGa().values().stream()
                .forEach(m -> VersionStyle.autodetect(m, camelCommunityVersion, project.getVersion(), expandedIncludes)
                        .ifPresent(vs -> versionStylesByPath.put(m.getPomPath(), vs)));

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
        handleExcludedTargetDirectories(basePath, fullTree, excludes, profiles);

        updateVersions(fullTree, profiles);

        /* Comment all non-productized modules in the tree */
        fullTree.unlinkModules(expandedIncludes, profiles, charset, simpleElementWhitespace,
                (Set<String> unlinkModules) -> Transformation.commentModules(unlinkModules, MODULE_COMMENT));

        /* Replace ${project.version} with ${camel-community-version} where necessary */
        final MavenSourceTree reducedTree = MavenSourceTree.of(rootPomPath, charset, Dependency::isVirtual);
        reducedTree.getModulesByGa().values().forEach(module -> {
            final List<Transformation> transformations = new ArrayList<>();

            for (Profile profile : module.getProfiles()) {
                if (!profile.getDependencies().isEmpty()) {
                    profile.getDependencies().stream()
                            .filter(dep -> "org.apache.camel".equals(dep.getGroupId().asConstant()) && dep.getVersion() != null)
                            .forEach(dep -> {
                                final Ga ga = new Ga(dep.getGroupId().asConstant(), dep.getArtifactId().asConstant());
                                final VersionStyle vs = versionStylesByPath.get(module.getPomPath());
                                vs.getTransformation(false, excludes.contains(ga), profile.getId(), ga,
                                        dep.getVersion().getRawExpression())
                                        .ifPresent(transformations::add);
                            });
                }

                if (!profile.getDependencyManagement().isEmpty()) {
                    profile.getDependencyManagement().stream()
                            .filter(dep -> "org.apache.camel".equals(dep.getGroupId().asConstant())
                                    && !"camel-bom".equals(dep.getArtifactId().asConstant()))
                            .forEach(dep -> {
                                final Ga ga = new Ga(dep.getGroupId().asConstant(), dep.getArtifactId().asConstant());
                                final VersionStyle vs = versionStylesByPath.get(module.getPomPath());
                                vs.getTransformation(true, excludes.contains(ga), profile.getId(), ga,
                                        dep.getVersion().getRawExpression())
                                        .ifPresent(transformations::add);
                            });
                }
            }
            if (!transformations.isEmpty()) {
                new PomTransformer(workRoot.resolve(module.getPomPath()), charset, simpleElementWhitespace)
                        .transform(transformations);
            }
        });

        Stream.of("parent/pom.xml").forEach(relPath -> {
            new PomTransformer(workRoot.resolve(relPath), charset, simpleElementWhitespace)
                    .transform(Transformation.setTextValue("/" +
                            PomTunerUtils.anyNs("dependency", "version") + "[.." + PomTunerUtils.anyNs("artifactId")
                            + "/text() = 'camel-buildtools']",
                            "${camel-community-version}"));
        });

        if (isChecking() && onCheckFailure != OnFailure.IGNORE) {
            final MavenSourceTree finalTree = MavenSourceTree.of(rootPomPath, charset, Dependency::isVirtual);
            CqCommonUtils.assertPomsMatch(
                    workRoot,
                    basePath,
                    finalTree.getModulesByPath().keySet(),
                    additionalFiles,
                    charset,
                    basedir.toPath(),
                    requiredProductizedCamelArtifacts.toPath(),
                    onCheckFailure,
                    getLog()::warn,
                    "org.l2x6.cq:cq-camel-prod-maven-plugin:camel-prod-excludes");
        }

    }

    void handleExcludedTargetDirectories(final Path basePath, final MavenSourceTree fullTree, final Set<Ga> excludes,
            Predicate<Profile> profiles) {
        /* Clean the target folders in all excluded modules so that Camel plugins do not see any stale content there */
        excludes.stream()
                .map(ga -> fullTree.getModulesByGa().get(ga))
                .map(Module::getPomPath)
                .map(basePath::resolve)
                .map(Path::getParent)
                .map(absPath -> absPath.resolve("target"))
                .filter(Files::isDirectory)
                .forEach(CqCommonUtils::deleteDirectory);

        /*
         * Unpack the community jars of excluded components to their target/classes so that Camel plugins find it there
         */
        project.getBasedir();
        excludes.parallelStream()
                .map(ga -> fullTree.getModulesByGa().get(ga))
                .filter(CamelProdExcludesMojo::isComponent)
                .forEach(module -> {
                    final String artifactId = module.getGav().getArtifactId().asConstant();
                    final Path jarPath = CqCommonUtils.resolveArtifact(Paths.get(localRepository), "org.apache.camel",
                            artifactId,
                            camelCommunityVersion, "jar", repositories, repoSystem, repoSession);
                    final Path pomFilePath = basePath.resolve(module.getPomPath());
                    final Path moduleBaseDir = pomFilePath.getParent();
                    final File outputDir = moduleBaseDir.resolve("target/classes").toFile();
                    try (ZipFile zipFile = new ZipFile(jarPath.toFile())) {
                        final Enumeration<? extends ZipEntry> entries = zipFile.entries();
                        while (entries.hasMoreElements()) {
                            final ZipEntry entry = entries.nextElement();
                            final File entryDestination = new File(outputDir, entry.getName());
                            if (entry.isDirectory()) {
                                entryDestination.mkdirs();
                            } else {
                                entryDestination.getParentFile().mkdirs();
                                try (InputStream in = zipFile.getInputStream(entry);
                                        OutputStream out = new FileOutputStream(entryDestination)) {
                                    IOUtils.copy(in, out);
                                }
                            }
                        }
                    } catch (IOException e) {
                        throw new RuntimeException("Could not extract " + jarPath + " to " + outputDir);
                    }

                    /* Execute ComponentDslMojo in the excluded component modules */
                    getLog().info("Executing ComponentDslMojo in " + moduleBaseDir);
                    final MavenProject projectCopy = new MavenProject(project);
                    projectCopy.setFile(pomFilePath.toFile());
                    projectCopy.getBuild().setDirectory(moduleBaseDir.resolve("target").toString());
                    try {
                        ComponentDslMojo mojo = new ComponentDslMojo();
                        mojo.setLog(getLog());
                        mojo.setPluginContext(getPluginContext());
                        mojo.execute(projectCopy, projectHelper, null);

                    } catch (Exception e) {
                        throw new RuntimeException("Could not excute ComponentDslMojo in " + moduleBaseDir, e);
                    }

                });

    }

    static boolean isComponent(Module module) {
        return "jar".equals(module.getPackaging())
                && (module.getPomPath().startsWith("components/") || module.getPomPath().startsWith("core/"));
    }

    void updateVersions(MavenSourceTree fullTree, Predicate<Profile> profiles) {
        /* Check that all modules have the same version - another version may have slipped in when backporting */
        final Module rootModule = fullTree.getRootModule();
        final String expectedVersion = rootModule.getGav().getVersion().asConstant();
        for (Module module : fullTree.getModulesByGa().values()) {
            if (!module.getPomPath().equals("pom.xml")) {
                final String moduleVersion = module.getParentGav().getVersion().asConstant();
                final String parentGroupId = module.getParentGav().getGroupId().asConstant();
                final String moduleGroupId = module.getGav().getGroupId().asConstant();
                if (!expectedVersion.equals(moduleVersion) && parentGroupId.equals(moduleGroupId)) {
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

    public static class VersionStyle {

        static Optional<VersionStyle> autodetect(Module module, String camelCommunityVersion, String camelVersion,
                Set<Ga> includes) {
            if ("bom/camel-bom/pom.xml".equals(module.getPomPath())) {
                return Optional.of(new VersionStyle(camelCommunityVersion, camelVersion, CamelVersionStyle.PROJECT_VERSION,
                        CamelCommunityVersionStyle.LITERAL));
            }

            final boolean importsCamelBom = module.getProfiles().get(0).getDependencyManagement().stream()
                    .anyMatch(dep -> "org.apache.camel".equals(dep.getGroupId().asConstant())
                            && "camel-bom".equals(dep.getArtifactId().asConstant()));

            final Optional<String> firstManagedCamelArtifact = module.getProfiles().get(0).getDependencyManagement().stream()
                    .filter(dep -> "org.apache.camel".equals(dep.getGroupId().asConstant())
                            && !"camel-bom".equals(dep.getArtifactId().asConstant()))
                    .map(dep -> dep.getVersion().getRawExpression())
                    .findFirst();

            if (importsCamelBom) {
                return Optional.of(new VersionStyle(camelCommunityVersion, camelVersion, CamelVersionStyle.NONE,
                        CamelCommunityVersionStyle.NONE));
            } else if (firstManagedCamelArtifact.isPresent()) {
                if (firstManagedCamelArtifact.get().startsWith("$")) {
                    return Optional.of(new VersionStyle(camelCommunityVersion, camelVersion, CamelVersionStyle.PROJECT_VERSION,
                            CamelCommunityVersionStyle.CAMEL_COMMUNITY_VERSION));
                } else {
                    return Optional.of(new VersionStyle(camelCommunityVersion, camelVersion, CamelVersionStyle.LITERAL,
                            CamelCommunityVersionStyle.LITERAL));
                }
            } else {
                final Optional<String> firstCamelDependency = module
                        .getProfiles()
                        .get(0)
                        .getDependencies()
                        .stream()
                        .filter(dep -> "org.apache.camel".equals(dep.getGroupId().getRawExpression())
                                && dep.getVersion() != null)
                        .map(dep -> dep.getVersion().getRawExpression())
                        .findFirst();
                if (firstCamelDependency.isPresent()) {
                    if (firstCamelDependency.get().startsWith("$")) {
                        return Optional
                                .of(new VersionStyle(camelCommunityVersion, camelVersion, CamelVersionStyle.PROJECT_VERSION,
                                        CamelCommunityVersionStyle.CAMEL_COMMUNITY_VERSION));
                    } else {
                        return Optional.of(new VersionStyle(camelCommunityVersion, camelVersion, CamelVersionStyle.LITERAL,
                                CamelCommunityVersionStyle.LITERAL));
                    }
                } else {
                    return Optional.empty();
                }
            }
        }

        private final String camelCommunityVersion;
        private final String camelVersion;
        private final CamelVersionStyle camelVersionStyle;
        private final CamelCommunityVersionStyle camelCommunityVersionStyle;

        public VersionStyle(String camelCommunityVersion, String camelVersion, CamelVersionStyle camelVersionStyle,
                CamelCommunityVersionStyle camelCommunityVersionStyle) {
            this.camelCommunityVersion = camelCommunityVersion;
            this.camelVersion = camelVersion;
            this.camelVersionStyle = camelVersionStyle;
            this.camelCommunityVersionStyle = camelCommunityVersionStyle;
        }

        public CamelVersionStyle getCamelVersionStyle() {
            return camelVersionStyle;
        }

        public CamelCommunityVersionStyle getCamelCommunityVersionStyle() {
            return camelCommunityVersionStyle;
        }

        public Optional<Transformation> getTransformation(
                boolean isManagement,
                boolean isCommunity,
                String profileId,
                Ga ga,
                String actualVersion) {

            final String literalVersion = isCommunity ? camelCommunityVersion : camelVersion;
            final String expectedVersion = isCommunity ? camelCommunityVersionStyle.getExpectedVersion(literalVersion)
                    : camelVersionStyle.getExpectedVersion(literalVersion);

            if (Objects.equal(actualVersion, expectedVersion)) {
                return Optional.empty();
            }
            return Optional.of(
                    isManagement
                            ? Transformation.setManagedDependencyVersion(profileId, expectedVersion,
                                    Collections.singleton(ga))
                            : Transformation.setDependencyVersion(profileId, expectedVersion,
                                    Collections.singleton(ga)));
        }
    }
}
