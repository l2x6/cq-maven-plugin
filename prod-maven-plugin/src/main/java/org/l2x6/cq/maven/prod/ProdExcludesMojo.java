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

import com.google.gson.Gson;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.maven.artifact.versioning.ComparableVersion;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.lifecycle.internal.MojoDescriptorCreator;
import org.apache.maven.model.Model;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuilder;
import org.apache.maven.project.ProjectBuildingException;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.project.ProjectBuildingResult;
import org.apache.maven.shared.invoker.Invoker;
import org.apache.maven.shared.utils.io.DirectoryScanner;
import org.apache.maven.shared.utils.io.IOUtil;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.repository.RemoteRepository;
import org.l2x6.cq.common.BannedDependencyResource;
import org.l2x6.cq.common.CqCommonUtils;
import org.l2x6.cq.common.FlattenBomTask;
import org.l2x6.cq.common.FlattenBomTask.BomEntryTransformation;
import org.l2x6.cq.common.OnFailure;
import org.l2x6.pom.tuner.ExpressionEvaluator;
import org.l2x6.pom.tuner.MavenSourceTree;
import org.l2x6.pom.tuner.MavenSourceTree.ActiveProfiles;
import org.l2x6.pom.tuner.PomTransformer;
import org.l2x6.pom.tuner.PomTransformer.ContainerElement;
import org.l2x6.pom.tuner.PomTransformer.NodeGavtcs;
import org.l2x6.pom.tuner.PomTransformer.SimpleElementWhitespace;
import org.l2x6.pom.tuner.PomTransformer.Transformation;
import org.l2x6.pom.tuner.PomTransformer.TransformationContext;
import org.l2x6.pom.tuner.PomTunerUtils;
import org.l2x6.pom.tuner.model.Dependency;
import org.l2x6.pom.tuner.model.Expression;
import org.l2x6.pom.tuner.model.Ga;
import org.l2x6.pom.tuner.model.GavSet;
import org.l2x6.pom.tuner.model.GavSet.UnionGavSet.Builder;
import org.l2x6.pom.tuner.model.Gavtcs;
import org.l2x6.pom.tuner.model.Module;
import org.l2x6.pom.tuner.model.Profile;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * Unlink modules that should not be productized from Camel Quarkus source tree based on
 * {@code product/src/main/resources/camel-quarkus-product-source.json}.
 */
@Mojo(name = "prod-excludes", threadSafe = true, requiresProject = false, inheritByDefault = false)
public class ProdExcludesMojo extends AbstractMojo {
    private static final String CQ_PROD_ARTIFACTS_SKIP = "cq.prod-artifacts.skip";

    enum CqEdition {
        PRODUCT(new HashSet<>(
                Arrays.asList("${camel-quarkus.version}", "${project.version}")), "${camel-quarkus.version}"),
        COMMUNITY(Collections
                .singleton("${camel-quarkus-community.version}"), "${camel-quarkus-community.version}");

        CqEdition(Set<String> versionExpressions, String preferredVersionExpression) {
            this.versionExpressions = versionExpressions;
            this.preferredVersionExpression = preferredVersionExpression;
        }

        private final Set<String> versionExpressions;
        private final String preferredVersionExpression;
    }

    public enum CamelEdition {
        PRODUCT("${camel.version}"),
        COMMUNITY("${camel-community.version}");

        CamelEdition(String versionExpression) {
            this.versionExpression = versionExpression;
        }

        private final String versionExpression;

        public String getVersionExpression() {
            return versionExpression;
        }
    }

    public enum QuarkusEdition {
        PRODUCT("${quarkus.version}"),
        COMMUNITY("${quarkus-community.version}");

        QuarkusEdition(String versionExpression) {
            this.versionExpression = versionExpression;
        }

        private final String versionExpression;

        public String getVersionExpression() {
            return versionExpression;
        }
    }

    enum TestCategory {
        /** Covers only productized extensions, has all dependencies productized */
        PURE_PRODUCT("Product", false, true),
        /** Covers one or more productized extensions, is explicitly allowed to depend on community artifacts */
        MIXED_ALLOWED("Mixed Allowed", true, true),
        /** None of the above, covers a mixture of productized and community artifacts, JVM only */
        MIXED_JVM("Mixed JVM", true, false),
        /** None of the above, covers a mixture of productized and community artifacts, native */
        MIXED_NATIVE("Mixed Native", true, true);

        private final String humanName;
        private final boolean mixed;
        private final boolean isNative;

        private TestCategory(String name, boolean mixed, boolean isNative) {
            this.humanName = name;
            this.mixed = mixed;
            this.isNative = isNative;
        }

        TestCategory upgradeFrom(TestCategory oldValue) {
            if (oldValue == null || ordinal() <= oldValue.ordinal()) {
                return this;
            }
            throw new IllegalStateException("Cannot upgrade from " + oldValue + " to " + this);
        }

        public boolean isMixed() {
            return mixed;
        }

        public String getKey() {
            return humanName.toLowerCase(Locale.ROOT).replace(' ', '-');
        }

        public Path resolveMixedModulePath(Path treeRootDir) {
            return treeRootDir.resolve("product/integration-tests-" + getKey() + "/pom.xml");
        }

        public String getHumanName() {
            return humanName;
        }

        public boolean isNative() {
            return isNative;
        }

    }

    enum ModeSupportStatus {
        community, techPreview, supported, devSupport;

        public boolean hasProductDocumentationPage() {
            switch (this) {
            case devSupport:
            case techPreview:
            case supported:
                return true;
            case community:
                return false;
            default:
                throw new IllegalStateException("Unexpected " + ModeSupportStatus.class.getSimpleName() + "." + name());
            }
        }
    }

    public static final String CAMEL_QUARKUS_PRODUCT_SOURCE_JSON_PATH = "product/src/main/resources/camel-quarkus-product-source.json";
    static final String MODULE_COMMENT = "disabled by cq-prod-maven-plugin:prod-excludes";
    static final String DEFAULT_REQUIRED_PRODUCTIZED_CAMEL_ARTIFACTS_TXT = "target/required-productized-camel-artifacts.txt";
    static final String DEFAULT_PRODUCTIZED_CAMEL_QUARKUS_ARTIFACTS_TXT = "target/productized-camel-quarkus-artifacts.txt";
    static final String DEFAULT_AVAILABLE_CI_NODES = "10";
    static final String DEFAULT_JENKINSFILE = "Jenkinsfile.redhat";
    static final String DEFAULT_JENKINSFILE_STAGE_TEMPLATE = "product/jenkinsfile-stage-template.txt";
    static final Pattern JVM_PARENT_MODULE_PATH_PATTERN = Pattern.compile("extensions-jvm/[^/]+/pom.xml");
    static final Pattern JENKINSFILE_PATTERN = Pattern
            .compile("(\\Q// %generated-stages-start%\n\\E)(.*)(\\Q// %generated-stages-end%\\E)", Pattern.DOTALL);

    static final Ga IO_QUARKUS_QUARKUS_BOM = new Ga("io.quarkus", "quarkus-bom");
    static final Ga IO_QUARKUS_QUARKUS_BOM_TEST = new Ga("io.quarkus", "quarkus-bom-test");

    static final String communityGuideUrlTemplate = "https://camel.apache.org/camel-quarkus/latest/reference/extensions/${artifactIdBase}.html";
    static final String defaultCommunityGuide = "https://camel.apache.org/camel-quarkus/latest/user-guide/index.html";
    static final String DEFAULT_PRODUCTIZED_DEPENDENCIES_FILE = "product/src/main/generated/transitive-dependencies-productized.txt";
    static final String DEFAULT_ALL_DEPENDENCIES_FILE = "product/src/main/generated/transitive-dependencies-all.txt";
    static final String DEFAULT_NON_PRODUCTIZED_DEPENDENCIES_FILE = "product/src/main/generated/transitive-dependencies-non-productized.txt";

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
    @Parameter(property = CQ_PROD_ARTIFACTS_SKIP, defaultValue = "false")
    boolean skip;

    /**
     * How to format simple XML elements ({@code <elem/>}) - with or without space before the slash.
     *
     * @since 1.1.0
     */
    @Parameter(property = "cq.simpleElementWhitespace", defaultValue = "SPACE")
    SimpleElementWhitespace simpleElementWhitespace;

    /**
     * @since 2.6.0
     */
    @Parameter(defaultValue = "${project.remoteProjectRepositories}", readonly = true, required = true)
    List<RemoteRepository> repositories;

    /**
     * @since 2.6.0
     */
    @Parameter(defaultValue = "${settings.localRepository}", readonly = true)
    String localRepository;
    Path localRepositoryPath;

    /**
     * @since 2.6.0
     */
    @Parameter(defaultValue = "${camel.version}")
    String camelVersion;

    /**
     * The current project's version
     *
     * @since 2.19.0
     */
    @Parameter(defaultValue = "${project.version}", readonly = true)
    String version;

    /**
     * What should happen when the checks performed by this plugin fail. Possible values: {@code WARN}, {@code FAIL},
     * {@code IGNORE}.
     *
     * @since 2.14.0
     */
    @Parameter(property = "cq.onCheckFailure", defaultValue = "FAIL")
    OnFailure onCheckFailure;

    /**
     * The root directory of the Camel Quarkus source tree.
     *
     * @since 2.30.0
     */
    @Parameter(defaultValue = "${maven.multiModuleProjectDirectory}", readonly = true)
    File multiModuleProjectDirectory;

    @Parameter(defaultValue = "${plugin}", readonly = true)
    private PluginDescriptor pluginDescriptor;

    @Component
    private RepositorySystem repoSystem;

    @Parameter(defaultValue = "${repositorySystemSession}", readonly = true, required = true)
    private RepositorySystemSession repoSession;

    @Component
    private MojoDescriptorCreator mojoDescriptorCreator;
    @Component
    private Invoker invoker;
    @Component
    private ProjectBuilder mavenProjectBuilder;

    @Parameter(defaultValue = "${project}", readonly = true)
    MavenProject project;
    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    protected MavenSession session;
    private Predicate<Path> additionalFiles;

    /**
     * The Camel Quarkus community version
     *
     * @since 2.17.0
     */
    @Parameter(property = "camel-quarkus-community.version")
    String camelQuarkusCommunityVersion;

    /**
     * Where to write a list of dependency paths pulling {@code javax.*} packages.
     *
     * @since 3.3.0
     */
    @Parameter(property = "cq.jakartaReport")
    File jakartaReport;

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
        localRepositoryPath = Paths.get(localRepository);

        final String majorVersion = version.split("\\.")[0];
        final Path docReferenceDir = basedir.toPath().resolve("docs/modules/ROOT/pages/reference/extensions");

        final Path absProdJson = basedir.toPath().resolve(productJson.toPath());
        final Product product = Product.read(absProdJson, charset, majorVersion, docReferenceDir,
                multiModuleProjectDirectory.toPath());

        final Path jenkinsfileName = product.getJenkinsfile().getFileName();
        final Path basePath = basedir.toPath();
        final Path productizedDependenciesPath = basePath.relativize(product.getProductizedDependenciesFile());
        final Path nonProductizedDependenciesPath = basePath.relativize(product.getNonProductizedDependenciesFile());
        final Path allDependenciesPath = basePath.relativize(product.getAllDependenciesFile());

        final Path extensionYamlRelPath = Paths.get("src/main/resources/META-INF/quarkus-extension.yaml");
        additionalFiles = path -> jenkinsfileName.equals(path.getFileName())
                || path.endsWith(extensionYamlRelPath)
                || path.endsWith(allDependenciesPath)
                || path.endsWith(nonProductizedDependenciesPath)
                || path.endsWith(productizedDependenciesPath);

        /*
         * Let's edit the copies of pom.xml files outside of the real source tree, if we are just checking or pom
         * editing is not desired
         */
        final Path workRoot = isChecking()
                ? CqCommonUtils.copyPoms(
                        basedir.toPath(),
                        basedir.toPath().resolve("target/prod-excludes-work"),
                        additionalFiles)
                : basedir.toPath();

        new PomTransformer(workRoot.resolve("product/pom.xml"), charset, simpleElementWhitespace)
                .transform(Transformation.removeAllModules(null, true, true));
        for (TestCategory testCategory : TestCategory.values()) {
            final Path mixedModulePath = testCategory.resolveMixedModulePath(workRoot).getParent();
            CqCommonUtils.deleteDirectory(mixedModulePath);
        }
        final Path catalogPomPath = workRoot.resolve("catalog/pom.xml");
        /* Remove all virtual deps from the Catalog */
        new PomTransformer(catalogPomPath, charset, simpleElementWhitespace)
                .transform(Transformation.removeDependencies(
                        null,
                        false,
                        true,
                        gavtcs -> gavtcs.isVirtual()));

        final Path rootPomPath = workRoot.resolve("pom.xml");
        final MavenSourceTree initialTree = MavenSourceTree.of(rootPomPath, charset, Dependency::isVirtual);
        final Predicate<Profile> profiles = ActiveProfiles.of();

        /* Re-link any previously commented modules */
        final MavenSourceTree fullTree = initialTree.relinkModules(charset, simpleElementWhitespace, MODULE_COMMENT);

        /* Add the modules required by the includes */
        final Set<Ga> expandedIncludesWithoutTests = Collections
                .unmodifiableSet(fullTree.findRequiredModules(product.getInitialProductizedModules(), profiles));

        updateVersions(fullTree, profiles, product.versionTransformations);

        /* Tests */
        final Map<Ga, Map<Ga, Set<Ga>>> uncoveredExtensions = new TreeMap<>();
        final Map<Ga, TestCategory> tests = analyzeTests(fullTree, expandedIncludesWithoutTests, profiles, uncoveredExtensions,
                product.getAllowedMixedTests(), product.getExcludeTests(), product.getIntegrationTests());

        /* Add the found product tests to the includes */
        final Set<Ga> tempExpandedIncludesWithTests = new TreeSet<>(expandedIncludesWithoutTests);
        tests.entrySet().stream()
                .filter(en -> !en.getValue().mixed)
                .map(Entry::getKey)
                .forEach(tempExpandedIncludesWithTests::add);
        /* The tests may require some additional modules */
        final Set<Ga> expandedIncludesWithProdTests = fullTree.findRequiredModules(tempExpandedIncludesWithTests, profiles);

        final Set<Ga> requiredCamelArtifacts = findRequiredCamelArtifacts(fullTree, expandedIncludesWithProdTests,
                fullTree.getExpressionEvaluator(profiles));
        writeProdReports(fullTree, expandedIncludesWithProdTests, profiles, requiredCamelArtifacts, product);

        /* Comment all non-productized modules in the tree */
        minimizeTree(workRoot, expandedIncludesWithProdTests, tests, profiles);

        /* Fix the virtual deps in the Catalog */
        final Set<Gavtcs> allVirtualExtensions = product.getProductExtensions().keySet().stream()
                .map(ga -> new Gavtcs(ga.getGroupId(), ga.getArtifactId(), null))
                .map(gavtcs -> gavtcs.toVirtual())
                .collect(Collectors.toSet());
        CqCommonUtils.updateVirtualDependencies(charset, simpleElementWhitespace, allVirtualExtensions, catalogPomPath);

        /* Enable the mixed tests in special modules */
        final TreeSet<Ga> expandedIncludesWithAllTests = updateMixedTests(fullTree, expandedIncludesWithProdTests, tests,
                product);

        /* BOMs */
        final Set<Ga> missingCamelArtifacts = updateBoms(fullTree, expandedIncludesWithAllTests, profiles,
                requiredCamelArtifacts);

        updateSuperApp(workRoot, product.getProductExtensions().keySet(),
                fullTree.getRootModule().getGav().getVersion().asConstant());

        /* Uncomment the product module and comment test modules */
        new PomTransformer(workRoot.resolve("pom.xml"), charset, simpleElementWhitespace)
                .transform(
                        Transformation.uncommentModules(MODULE_COMMENT, m -> m.equals("product")));

        /* Product guide links */
        updateProductGuideLinks(workRoot, product, fullTree, extensionYamlRelPath);

        /* Make sure all excludeTests are excluded from the config in tooling/test-list/pom.xml */
        excludeTestsFromTestList(workRoot, fullTree, workRoot.resolve("tooling/test-list/pom.xml"),
                workRoot.resolve("integration-tests"), product.getExcludeTests());

        /* Invoke transitive-deps mojo */
        invokeTransitiveDependenciesMojo(
                basedir.toPath(),
                workRoot,
                product,
                productizedDependenciesPath,
                nonProductizedDependenciesPath,
                allDependenciesPath);

        if (isChecking()) {
            final MavenSourceTree finalTree = MavenSourceTree.of(rootPomPath, charset, Dependency::isVirtual);
            CqCommonUtils.assertPomsMatch(
                    workRoot,
                    basedir.toPath(),
                    finalTree.getModulesByPath().keySet(),
                    additionalFiles,
                    charset,
                    basedir.toPath(),
                    productJson.toPath(),
                    onCheckFailure,
                    getLog()::warn,
                    "org.l2x6.cq:cq-prod-maven-plugin:prod-excludes");
        }

        if (!missingCamelArtifacts.isEmpty()) {
            throw new IllegalStateException(
                    "The following Camel artifacts are not managed in in org.apache.camel:camel-bom:" + camelVersion
                            + " but are required by extensions declared in "
                            + CAMEL_QUARKUS_PRODUCT_SOURCE_JSON_PATH
                            + ":\n - "
                            + missingCamelArtifacts.stream()
                                    .map(Ga::getArtifactId)
                                    .collect(Collectors.joining("\n - ")));
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

            sb.append(".\n\nConsider adding allowedMixedTests to the respective extension entries in ")
                    .append(CAMEL_QUARKUS_PRODUCT_SOURCE_JSON_PATH)
                    .append("\nand running mvn org.l2x6.cq:cq-prod-maven-plugin:prod-excludes -N after that\n\n");
            throw new MojoFailureException(sb.toString());
        }

    }

    void excludeTestsFromTestList(Path workRoot, MavenSourceTree fullTree, Path testListPomPath, Path integrationTestsDir,
            Set<Ga> excludeTests) {
        new PomTransformer(testListPomPath, charset, simpleElementWhitespace).transform(
                (Document document, TransformationContext context) -> {
                    final NodeGavtcs rpkgtestsPluginElement = context.getContainerElement("project", "build", "plugins").get()
                            .childElementsStream()
                            .map(ContainerElement::asGavtcs)
                            .filter(gav -> "org.l2x6.rpkgtests".equals(gav.getGroupId())
                                    && "rpkgtests-maven-plugin".equals(gav.getArtifactId()))
                            .findFirst()
                            .orElseThrow(() -> new IllegalStateException(
                                    "Could not find org.l2x6.rpkgtests:rpkgtests-maven-plugin in " + testListPomPath));
                    final Set<String> excludesToAdd = excludeTests.stream()
                            .map(ga -> workRoot.resolve(fullTree.getModulesByGa().get(ga).getPomPath()))
                            .map(pomPath -> integrationTestsDir.relativize(pomPath).toString().replace(File.separatorChar, '/'))
                            .collect(Collectors.toCollection(TreeSet::new));
                    final ContainerElement excludesElement = rpkgtestsPluginElement.getNode()
                            .getChildContainerElement("configuration", "fileSets", "fileSet", "excludes").get();
                    excludesElement
                            .childElementsStream()
                            .map(child -> child.getNode().getTextContent())
                            .forEach(excludesToAdd::remove);
                    if (!excludesToAdd.isEmpty()) {
                        excludesToAdd.forEach(path -> excludesElement.addChildTextElement("exclude", path));
                    }
                });
    }

    void updateProductGuideLinks(
            Path workRoot,
            Product productDefinition,
            MavenSourceTree fullTree,
            Path extensionYamlRelPath) {
        final Pattern guidePattern = Pattern.compile("guide: \"([^\"]*)\"");
        for (Entry<Ga, Module> en : fullTree.getModulesByGa().entrySet()) {
            final Ga ga = en.getKey();
            final Module module = en.getValue();
            final Path moduleDir = workRoot.resolve(module.getPomPath()).getParent();
            final Path extensionYaml = moduleDir.resolve(extensionYamlRelPath);
            if (Files.isRegularFile(extensionYaml)) {
                try {
                    final String src = new String(Files.readAllBytes(extensionYaml), charset);
                    final Matcher m = guidePattern.matcher(src);
                    if (m.find()) {
                        final String oldUrl = m.group(1);
                        final String newUrl = productDefinition.getExtensionDocPageUrl(ga);
                        if (!newUrl.equals(oldUrl)) {
                            final StringBuilder sb = new StringBuilder(src.length());
                            m.appendReplacement(sb, "guide: \"" + newUrl + "\"");
                            m.appendTail(sb);
                            Files.write(extensionYaml, sb.toString().getBytes(charset));
                        }
                    }
                } catch (IOException e) {
                    throw new RuntimeException("Could not read " + extensionYaml, e);
                }
            }
        }
    }

    static String guideUrl(String majorVersion, final Ga ga, final String guideUrlTemplate) {
        return guideUrlTemplate
                .replace("${cqMajorVersion}", majorVersion)
                .replace("${artifactIdBase}", ga.getArtifactId().replace("camel-quarkus-", ""));
    }

    void updateSuperApp(Path workRoot, Set<Ga> requiredExtensions, String version) {

        final Path productPomPath = workRoot.resolve("product/pom.xml");
        new PomTransformer(productPomPath, charset, simpleElementWhitespace)
                .transform(Transformation.addModuleIfNeeded("superapp", String::compareTo));

        final Path pomXmlPath = workRoot.resolve("product/superapp/pom.xml");
        initializeMixedTestsPom(pomXmlPath, "camel-quarkus-build-parent-it", version,
                "../../poms/build-parent-it/pom.xml", "camel-quarkus-superapp",
                "Camel Quarkus :: Superapp");

        new PomTransformer(pomXmlPath, charset, simpleElementWhitespace)
                .transform(
                        Transformation.addOrSetProperty("enforcer.skip", "false"),
                        Transformation.removeDependencies(null, true, true, gavtcs -> true),
                        (Document document, TransformationContext context) -> {
                            final ContainerElement deps = context.getOrAddContainerElements("dependencies");
                            requiredExtensions.forEach(ga -> {
                                deps.addGavtcs(new Gavtcs(ga.getGroupId(), ga.getArtifactId(), null));
                            });
                        });
    }

    void updateVersions(MavenSourceTree fullTree, Predicate<Profile> profiles, Map<String, String> versionTransformations) {
        /* Check that all modules have the same version - another version may have slipped in when backporting */
        final ExpressionEvaluator evaluator = fullTree.getExpressionEvaluator(profiles);
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

        /* Check that <camel-quarkus.version> is the same as project.version */
        for (Entry<String, Module> en : fullTree.getModulesByPath().entrySet()) {
            final String relPath = en.getKey();
            final Module bomModule = en.getValue();
            final Expression cqVersion = bomModule.getProfiles().get(0)
                    .getProperties().get("camel-quarkus.version");
            if (cqVersion != null && cqVersion.isConstant() && !cqVersion.asConstant().equals(expectedVersion)) {
                final Path absPath = fullTree.getRootDirectory().resolve(relPath);
                new PomTransformer(absPath, charset, simpleElementWhitespace)
                        .transform(Transformation.addOrSetProperty("camel-quarkus.version", expectedVersion));
            }
        }

        /* Check that <camel.version> is the same as parent */
        final List<Transformation> transformations = new ArrayList<PomTransformer.Transformation>();
        final String camelParentVersion = rootModule.getParentGav().getVersion().asConstant();
        final String camelVersion = evaluator
                .evaluate(Expression.of("${camel.version}", evaluator.evaluateGa(rootModule.getGav())));
        if (!camelParentVersion.equals(camelVersion)) {
            transformations.add(
                    (Document document, TransformationContext context) -> {
                        context
                                .getContainerElement("project", "parent")
                                .ifPresent(parent -> parent.addOrSetChildTextElement("version", camelVersion));
                    });
        }
        final Path rootPomPath = fullTree.getRootDirectory().resolve(rootModule.getPomPath());
        if (!transformations.isEmpty()) {
            new PomTransformer(rootPomPath, charset, simpleElementWhitespace)
                    .transform(transformations);
        }

        if (mojoDescriptorCreator != null) {
            /* Do not test this */
            CqCommonUtils.syncVersions(rootPomPath, mojoDescriptorCreator, session, project, charset, simpleElementWhitespace,
                    localRepositoryPath,
                    getLog(), versionTransformations, repositories, repoSession, repoSystem);
        }

    }

    void minimizeTree(Path workRoot, Set<Ga> expandedIncludes, Map<Ga, TestCategory> tests, Predicate<Profile> profiles) {
        final Set<String> testParents = new TreeSet<>(
                Arrays.asList("integration-tests", "integration-tests-jvm", "integration-test-groups"));
        final Set<String> testParentArtifactIds = testParents.stream().map(base -> "camel-quarkus-" + base)
                .collect(Collectors.toSet());
        final Path rootPomPath = workRoot.resolve("pom.xml");
        new PomTransformer(rootPomPath, charset, simpleElementWhitespace)
                .transform(Transformation.commentModules(testParents, MODULE_COMMENT));
        final Set<Ga> expandedIncludesWithoutTests = expandedIncludes.stream()
                .filter(ga -> !tests.containsKey(ga) && !testParentArtifactIds.contains(ga.getArtifactId()))
                .collect(Collectors.toCollection(LinkedHashSet<Ga>::new));
        final MavenSourceTree tree = MavenSourceTree.of(rootPomPath, charset);
        tree.unlinkModules(expandedIncludesWithoutTests, profiles, charset, simpleElementWhitespace,
                (Set<String> unlinkModules) -> Transformation.commentModules(unlinkModules, MODULE_COMMENT));
    }

    TreeSet<Ga> updateMixedTests(final MavenSourceTree fullTree, Set<Ga> expandedIncludes, final Map<Ga, TestCategory> tests,
            Product product) {
        /* Count all native tests */
        int nativeTestsCount = (int) tests.entrySet().stream()
                .filter(en -> en.getValue().isNative)
                .count();
        int availableNodes = product.getAvailableCiNodes() - 1;
        int maxTestsPerGroup = (nativeTestsCount / availableNodes) + 1;
        final Map<TestCategory, TestCategoryTests> testGroups = new EnumMap<>(TestCategory.class);
        Stream.of(TestCategory.values())
                .forEach(k -> testGroups.put(k, new TestCategoryTests(fullTree, maxTestsPerGroup, k)));
        tests.entrySet().stream()
                .forEach(en -> testGroups.get(en.getValue()).addTest(en.getKey()));

        final List<TestGroup> groups = testGroups.values().stream()
                .flatMap(cat -> cat.groupTests().stream())
                .collect(Collectors.toList());
        testGroups.values().stream()
                .forEach(TestCategoryTests::write);

        final TreeSet<Ga> includesPlusTests = new TreeSet<>(expandedIncludes);
        tests.entrySet().stream()
                .filter(en -> en.getValue().isMixed())
                .map(Entry::getKey)
                .forEach(includesPlusTests::add);

        updateJenkinsfile(fullTree.getRootDirectory(), groups, product);

        return includesPlusTests;
    }

    @SuppressWarnings("deprecation")
    void updateJenkinsfile(Path workRoot, List<TestGroup> groups, Product product) {
        final String stageTemplate;
        if (!Files.isRegularFile(product.getJenkinsfileStageTemplate())) {
            final Writer out = new StringWriter();
            try (Reader in = new InputStreamReader(
                    ProdExcludesMojo.class.getClassLoader().getResourceAsStream("jenkinsfile-stage-template.txt"),
                    StandardCharsets.UTF_8)) {
                IOUtil.copy(in, out);
            } catch (IOException e) {
                throw new RuntimeException("Could not read classpath:mixed-tests-template-pom.xml", e);
            }
            stageTemplate = out.toString();
        } else {
            try {
                stageTemplate = new String(Files.readAllBytes(product.getJenkinsfileStageTemplate()), charset);
            } catch (IOException e) {
                throw new RuntimeException("Could not read from " + product.getJenkinsfileStageTemplate(), e);
            }
        }

        final String stages = groups.stream()
                .map(g -> stageTemplate
                        .replace("${groupDirectory}", g.getGroupDirectory())
                        .replace("${stageName}", g.getHumanName()))
                .collect(Collectors.joining());

        final Path relJenkinsfile = basedir.toPath().relativize(product.getJenkinsfile());
        final Path absJenkinsfilePath = workRoot.resolve(relJenkinsfile);
        String content;
        try {
            content = new String(Files.readAllBytes(absJenkinsfilePath), charset);
        } catch (IOException e) {
            throw new RuntimeException("Could not read " + absJenkinsfilePath);
        }

        content = JENKINSFILE_PATTERN.matcher(content)
                .replaceFirst("$1" + Matcher.quoteReplacement(stages + "                ") + "$3");
        try {
            Files.write(absJenkinsfilePath, content.getBytes(charset));
        } catch (IOException e) {
            throw new RuntimeException("Could not write to " + absJenkinsfilePath);
        }
    }

    void removeAllModules(final Path pomXml) {
        new PomTransformer(pomXml, charset, simpleElementWhitespace)
                .transform(Transformation.removeAllModules("mixed", true, true));
    }

    Set<Ga> updateBoms(MavenSourceTree tree, Set<Ga> expandedIncludes, Predicate<Profile> profiles,
            Set<Ga> requiredCamelArtifacts) {
        final ExpressionEvaluator evaluator = tree.getExpressionEvaluator(profiles);

        final Set<Ga> productizedCamelArtifacts = getProductizedCamelArtifacts(tree.getRootModule(), evaluator);
        final Set<Ga> missingCamelArtifacts = requiredCamelArtifacts.stream()
                .filter(ga -> !productizedCamelArtifacts.contains(ga) && !ga.getArtifactId().startsWith("camel-dependencies"))
                .collect(Collectors.toCollection(TreeSet::new));

        for (Entry<Ga, Module> moduleEntry : tree.getModulesByGa().entrySet()) {
            final Ga moduleGa = moduleEntry.getKey();
            if (expandedIncludes.contains(moduleGa)) {
                /* No need to edit the excluded modules */
                final Module module = moduleEntry.getValue();
                final List<Transformation> transformations = new ArrayList<>();
                for (Profile profile : module.getProfiles()) {

                    if (profiles.test(profile) && !profile.getDependencyManagement().isEmpty()) {
                        final Map<String, List<Ga>> gasByNewVersion = new LinkedHashMap<>();
                        Stream.of(CqEdition.values())
                                .forEach(edition -> gasByNewVersion.put(edition.preferredVersionExpression, new ArrayList<>()));
                        Stream.of(CamelEdition.values())
                                .forEach(edition -> gasByNewVersion.put(edition.versionExpression, new ArrayList<>()));
                        Stream.of(QuarkusEdition.values())
                                .forEach(edition -> gasByNewVersion.put(edition.versionExpression, new ArrayList<>()));
                        for (Dependency managedDep : profile.getDependencyManagement()) {
                            final Ga depGa = evaluator.evaluateGa(managedDep);
                            if (depGa.getGroupId().equals("org.apache.camel.quarkus")) {
                                final String rawExpression = managedDep.getVersion().getRawExpression();
                                final CqEdition edition = expandedIncludes.contains(depGa)
                                        ? CqEdition.PRODUCT
                                        : CqEdition.COMMUNITY;
                                if (!edition.versionExpressions.contains(rawExpression)) {
                                    gasByNewVersion.get(edition.preferredVersionExpression).add(depGa);
                                }
                            } else if (depGa.getGroupId().equals("org.apache.camel")) {
                                final String rawExpression = managedDep.getVersion().getRawExpression();
                                /* Set all to community at this stage and correct it later via transitive-deps mojo */
                                final CamelEdition edition = CamelEdition.COMMUNITY;
                                if (!rawExpression.equals(edition.versionExpression)) {
                                    gasByNewVersion.get(edition.versionExpression).add(depGa);
                                }
                            } else if (depGa.equals(IO_QUARKUS_QUARKUS_BOM_TEST)) {
                                /*
                                 * Handle io.quarkus:quarkus-bom-test as a special case.
                                 * If there turns out to be more cases like this, we may implement some auto-detection
                                 * whether the given Quarkus artifact is productized.
                                 * Note that the current hardcoding is much faster than probing an existence of a
                                 * resource over HTTP
                                 */
                                final String rawExpression = managedDep.getVersion().getRawExpression();
                                final QuarkusEdition edition = QuarkusEdition.COMMUNITY;
                                if (!rawExpression.equals(edition.versionExpression)) {
                                    gasByNewVersion.get(edition.versionExpression).add(depGa);

                                    if (!profile.getDependencyManagement().stream()
                                            .map(evaluator::evaluateGa)
                                            .anyMatch(IO_QUARKUS_QUARKUS_BOM::equals)) {
                                        /*
                                         * Add quarkus-bom productized version before quarkus-bom-test community
                                         * if not already there
                                         */
                                        transformations.add((Document document, TransformationContext context) -> {
                                            final ContainerElement dependencyManagementDeps = context.getOrAddContainerElements(
                                                    "dependencyManagement",
                                                    "dependencies");
                                            final Node refNode = dependencyManagementDeps.childElementsStream()
                                                    .map(ContainerElement::asGavtcs)
                                                    .filter(gavtcs -> gavtcs.toGa().equals(IO_QUARKUS_QUARKUS_BOM_TEST))
                                                    .findFirst()
                                                    .get()
                                                    .getNode()
                                                    .previousSiblingInsertionRefNode();
                                            dependencyManagementDeps.addGavtcs(
                                                    new Gavtcs("io.quarkus", "quarkus-bom", "${quarkus.version}", "pom", null,
                                                            "import"),
                                                    refNode);
                                        });
                                    }
                                }
                            }
                        }
                        gasByNewVersion.entrySet().stream()
                                .filter(en -> !en.getValue().isEmpty())
                                .forEach(en -> transformations
                                        .add(Transformation.setManagedDependencyVersion(profile.getId(),
                                                en.getKey(), en.getValue())));
                    }
                }
                if (!transformations.isEmpty()) {
                    new PomTransformer(tree.getRootDirectory().resolve(module.getPomPath()), charset, simpleElementWhitespace)
                            .transform(transformations);
                }
            }
        }

        return missingCamelArtifacts;
    }

    Set<Ga> getProductizedCamelArtifacts(Module cqRootModule, ExpressionEvaluator evaluator) {

        final Path camelBomPath = CqCommonUtils.resolveArtifact(localRepositoryPath, "org.apache.camel", "camel-bom",
                camelVersion, "pom", repositories, repoSystem, repoSession);
        final Model camelBomModel = CqCommonUtils.readPom(camelBomPath, charset);
        return camelBomModel.getDependencyManagement().getDependencies().stream()
                .filter(dep -> "${project.version}".equals(dep.getVersion()) || camelVersion.equals(dep.getVersion()))
                .map(dep -> new Ga(dep.getGroupId(), dep.getArtifactId()))
                .collect(Collectors.toSet());
    }

    Transformation addMixedProfile(String bomArtifactId) {
        return (Document document, TransformationContext context) -> {
            ContainerElement profile = context.getOrAddContainerElements("profiles").addChildContainerElement("profile");
            profile.addChildTextElement("id", "mixed");
            ContainerElement deps = profile.addChildContainerElement("dependencyManagement")
                    .addChildContainerElement("dependencies");
            deps.addGavtcs(new Gavtcs("org.apache.camel.quarkus", bomArtifactId, "${project.version}", "pom", null, "import"));
        };
    }

    Transformation replaceManagedArtifactId(final String findArtifactId, final String replaceArtifactId) {
        return (Document document, TransformationContext context) -> {
            final NodeGavtcs bom = context.getManagedDependencies().stream()
                    .filter(dep -> findArtifactId.equals(dep.getArtifactId()))
                    .findFirst()
                    .get();
            bom.getNode().childElementsStream()
                    .forEach(child -> {
                        final Element node = child.getNode();
                        if ("artifactId".equals(node.getLocalName())) {
                            node.setTextContent(replaceArtifactId);
                        }
                    });
        };
    }

    String copyPom(MavenSourceTree tree, Module source, Transformation... transformations) {
        final Path sourcePath = tree.getRootDirectory().resolve(source.getPomPath());
        final Path destinationPath = tree.getRootDirectory().resolve(source.getPomPath().replace("poms/", "product/"));
        try {
            Files.createDirectories(destinationPath.getParent());
            Files.copy(sourcePath, destinationPath, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new RuntimeException("Could not copy " + sourcePath + " to " + destinationPath, e);
        }

        List<Transformation> transformationList = new ArrayList<>();
        transformationList.add(Transformation.setParent("camel-quarkus-product", "../pom.xml"));
        transformationList.add((Document document, TransformationContext context) -> {
            ContainerElement project = context.getContainerElement("project").get();
            project.addOrSetChildTextElement("artifactId",
                    source.getGav().getArtifactId().getRawExpression().replace("camel-quarkus-", "camel-quarkus-product-"));
            project.addOrSetChildTextElement("name",
                    source.getName().replace("Camel Quarkus :: ", "Camel Quarkus :: Product :: "));
        });
        Stream.of(transformations).forEach(transformationList::add);

        new PomTransformer(destinationPath, charset, simpleElementWhitespace).transform(transformationList);

        return destinationPath.getParent().getFileName().toString();
    }

    /**
     * @param  tree                the source tree
     * @param  productizedGas      {@link Set} of productized artifacts
     * @param  profiles            the active profiles
     * @param  uncoveredExtensions a map from an extension {@link Ga} to set of missing dependencies
     * @param  allowedMixedTests   a Map from extension {@link Ga} to a set of mixed tests covering it that are allowed
     * @param  integrationTests
     * @return                     a {@link Map} covering all integration tests, from {@link Ga} to {@link TestCategory}
     */
    Map<Ga, TestCategory> analyzeTests(final MavenSourceTree tree, final Set<Ga> productizedGas, Predicate<Profile> profiles,
            Map<Ga, Map<Ga, Set<Ga>>> uncoveredExtensions, Map<Ga, Set<Ga>> allowedMixedTests, Set<Ga> excludeTests,
            List<DirectoryScanner> integrationTests) {
        getLog().debug("Included extensions before considering tests:");
        final Set<Ga> expandedExtensions = CqCommonUtils.filterExtensions(productizedGas.stream())
                .peek(ga -> getLog().debug(" - " + ga.getArtifactId()))
                .collect(Collectors.toCollection(TreeSet::new));

        final Map<Ga, Set<Ga>> testModules = collectIntegrationTests(tree, profiles, excludeTests, getLog(), basedir.toPath(),
                integrationTests);

        final Map<Ga, TestCategory> tests = new TreeMap<>();
        testModules.keySet().stream().forEach(ga -> tests.put(ga, findInitialTestCategory(tree, ga)));

        getLog().info("Test coverage:");
        for (Ga extensionGa : expandedExtensions) {
            final String extensionArtifactId = extensionGa.getArtifactId();
            if (!extensionArtifactId.startsWith("camel-quarkus-support-")) {
                /* Do not analyze the test coverage of the ancillary extensions */
                final Set<Ga> extAllowedMixedTests = allowedMixedTests.getOrDefault(extensionGa, Collections.emptySet());
                boolean covered = false;
                final Map<Ga, Set<Ga>> testsWithMissingDependencies = new TreeMap<>();
                for (Entry<Ga, Set<Ga>> testModule : testModules.entrySet()) {
                    if (testModule.getValue().contains(extensionGa)) {
                        final Ga testGa = testModule.getKey();
                        if (productizedGas.containsAll(testModule.getValue())) {
                            /* This test covers the given extensionGa and all its deps are included */
                            tests.compute(testGa, (k, oldVal) -> TestCategory.PURE_PRODUCT.upgradeFrom(oldVal));
                            covered = true;
                            getLog().info(
                                    " - " + extensionArtifactId + " is covered by "
                                            + testGa.getArtifactId());
                        } else if (extAllowedMixedTests.contains(testGa)) {
                            /* This test is allowed to be mixed for this specific extension */
                            tests.compute(testGa, (k, oldVal) -> TestCategory.MIXED_ALLOWED.upgradeFrom(oldVal));
                            covered = true;
                            getLog().info(
                                    " - " + extensionArtifactId + " is covered by an explicitly allowed mixed test "
                                            + testGa.getArtifactId());
                        } else if (!covered) {
                            /* Store what is missing to be able to report later */
                            final TreeSet<Ga> missingDeps = testModule.getValue().stream()
                                    .filter(ga -> !productizedGas.contains(ga))
                                    .collect(Collectors.toCollection(TreeSet::new));
                            if (testGa.getArtifactId().startsWith(
                                    extensionArtifactId.replace("camel-quarkus-", "camel-quarkus-integration-test-"))) {
                                getLog().warn(
                                        " - " + extensionArtifactId + " cannot be covered by " + testGa.getArtifactId()
                                                + " because of missing dependencies:\n    - "
                                                + missingDeps.stream().map(Ga::getArtifactId)
                                                        .collect(Collectors.joining("\n    - ")));
                            }
                            testsWithMissingDependencies.put(
                                    testGa,
                                    missingDeps);
                        }
                    }
                }
                if (!covered) {
                    uncoveredExtensions.put(extensionGa, testsWithMissingDependencies);
                }
            }
        }
        return tests;
    }

    static TestCategory findInitialTestCategory(MavenSourceTree tree, Ga ga) {
        final Module module = tree.getModulesByGa().get(ga);
        final String pomPath = module.getPomPath();
        if (pomPath.startsWith("integration-tests-jvm/")) {
            return TestCategory.MIXED_JVM;
        } else if (pomPath.startsWith("integration-tests/") || pomPath.startsWith("integration-test-groups/")) {
            return TestCategory.MIXED_NATIVE;
        }
        throw new IllegalStateException("Could not assign a category to test " + pomPath);
    }

    static Map<Ga, Set<Ga>> collectIntegrationTests(final MavenSourceTree tree, Predicate<Profile> profiles,
            Set<Ga> excludeTests, Log log, Path basedir, List<DirectoryScanner> integrationTests) {
        final ExpressionEvaluator evaluator = tree.getExpressionEvaluator(profiles);
        final Map<Ga, Set<Ga>> testModules = new TreeMap<>();
        for (DirectoryScanner scanner : integrationTests) {
            scanner.scan();
            final Path base = scanner.getBasedir().toPath().toAbsolutePath().normalize();
            for (String scannerPath : scanner.getIncludedFiles()) {
                final Path pomXmlPath = base.resolve(scannerPath);
                final String pomXmlRelPath = PomTunerUtils.toUnixPath(basedir.relativize(pomXmlPath).toString());
                final Module testModule = tree.getModulesByPath().get(pomXmlRelPath);
                if (testModule == null) {
                    throw new IllegalStateException("Could not find module for path " + pomXmlRelPath);
                }
                final Ga moduleGa = evaluator.evaluateGa(testModule.getGav());
                if (!excludeTests.contains(moduleGa)) {
                    final Set<Ga> deps = tree.collectTransitiveDependencies(moduleGa, profiles).stream()
                            .map(evaluator::evaluateGa)
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
        }
        log.debug("Found tests:");
        testModules.entrySet().forEach(m -> log.debug(" - " + m.getKey().getArtifactId() + ": " + m.getValue()));
        return testModules;
    }

    void writeProdReports(
            MavenSourceTree tree,
            Set<Ga> expandedIncludes,
            Predicate<Profile> profiles,
            Set<Ga> requiredCamelArtifacts,
            Product product) {
        final Path cqFile = product.getProductizedCamelQuarkusArtifacts();
        try {
            Files.createDirectories(cqFile.getParent());
        } catch (IOException e1) {
            throw new RuntimeException("Could not create " + cqFile.getParent());
        }
        getLog().debug("Required modules:");
        try (Writer out = Files.newBufferedWriter(cqFile, charset)) {
            expandedIncludes.stream()
                    .map(Ga::getArtifactId)
                    .sorted()
                    .peek(artifactId -> getLog().debug(" - " + artifactId))
                    .forEach(artifactId -> {
                        try {
                            out.write(artifactId);
                            out.write('\n');
                        } catch (IOException e) {
                            throw new RuntimeException("Could not write to " + cqFile);
                        }
                    });
        } catch (IOException e) {
            throw new RuntimeException("Could not write to " + cqFile);
        }

        final Path camelFile = product.getRequiredProductizedCamelArtifacts();
        try (Writer out = Files.newBufferedWriter(camelFile, charset)) {
            requiredCamelArtifacts.stream()
                    .map(Ga::getArtifactId)
                    .distinct()
                    .sorted()
                    .forEach(artifactId -> {
                        try {
                            out.write(artifactId);
                            out.write('\n');
                        } catch (IOException e) {
                            throw new RuntimeException("Could not write to " + camelFile);
                        }
                    });
        } catch (IOException e) {
            throw new RuntimeException("Could not write to " + camelFile);
        }
    }

    /**
     * @param realRoot the real root of the source tree
     * @param workRoot the work root, possibly under realRoot/target where we perform non-permanent changes
     */
    void invokeTransitiveDependenciesMojo(
            Path realRoot,
            Path workRoot,
            Product product,
            Path productizedDependenciesPath,
            Path nonProductizedDependenciesPath,
            Path allDependenciesPath) {
        if (invoker == null) {
            /* Do not test this */
            return;
        }

        /* Install the poms so that Maven resolver can find them */
        final Path rootPomPath = workRoot.resolve("pom.xml");
        final MavenSourceTree finalTree = MavenSourceTree.of(rootPomPath, charset, Dependency::isVirtual);
        final Ga bomGa = new Ga("org.apache.camel.quarkus", "camel-quarkus-bom");
        finalTree.getModulesByGa().entrySet().stream().forEach(en -> {
            final Ga ga = en.getKey();
            if (!ga.equals(bomGa)) {
                /* The BOM needs a special handling - see below */
                final String relPath = en.getValue().getPomPath();
                final Path absPath = workRoot.resolve(relPath);
                CqCommonUtils.installArtifact(absPath, localRepositoryPath, ga.getGroupId(), ga.getArtifactId(), version,
                        "pom");
            }
        });
        /* Install the BOM */
        getLog().info("Installing preliminary camel-quarkus-bom with community-only Camel constraints");
        flattenAndInstallBom(product);

        new TransitiveDependenciesMojo(
                version,
                camelQuarkusCommunityVersion,
                workRoot,
                charset,
                workRoot.resolve(productizedDependenciesPath),
                workRoot.resolve(allDependenciesPath),
                workRoot.resolve(nonProductizedDependenciesPath),
                product.getAdditionalExtensionDependencies(),
                simpleElementWhitespace,
                repositories,
                repoSystem,
                repoSession,
                getLog(),
                () -> flattenAndInstallBom(product),
                jakartaReport != null ? jakartaReport.toPath() : null)
                .execute();

    }

    private void flattenAndInstallBom(Product product) {
        final Path rootDir = multiModuleProjectDirectory.toPath().toAbsolutePath().normalize();
        ProjectBuildingRequest pbr = new DefaultProjectBuildingRequest(session.getProjectBuildingRequest());
        pbr.setProcessPlugins(false);
        final String cqBomRelPath = "poms/bom/pom.xml";
        final Path cqBomAbsPath = rootDir.resolve(cqBomRelPath);
        try {
            final ProjectBuildingResult result = mavenProjectBuilder.build(cqBomAbsPath.toFile(), pbr);
            final MavenProject p = result.getProject();
            Xpp3Dom config = (Xpp3Dom) p.getModel().getBuild().getPlugins().stream()
                    .filter(plugin -> plugin.getGroupId().equals("org.l2x6.cq")
                            && plugin.getArtifactId().equals("cq-maven-plugin"))
                    .flatMap(plugin -> plugin.getExecutions().stream())
                    .filter(execution -> execution.getGoals().contains("flatten-bom"))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            cqBomRelPath + " should contain a org.l2x6.cq:cq-maven-plugin:flatten-bom mojo definition"))
                    .getConfiguration();

            final List<BomEntryTransformation> bomEntryTransformations = Stream.of(
                    Optional.ofNullable(config.getChild("bomEntryTransformations"))
                            .flatMap(xpp3Dom -> Optional.ofNullable(xpp3Dom.getChildren()))
                            .orElse(new Xpp3Dom[0]))
                    .map(xpp3Dom -> new BomEntryTransformation(
                            optionalChild(xpp3Dom, "gavPattern").orElse(null),
                            optionalChild(xpp3Dom, "versionReplacement").orElse(null),
                            optionalChild(xpp3Dom, "exclusions").orElse(null),
                            optionalChild(xpp3Dom, "addExclusions").orElse(null)))
                    .collect(Collectors.toList());

            List<String> requiredBomEntryIncludes = childList(config, "requiredBomEntryIncludes");
            if (requiredBomEntryIncludes == null) {
                requiredBomEntryIncludes = List.of("org.apache.camel");
            }

            final Path flattenedBomPath = new FlattenBomTask(
                    childList(config, "resolutionEntryPointIncludes"),
                    childList(config, "resolutionEntryPointExcludes"),
                    childList(config, "resolutionSuspects"),
                    childList(config, "originExcludes"),
                    bomEntryTransformations,
                    requiredBomEntryIncludes,
                    childList(config, "requiredBomEntryExcludes"),
                    onCheckFailure,
                    p,
                    rootDir,
                    optionalChild(config, "fullPomPath").map(Paths::get).orElse(null),
                    optionalChild(config, "reducedVerbosePamPath").map(Paths::get).orElse(null),
                    optionalChild(config, "reducedPomPath").map(Paths::get).orElse(null),
                    charset,
                    getLog(),
                    repositories,
                    repoSystem,
                    repoSession,
                    CqCommonUtils.getProfiles(session), !isChecking(),
                    simpleElementWhitespace,
                    optionalChild(config, "installFlavor").map(FlattenBomTask.InstallFlavor::valueOf)
                            .orElse(FlattenBomTask.InstallFlavor.REDUCED),
                    false,
                    product.getBannedDependencies(),
                    localRepositoryPath)
                    .execute();
            CqCommonUtils.installArtifact(flattenedBomPath, localRepositoryPath, p.getGroupId(), p.getArtifactId(), version,
                    "pom");

        } catch (ProjectBuildingException e) {
            throw new RuntimeException("Could not build effective POM for " + cqBomRelPath, e);
        }

    }

    static java.util.Optional<String> optionalChild(Xpp3Dom config, String childElementName) {
        return Optional.ofNullable(config.getChild(childElementName)).map(Xpp3Dom::getValue);
    }

    static List<String> childList(Xpp3Dom config, String childElementName) {
        final Xpp3Dom child = config.getChild(childElementName);
        if (child == null) {
            return null;
        }
        return Stream.of(child.getChildren()).map(Xpp3Dom::getValue).collect(Collectors.toList());
    }

    public Set<Ga> findRequiredCamelArtifacts(MavenSourceTree tree, Set<Ga> expandedIncludes,
            final ExpressionEvaluator evaluator) {
        final Set<Ga> set = expandedIncludes.stream()
                .map(ga -> tree.getModulesByGa().get(ga))
                .flatMap(module -> module.getProfiles().stream())
                .flatMap(profile -> profile.getDependencies().stream())
                .map(evaluator::evaluateGa)
                .filter(depGa -> "org.apache.camel".equals(depGa.getGroupId()))
                .collect(Collectors.toCollection(TreeSet::new));
        final ComparableVersion comparableCamelVersion = new ComparableVersion(camelVersion);
        final ComparableVersion camel3_14_0 = new ComparableVersion("3.14.0");
        set.add(new Ga(
                "org.apache.camel",
                camel3_14_0.compareTo(comparableCamelVersion) > 0
                        ? "camel-dependencies"
                        : "camel-dependencies-generator"));
        return Collections.unmodifiableSet(set);
    }

    static void initializeMixedTestsPom(Path destinationPath, String parentArtifactId, String version, String parentPath,
            String artifactId, String name) {
        final Writer out = new StringWriter();
        try (Reader in = new InputStreamReader(
                ProdExcludesMojo.class.getClassLoader().getResourceAsStream("mixed-tests-template-pom.xml"),
                StandardCharsets.UTF_8)) {
            IOUtil.copy(in, out);
        } catch (IOException e) {
            throw new RuntimeException("Could not read classpath:mixed-tests-template-pom.xml", e);
        }
        final String template = out.toString();
        final String content = template
                .replace("${parentArtifactId}", parentArtifactId)
                .replace("${version}", version)
                .replace("${parentPath}", parentPath)
                .replace("${artifactId}", artifactId)
                .replace("${name}", name);
        try {
            Files.createDirectories(destinationPath.getParent());
            Files.write(destinationPath, content.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new RuntimeException("Could not write to " + destinationPath);
        }
    }

    /**
     * A representation of data stored in {@code product/src/main/resources/camel-quarkus-product-source.json}.
     */
    static class Product {

        public static Product read(Path absProdJson, Charset charset, String majorVersion, Path docReferenceDir,
                Path multiModuleProjectDirectory) {

            try (Reader r = Files.newBufferedReader(absProdJson, charset)) {
                @SuppressWarnings("unchecked")
                final Map<String, Object> json = new Gson().fromJson(r, Map.class);
                final String prodGuideUrlTemplate = (String) json.get("guideUrlTemplate");
                @SuppressWarnings("unchecked")
                final Map<String, Object> extensions = (Map<String, Object>) json.get("extensions");
                final Map<Ga, Extension> extensionsMap = new TreeMap<>();
                final Set<Ga> excludeTests = new TreeSet<Ga>();
                final Map<Ga, Set<Ga>> allowedMixedTests = new TreeMap<>();
                for (Entry<String, Object> en : extensions.entrySet()) {

                    final String artifactId = en.getKey();
                    final Ga extensionGa = new Ga("org.apache.camel.quarkus", artifactId);
                    Map<String, Object> extensionEntry = (Map<String, Object>) en.getValue();
                    extensionsMap.put(extensionGa,
                            new Extension(extensionGa, ModeSupportStatus.valueOf((String) extensionEntry.get("jvm")),
                                    ModeSupportStatus.valueOf((String) extensionEntry.get("native"))));

                    @SuppressWarnings("unchecked")
                    final List<String> allowedMixedTestsList = (List<String>) extensionEntry.get("allowedMixedTests");
                    if (allowedMixedTestsList != null) {
                        final Set<Ga> moduleAllowedMixedTests = allowedMixedTestsList.stream()
                                .map(a -> new Ga("org.apache.camel.quarkus", a))
                                .collect(Collectors.toCollection(TreeSet::new));
                        allowedMixedTests.put(extensionGa, moduleAllowedMixedTests);
                    }
                }
                @SuppressWarnings("unchecked")
                final List<String> additionalProductizedArtifacts = (List<String>) json
                        .getOrDefault("additionalProductizedArtifacts", Collections.emptyList());
                @SuppressWarnings("unchecked")
                final List<String> excludeTestsList = (List<String>) json.get("excludeTests");
                if (excludeTestsList != null) {
                    for (String artifactId : excludeTestsList) {
                        excludeTests.add(new Ga("org.apache.camel.quarkus", artifactId));
                    }
                }

                @SuppressWarnings("unchecked")
                final Map<String, String> versionTransformations = (Map<String, String>) json.getOrDefault(
                        "versionTransformations",
                        Collections.emptyMap());
                final List<DirectoryScanner> integrationTests = new ArrayList<>();
                final List<Map<String, Object>> itEntries = (List<Map<String, Object>>) json
                        .getOrDefault("integrationTests", Collections.emptyList());
                for (Map<String, Object> itEntry : itEntries) {
                    final DirectoryScanner ds = new DirectoryScanner();
                    ds.setBasedir(multiModuleProjectDirectory.resolve((String) itEntry.getOrDefault("basedir", ".")).normalize()
                            .toFile());
                    ds.setIncludes(
                            ((List<String>) itEntry.getOrDefault("includes", Collections.emptyList())).toArray(new String[0]));
                    ds.setExcludes(
                            ((List<String>) itEntry.getOrDefault("excludes", Collections.emptyList())).toArray(new String[0]));
                    integrationTests.add(ds);
                }

                final Path requiredProductizedCamelArtifacts = multiModuleProjectDirectory.resolve((String) json
                        .getOrDefault("requiredProductizedCamelArtifacts", DEFAULT_REQUIRED_PRODUCTIZED_CAMEL_ARTIFACTS_TXT));
                final Path productizedCamelQuarkusArtifacts = multiModuleProjectDirectory.resolve((String) json
                        .getOrDefault("productizedCamelQuarkusArtifacts", DEFAULT_PRODUCTIZED_CAMEL_QUARKUS_ARTIFACTS_TXT));
                final int availableCiNodes = (Integer) json
                        .getOrDefault("availableCiNodes", Integer.valueOf(DEFAULT_AVAILABLE_CI_NODES));
                final Path jenkinsfile = multiModuleProjectDirectory.resolve((String) json
                        .getOrDefault("jenkinsfile", DEFAULT_JENKINSFILE));
                final Path jenkinsfileStageTemplate = multiModuleProjectDirectory.resolve((String) json
                        .getOrDefault("jenkinsfileStageTemplate", DEFAULT_JENKINSFILE_STAGE_TEMPLATE));
                final Path productizedDependenciesFile = multiModuleProjectDirectory.resolve((String) json
                        .getOrDefault("productizedDependenciesFile", DEFAULT_PRODUCTIZED_DEPENDENCIES_FILE));
                final Path allDependenciesFile = multiModuleProjectDirectory.resolve((String) json
                        .getOrDefault("allDependenciesFile", DEFAULT_ALL_DEPENDENCIES_FILE));
                final Path nonProductizedDependenciesFile = multiModuleProjectDirectory.resolve((String) json
                        .getOrDefault("nonProductizedDependenciesFile", DEFAULT_NON_PRODUCTIZED_DEPENDENCIES_FILE));
                @SuppressWarnings("unchecked")
                final Map<String, String> additionalExtensionDependencies = (Map<String, String>) json
                        .getOrDefault("additionalExtensionDependencies", Collections.emptyMap());

                final TreeMap<String, GavSet> additionalDependenciesMap = new TreeMap<>();
                if (additionalExtensionDependencies != null) {
                    for (Entry<String, String> en : additionalExtensionDependencies.entrySet()) {
                        additionalDependenciesMap.put(en.getKey(), GavSet.builder().includes(en.getValue()).build());
                    }
                }

                final Builder bannedDeps = GavSet.unionBuilder().defaultResult(GavSet.excludeAll());
                @SuppressWarnings("unchecked")
                final List<Map<String, Object>> rawBannedDependencyResources = (List<Map<String, Object>>) json
                        .getOrDefault("bannedDependencyResources", Collections.emptyList());
                for (Map<String, Object> resource : rawBannedDependencyResources) {
                    @SuppressWarnings("unchecked")
                    BannedDependencyResource bannedDependencyResource = new BannedDependencyResource(
                            (String) resource.get("location"),
                            (String) resource.get("xsltLocation"));
                    bannedDeps.union(bannedDependencyResource.getBannedSet(charset));
                }

                return new Product(
                        Collections.unmodifiableMap(extensionsMap),
                        prodGuideUrlTemplate,
                        majorVersion,
                        docReferenceDir,
                        Collections.unmodifiableMap(versionTransformations),
                        Collections.unmodifiableList(additionalProductizedArtifacts),
                        Collections.unmodifiableSet(excludeTests),
                        allowedMixedTests,
                        Collections.unmodifiableList(integrationTests),
                        requiredProductizedCamelArtifacts,
                        productizedCamelQuarkusArtifacts,
                        availableCiNodes,
                        jenkinsfile,
                        jenkinsfileStageTemplate,
                        productizedDependenciesFile,
                        allDependenciesFile,
                        nonProductizedDependenciesFile,
                        Collections.unmodifiableMap(additionalDependenciesMap),
                        bannedDeps.build());
            } catch (IOException e) {
                throw new RuntimeException("Could not read " + absProdJson, e);
            }
        }

        private final Map<Ga, Extension> extensions;
        private final String prodGuideUrlTemplate;
        private final String majorVersion;
        private final Path docReferenceDir;
        private final Map<String, String> versionTransformations;
        private final List<String> additionalProductizedArtifacts;
        private final Set<Ga> excludeTests;
        private final Map<Ga, Set<Ga>> allowedMixedTests;
        private final List<DirectoryScanner> integrationTests;
        private final Path requiredProductizedCamelArtifacts;
        private final Path productizedCamelQuarkusArtifacts;
        private final int availableCiNodes;
        private final Path jenkinsfile;
        private final Path jenkinsfileStageTemplate;
        private final Path productizedDependenciesFile;
        private final Path allDependenciesFile;
        private final Path nonProductizedDependenciesFile;
        private final Map<String, GavSet> additionalExtensionDependencies;
        private final GavSet bannedDependencies;

        public Product(Map<Ga, Extension> extensions, String prodGuideUrlTemplate, String majorVersion, Path docReferenceDir,
                Map<String, String> versionTransformations, List<String> additionalProductizedArtifacts, Set<Ga> excludeTests,
                Map<Ga, Set<Ga>> allowedMixedTests, List<DirectoryScanner> integrationTests,
                Path requiredProductizedCamelArtifacts,
                Path productizedCamelQuarkusArtifacts,
                int availableCiNodes,
                Path jenkinsfile,
                Path jenkinsfileStageTemplate,
                Path productizedDependenciesFile,
                Path allDependenciesFile,
                Path nonProductizedDependenciesFile,
                Map<String, GavSet> additionalExtensionDependencies,
                GavSet bannedDependencies) {
            this.extensions = extensions;
            this.prodGuideUrlTemplate = prodGuideUrlTemplate;
            this.majorVersion = majorVersion;
            this.docReferenceDir = docReferenceDir;
            this.versionTransformations = versionTransformations;
            this.additionalProductizedArtifacts = additionalProductizedArtifacts;
            this.excludeTests = excludeTests;
            this.allowedMixedTests = allowedMixedTests;
            this.integrationTests = integrationTests;
            this.requiredProductizedCamelArtifacts = requiredProductizedCamelArtifacts;
            this.productizedCamelQuarkusArtifacts = productizedCamelQuarkusArtifacts;
            this.availableCiNodes = availableCiNodes;
            this.jenkinsfile = jenkinsfile;
            this.jenkinsfileStageTemplate = jenkinsfileStageTemplate;
            this.productizedDependenciesFile = productizedDependenciesFile;
            this.allDependenciesFile = allDependenciesFile;
            this.nonProductizedDependenciesFile = nonProductizedDependenciesFile;
            this.additionalExtensionDependencies = additionalExtensionDependencies;
            this.bannedDependencies = bannedDependencies;
        }

        /**
         * @return a {@link Map} of {@link Extension}s defining a product
         */
        public Map<Ga, Extension> getProductExtensions() {
            return extensions;
        }

        /**
         * @return a {@link SortedSet} of {@link Ga}s representing modules required by the product. This is the
         *         "initial" set - i.e. any possible transitive dependencies are yet to be resolved.
         */
        public SortedSet<Ga> getInitialProductizedModules() {
            SortedSet<Ga> result = new TreeSet<>();
            for (Ga ga : extensions.keySet()) {
                result.add(ga);
                result.add(new Ga("org.apache.camel.quarkus", ga.getArtifactId() + "-deployment"));
            }
            if (additionalProductizedArtifacts != null) {
                for (String artifactId : additionalProductizedArtifacts) {
                    result.add(new Ga("org.apache.camel.quarkus", artifactId));
                }
            }
            return Collections.unmodifiableSortedSet(result);
        }

        /**
         * @param  ga the {@link Ga} to look the doc page URL for
         * @return    an URL to a doc page of the given extension, either a product doc page or a community page or the
         *            default
         *            {@value ProdExcludesMojo#defaultCommunityGuide}
         */
        public String getExtensionDocPageUrl(Ga ga) {
            final Extension ext = extensions.get(ga);

            if (ext != null && ext.hasProductDocumentationPage()) {
                return guideUrl(majorVersion, ga, prodGuideUrlTemplate);
            }
            final String artifactIdBase = ga.getArtifactId().replace("camel-quarkus-", "");
            if (Files.isRegularFile(docReferenceDir.resolve(artifactIdBase + ".adoc"))) {
                return guideUrl(majorVersion, ga, communityGuideUrlTemplate);
            } else {
                return defaultCommunityGuide;
            }
        }

        /**
         * @return see {@code product/README.adoc} of the current product branch
         */
        public Set<Ga> getExcludeTests() {
            return excludeTests;
        }

        /**
         * @return see {@code product/README.adoc} of the current product branch
         */
        public Map<Ga, Set<Ga>> getAllowedMixedTests() {
            return allowedMixedTests;
        }

        /**
         * A representation of a product extension as defined in {@code camel-quarkus-product-source.json}
         */
        static class Extension {
            private final Ga ga;
            private final ModeSupportStatus jvmSupportStatus;
            private final ModeSupportStatus nativeSupportStatus;

            public Extension(Ga ga, ModeSupportStatus jvmSupportStatus, ModeSupportStatus nativeSupportStatus) {
                this.ga = ga;
                this.jvmSupportStatus = jvmSupportStatus;
                this.nativeSupportStatus = nativeSupportStatus;
            }

            public boolean hasProductDocumentationPage() {
                return jvmSupportStatus.hasProductDocumentationPage() || nativeSupportStatus.hasProductDocumentationPage();
            }

            public Ga getGa() {
                return ga;
            }
        }

        public List<DirectoryScanner> getIntegrationTests() {
            return integrationTests;
        }

        public Path getRequiredProductizedCamelArtifacts() {
            return requiredProductizedCamelArtifacts;
        }

        public Path getProductizedCamelQuarkusArtifacts() {
            return productizedCamelQuarkusArtifacts;
        }

        public int getAvailableCiNodes() {
            return availableCiNodes;
        }

        public Path getJenkinsfile() {
            return jenkinsfile;
        }

        public Path getJenkinsfileStageTemplate() {
            return jenkinsfileStageTemplate;
        }

        public Path getProductizedDependenciesFile() {
            return productizedDependenciesFile;
        }

        public Path getAllDependenciesFile() {
            return allDependenciesFile;
        }

        public Path getNonProductizedDependenciesFile() {
            return nonProductizedDependenciesFile;
        }

        public Map<String, GavSet> getAdditionalExtensionDependencies() {
            return additionalExtensionDependencies;
        }

        public GavSet getBannedDependencies() {
            return bannedDependencies;
        }
    }

    static class TestGroup {
        private final TestCategory category;
        private final int index;
        private final List<String> tests = new ArrayList<>();

        public TestGroup(int index, TestCategory category) {
            this.category = category;
            this.index = index;
        }

        public String getHumanIndex() {
            return String.format("%02d", index + 1);
        }

        public void add(String testRelPath) {
            tests.add(testRelPath);
        }

        public String getHumanName() {
            return category.getHumanName() + " :: Group " + getHumanIndex();
        }

        public String getGroupDirectory() {
            return "product/integration-tests-" + category.getKey() + "/group-" + getHumanIndex();
        }

    }

    class TestCategoryTests {

        private final MavenSourceTree tree;
        private final List<Ga> tests;
        private final int maxTestsPerGroup;
        private final TestCategory category;
        private List<TestGroup> groups;

        public TestCategoryTests(MavenSourceTree tree, int maxTestsPerGroup, TestCategory category) {
            this.tree = tree;
            /* No need to split JVM tests into groups */
            this.maxTestsPerGroup = category.isNative() ? maxTestsPerGroup : Integer.MAX_VALUE;
            this.tests = new ArrayList<>();
            this.category = category;
        }

        public void addTest(Ga ga) {
            tests.add(ga);
        }

        public List<TestGroup> groupTests() {
            if (groups != null) {
                return groups;
            }

            final Path anyGroupDir = tree.getRootDirectory().resolve("product/integration-tests-product/group-01");
            Set<String> testPaths = tests.stream()
                    .map(test -> {
                        final Path testAbsPath = tree.getRootDirectory().resolve(tree.getModulesByGa().get(test).getPomPath())
                                .getParent();
                        return PomTunerUtils.toUnixPath(anyGroupDir.relativize(testAbsPath).toString());
                    })
                    .collect(Collectors.toCollection(() -> new TreeSet<String>()));

            int groupCount = Math.max(1, tests.size() / maxTestsPerGroup);
            final List<TestGroup> groups = new ArrayList<>(groupCount);

            final int minGroupSize = tests.size() / groupCount;
            final int rest = tests.size() % groupCount;
            final Iterator<String> testIt = testPaths.iterator();
            for (int i = 0; i < groupCount; i++) {
                final TestGroup group = new TestGroup(i, category);
                int groupSize = minGroupSize + (i < rest ? 1 : 0);
                while (groupSize-- > 0) {
                    group.add(testIt.next());
                }
                groups.add(group);
            }
            if (testIt.hasNext()) {
                StringBuilder msg = new StringBuilder("Still remaining tests there in " + category + ": ");
                while (testIt.hasNext()) {
                    msg.append(testIt.next()).append(", ");
                }
                throw new IllegalStateException(msg.toString());
            }
            this.groups = groups;
            return groups;
        }

        public void write() {
            final List<TestGroup> groups = groupTests();

            final Path productPomPath = tree.getRootDirectory().resolve("product/pom.xml");
            new PomTransformer(productPomPath, charset, simpleElementWhitespace)
                    .transform(Transformation.addModuleIfNeeded("integration-tests-" + category.getKey(), String::compareTo));

            /* Init the category pom */
            final Path categoryPomPath = category.resolveMixedModulePath(tree.getRootDirectory());
            final String version = tree.getRootModule().getGav().getVersion().asConstant();
            final String categoryArtifactId = "camel-quarkus-integration-tests-" + category.getKey();
            initializeMixedTestsPom(categoryPomPath, "camel-quarkus-product", version,
                    "../pom.xml", categoryArtifactId,
                    "Integration Tests :: " + category.getHumanName());
            /* Link the Group poms in the Category pom */
            final String profile = category.isMixed() ? "mixed" : null;
            final List<String> groupPaths = groups.stream()
                    .map(g -> "group-" + g.getHumanIndex())
                    .collect(Collectors.toList());
            new PomTransformer(categoryPomPath, charset, simpleElementWhitespace)
                    .transform(Transformation.addModules(profile, groupPaths));

            /* Create the group poms */
            groups.stream()
                    .forEach(group -> {
                        final String humanIndex = group.getHumanIndex();
                        final String g = "group-" + humanIndex;
                        final Path groupPomPath = categoryPomPath.getParent().resolve(g).resolve("pom.xml");
                        initializeMixedTestsPom(groupPomPath, categoryArtifactId, version, "../pom.xml",
                                categoryArtifactId + "-" + g,
                                "Integration Tests :: " + group.getHumanName());
                        new PomTransformer(groupPomPath, charset, simpleElementWhitespace)
                                .transform(Transformation.addModules(null, group.tests));
                    });
        }

    }

}
