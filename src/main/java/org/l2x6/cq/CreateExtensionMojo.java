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
package org.l2x6.cq;

import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.lang.reflect.Field;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Stack;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.lang.model.SourceVersion;

import org.apache.maven.model.Model;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.l2x6.cq.CqCatalog.WrappedModel;
import org.l2x6.cq.PomTransformer.Gavtcs;
import org.l2x6.cq.PomTransformer.Transformation;

import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateException;
import freemarker.template.TemplateMethodModelEx;
import freemarker.template.TemplateModelException;

/**
 * Scaffolds a new Camel Quarkus extension.
 */
@Mojo(name = "create", requiresProject = true, inheritByDefault = false)
public class CreateExtensionMojo extends AbstractMojo {

    static final String QUOTED_DOLLAR = Matcher.quoteReplacement("$");

    static final String QUARKUS_VERSION_PROP = "quarkus.version";

    static final String DEFAULT_ENCODING = "utf-8";
    static final String DEFAULT_QUARKUS_VERSION = "@{" + QUARKUS_VERSION_PROP + "}";
    static final String QUARKUS_VERSION_POM_EXPR = "${" + QUARKUS_VERSION_PROP + "}";
    static final String DEFAULT_BOM_ENTRY_VERSION = "@{project.version}";
    static final String DEFAULT_TEMPLATES_URI_BASE = "classpath:/create-extension-templates";
    static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("@\\{([^\\}]+)\\}");

    static final String CQ_EXTENSIONS_DIR = "extensions";
    static final String CQ_EXTENSIONS_JVM_DIR = "extensions-jvm";

    static final String CQ_ARTIFACT_ID_PREFIX = "camel-quarkus-";
    static final String CQ_NAME_PREFIX = "Camel Quarkus :: ";
    static final String CQ_NAME_SEGMENT_DELIMITER = " :: ";
    static final String CQ_JAVA_PACKAGE_INFIX = "component";
    static final String CQ_RUNTIME_BOM_PATH = "${project.basedir}/poms/bom/pom.xml";
    static final String CQ_DEPLOYMENT_BOM_PATH = "${project.basedir}/poms/bom-deployment/pom.xml";
    static final String CQ_BOM_ENTRY_VERSION = "@{camel-quarkus.version}";
    static final String CQ_INTEGRATION_TESTS_PATH = "integration-tests/pom.xml";
    static final String CQ_TEMPLATES_URI_BASE = "tooling/create-extension-templates";

    static final String CQ_ADDITIONAL_RUNTIME_DEPENDENCIES = "org.apache.camel:camel-@{cq.artifactIdBase}:@{$}{camel.version}";

    /**
     * Directory where the changes should be performed. Default is the current directory of the current Java process.
     *
     * @since 0.1.0
     */
    @Parameter(property = "cq.basedir", defaultValue = "${project.basedir}")
    File baseDir;
    private Path basePath;

    /**
     * The directory where the extension should be created. The default value depends on {@link #nativeSupported}: if
     * {@link #nativeSupported} is {@code true}, the default is {@value #CQ_EXTENSIONS_DIR} otherwise the default is
     * {@value #CQ_EXTENSIONS_JVM_DIR}
     */
    @Parameter(property = "cq.extensionsDir")
    File extensionsDir;
    private Path extensionsPath;

    private String groupId;
    private String artifactId;
    private String version;

    /**
     * A prefix common to all extension artifactIds in the current source tree. If you set {@link #artifactIdPrefix},
     * set also {@link #artifactIdBase}, but do not set {@link #artifactId}.
     *
     * @since 0.0.1
     */
    @Parameter(property = "cq.artifactIdPrefix", defaultValue = CQ_ARTIFACT_ID_PREFIX)
    String artifactIdPrefix;

    /**
     * The unique part of the {@link #artifactId}. If you set {@link #artifactIdBase}, {@link #artifactIdPrefix}
     * may also be set, but not {@link #artifactId}.
     *
     * @since 0.0.1
     */
    @Parameter(property = "cq.artifactIdBase", required = true)
    String artifactIdBase;

    /**
     * A prefix common to all extension names in the current source tree. If you set {@link #namePrefix}, set also
     * {@link #nameBase}, but do not set {@link #name}.
     *
     * @since 0.0.1
     */
    @Parameter(property = "cq.namePrefix", defaultValue = CQ_NAME_PREFIX)
    String namePrefix;

    /**
     * The unique part of the {@link #name}. If you set {@link #nameBase}, set also {@link #namePrefix}, but do not set
     * {@link #name}.
     * <p>
     * If neither {@link #name} nor @{link #nameBase} is set, @{link #nameBase} will be derived from
     * {@link #artifactIdBase}.
     *
     * @since 0.0.1
     */
    @Parameter(property = "cq.nameBase")
    String nameBase;

    /**
     * A string that will delimit {@link #name} from {@code Parent}, {@code Runtime} and {@code Deployment} tokens in
     * the respective modules.
     *
     * @since 0.0.1
     */
    @Parameter(property = "cq.nameSegmentDelimiter", defaultValue = CQ_NAME_SEGMENT_DELIMITER)
    String nameSegmentDelimiter;

    /**
     * Base Java package under which Java classes should be created in Runtime and Deployment modules. If not set, the
     * Java package will be auto-generated out of {@link #groupId}, {@link #javaPackageInfix} and {@link #artifactId}
     *
     * @since 0.0.1
     */
    @Parameter(property = "cq.javaPackageBase")
    String javaPackageBase;

    /**
     * If {@link #javaPackageBase} is not set explicitly, this infix will be put between package segments taken from
     * {@link #groupId} and {@link #artifactId}.
     * <p>
     * Example: Given
     * <ul>
     * <li>{@link #groupId} is {@code org.example.quarkus.extensions}</li>
     * <li>{@link #javaPackageInfix} is {@code foo.bar}</li>
     * <li>{@link #artifactId} is {@code cool-extension}</li>
     * </ul>
     * Then the auto-generated {@link #javaPackageBase} will be
     * {@code org.example.quarkus.extensions.foo.bar.cool.extension}
     *
     * @since 0.0.1
     */
    @Parameter(property = "cq.javaPackageInfix", defaultValue = CQ_JAVA_PACKAGE_INFIX)
    String javaPackageInfix;

    /**
     * Quarkus version the newly created extension should depend on. If you want to pass a property placeholder, use
     * {@code @} instead if {@code $} so that the property is not evaluated by the current mojo - e.g.
     * <code>@{quarkus.version}</code>
     *
     * @since 0.0.1
     */
    @Parameter(defaultValue = DEFAULT_QUARKUS_VERSION, required = true, property = "cq.version")
    String quarkusVersion;

    /**
     * URI prefix to use when looking up FreeMarker templates when generating various source files. You need to touch
     * this only if you want to provide your own custom templates.
     * <p>
     * The following URI schemes are supported:
     * <ul>
     * <li>{@code classpath:}</li>
     * <li>{@code file:} (relative to {@link #basedir})</li>
     * </ul>
     * These are the template files you may want to provide under your custom {@link #templatesUriBase}:
     * <ul>
     * <li>{@code deployment-pom.xml}</li>
     * <li>{@code integration-test-application.properties}</li>
     * <li>{@code integration-test-pom.xml}</li>
     * <li>{@code IT.java}</li>
     * <li>{@code parent-pom.xml}</li>
     * <li>{@code Processor.java}</li>
     * <li>{@code runtime-pom.xml}</li>
     * <li>{@code Test.java}</li>
     * <li>{@code TestResource.java}</li>
     * </ul>
     * Note that you do not need to provide all of them. Files not available in your custom {@link #templatesUriBase}
     * will be looked up in the default URI base {@value #DEFAULT_TEMPLATES_URI_BASE}. The default templates are
     * maintained <a href=
     * "https://github.com/quarkusio/quarkus/tree/master/devtools/maven/src/main/resources/create-extension-templates">here</a>.
     *
     * @since 0.0.1
     */
    @Parameter(defaultValue = DEFAULT_TEMPLATES_URI_BASE, required = true, property = "cq.templatesUriBase")
    String templatesUriBase;

    /**
     * Encoding to read and write files in the current source tree
     *
     * @since 0.0.1
     */
    @Parameter(defaultValue = DEFAULT_ENCODING, required = true, property = "cq.encoding")
    String encoding;

    /**
     * Path relative to {@link #basedir} pointing at a {@code pom.xml} file containing the BOM (Bill of Materials) that
     * manages runtime extension artifacts. If set, the newly created Runtime module will be added to
     * {@code <dependencyManagement>} section of this bom; otherwise the newly created Runtime module will not be added
     * to any BOM.
     *
     * @since 0.0.1
     */
    @Parameter(property = "cq.runtimeBomPath", defaultValue = CQ_RUNTIME_BOM_PATH)
    File runtimeBom;
    private Path runtimeBomPath;

    /**
     * Path relative to {@link #basedir} pointing at a {@code pom.xml} file containing the BOM (Bill of Materials) that
     * manages deployment time extension artifacts. If set, the newly created Deployment module will be added to
     * {@code <dependencyManagement>} section of this bom; otherwise the newly created Deployment module will not be
     * added to any BOM.
     *
     * @since 0.0.1
     */
    @Parameter(property = "cq.deploymentBomPath", defaultValue = CQ_DEPLOYMENT_BOM_PATH)
    File deploymentBom;
    private Path deploymentBomPath;

    /**
     * A version for the entries added to the runtime BOM (see {@link #runtimeBomPath}) and to the deployment BOM (see
     * {@link #deploymentBomPath}). If you want to pass a property placeholder, use {@code @} instead if {@code $} so
     * that the property is not evaluated by the current mojo - e.g. <code>@{my-project.version}</code>
     *
     * @since 0.0.1
     */
    @Parameter(property = "cq.bomEntryVersion", defaultValue = CQ_BOM_ENTRY_VERSION)
    String bomEntryVersion;

    /**
     * A list of strings of the form {@code groupId:artifactId:version[:type[:classifier[:scope]]]} representing the
     * dependencies that should be added to the generated runtime module and to the runtime BOM if it is specified via
     * {@link #runtimeBomPath}.
     * <p>
     * In case the built-in Maven <code>${placeholder}</code> expansion does not work well for you (because you e.g.
     * pass {@link #additionalRuntimeDependencies}) via CLI, the Mojo supports a custom <code>@{placeholder}</code>
     * expansion:
     * <ul>
     * <li><code>@{$}</code> will be expanded to {@code $} - handy for escaping standard placeholders. E.g. to insert
     * <code>${quarkus.version}</code> to the BOM, you need to pass <code>@{$}{quarkus.version}</code></li>
     * <li><code>@{quarkus.field}</code> will be expanded to whatever value the given {@code field} of this mojo has at
     * runtime.</li>
     * <li>Any other <code>@{placeholder}</code> will be resolved using the current project's properties</li>
     * </ul>
     *
     * @since 0.0.1
     */
    @Parameter(property = "cq.additionalRuntimeDependencies", defaultValue = CQ_ADDITIONAL_RUNTIME_DEPENDENCIES)
    List<String> additionalRuntimeDependencies;

    /**
     * The directory where the extension's itest should be created. The default value depends on
     * {@link #nativeSupported}: if
     * {@link #nativeSupported} is {@code true}, the default is {@value #CQ_INTEGRATION_TESTS_PATH} otherwise the
     * default is {@code extensions-jvm/<artifactIdBase>/integration-test}.
     *
     * @since 0.0.1
     */
    @Parameter(property = "cq.itestParentPath", defaultValue = CQ_INTEGRATION_TESTS_PATH)
    File itestParent;

    @Parameter(defaultValue = "${project}", readonly = true)
    MavenProject project;

    /**
     * A human readable description to use in the runtime module and in {@code quarkus-extension.yaml}.
     *
     * @since 0.0.1
     */
    @Parameter(property = "cq.metadata.description")
    String description;

    /**
     * A list of keywords to use in {@code quarkus-extension.yaml}. If the {@link #keywords} are not provided, the
     * {@code quarkus-extension.yaml} will not be generated.
     *
     * @since 0.0.1
     */
    @Parameter(property = "cq.metadata.keywords")
    List<String> keywords;

    /**
     * A guide URL to use in {@code quarkus-extension.yaml}.
     *
     * @since 0.0.1
     */
    @Parameter(property = "cq.metadata.guideUrl")
    String guideUrl;

    /**
     * A list of categories to use in {@code quarkus-extension.yaml}.
     *
     * @since 0.0.1
     */
    @Parameter(property = "cq.metadata.categories")
    List<String> categories;

    /**
     * The extension status to use in {@code quarkus-extension.yaml}; one of {@code stable} or {@code preview}
     *
     * @since 0.0.1
     */
    @Parameter(property = "cq.nativeSupported", defaultValue = "true")
    boolean nativeSupported;

    /**
     * A list of directory paths relative to the current module's {@code baseDir} containing Quarkus extensions.
     *
     * @since 0.0.1
     */
    @Parameter(required = true)
    List<ExtensionDir> extensionDirs;

    List<org.l2x6.cq.CqCatalog.WrappedModel> models;
    org.l2x6.cq.CqCatalog.WrappedModel model;

    Charset charset;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        basePath = baseDir.toPath().toAbsolutePath().normalize();
        if (extensionsDir == null) {
            extensionsDir = nativeSupported ? basePath.resolve(CQ_EXTENSIONS_DIR).toFile()
                    : basePath.resolve(CQ_EXTENSIONS_JVM_DIR).toFile();
        }
        extensionsPath = extensionsDir.toPath();
        runtimeBomPath = runtimeBom.toPath();
        deploymentBomPath = deploymentBom.toPath();
        if (extensionDirs == null || extensionDirs.isEmpty()) {
            extensionDirs = PomSorter.CQ_EXTENSIONS_DIRECTORIES;
        }

        charset = Charset.forName(encoding);
        this.models = new CqCatalog().filterModels(artifactIdBase);
        switch (models.size()) {
        case 0:
            throw new IllegalStateException("Could not find name " + artifactIdBase + " in Camel catalog");
        default:
            this.model = models.get(0);
            break;
        }

        if (artifactIdPrefix == null) {
            artifactIdPrefix = "";
        }
        artifactId = artifactIdPrefix == null || artifactIdPrefix.isEmpty() ? artifactIdBase
                : artifactIdPrefix + artifactIdBase;

        if (nameBase == null) {
            nameBase = model.delegate.getTitle();
            if (nameBase == null) {
                throw new MojoFailureException("Name not found for " + artifactIdBase);
            }
        }
        if (namePrefix == null) {
            namePrefix = "";
        }

        if (this.description == null) {
            this.description = model.delegate.getDescription();
        }
        if (this.keywords == null || this.keywords.isEmpty()) {
            final String rawLabel = model.delegate.getLabel();
            if (rawLabel != null) {
                this.keywords = Stream.of(rawLabel.split(","))
                        .map(String::trim)
                        .map(label -> label.toLowerCase(Locale.ROOT))
                        .sorted()
                        .collect(Collectors.toList());
            } else {
                this.keywords = Collections.emptyList();
            }
        }
        if (this.guideUrl == null) {
            /*
             * We do not know yet whether this extension will have a page
             * under https://camel.apache.org/camel-quarkus/latest/extensions
             * The value may get corrected by the maven Mojo that generates the .adoc files.
             */
            this.guideUrl = "https://camel.apache.org/components/latest/" + artifactIdBase + "-" + model.kind.name() + ".html";
        }
        if (this.categories == null || this.categories.isEmpty()) {
            this.categories = Collections.singletonList("integration");
        }

        final Path extensionsPomPath = this.extensionsPath.resolve("pom.xml");
        final Model extensionsModel = CqUtils.readPom(extensionsPomPath, charset);
        this.groupId = getGroupId(extensionsModel);
        this.version = CqUtils.getVersion(extensionsModel);

        final TemplateParams templateParams = getTemplateParams();
        final Configuration cfg = CqUtils.getTemplateConfig(basePath, DEFAULT_TEMPLATES_URI_BASE, templatesUriBase, encoding);

        generateExtensionProjects(cfg, templateParams);
        if (!extensionsModel.getModules().contains(artifactIdBase)) {
            getLog().info(String.format("Adding module [%s] to [%s]", artifactIdBase, extensionsPomPath));
            pomTransformer(extensionsPomPath).transform(Transformation.addModule(artifactIdBase));
        }
        PomSorter.sortModules(extensionsPomPath);

        if (runtimeBomPath != null) {
            getLog().info(
                    String.format("Adding [%s] to dependencyManagement in [%s]", templateParams.artifactId,
                            runtimeBomPath));
            List<PomTransformer.Transformation> transformations = new ArrayList<PomTransformer.Transformation>();
            transformations
                    .add(Transformation.addManagedDependency(templateParams.groupId, templateParams.artifactId,
                            templateParams.bomEntryVersion));
            for (Gavtcs gavtcs : templateParams.additionalRuntimeDependencies) {
                getLog().info(String.format("Adding [%s] to dependencyManagement in [%s]", gavtcs, runtimeBomPath));
                transformations.add(Transformation.addManagedDependency(gavtcs));
            }
            pomTransformer(runtimeBomPath).transform(transformations);
            PomSorter.sortDependencyManagement(runtimeBomPath);
        }
        if (deploymentBomPath != null) {
            final String aId = templateParams.artifactId + "-deployment";
            getLog().info(String.format("Adding [%s] to dependencyManagement in [%s]", aId, deploymentBomPath));
            pomTransformer(deploymentBomPath)
                    .transform(Transformation.addManagedDependency(templateParams.groupId, aId,
                            templateParams.bomEntryVersion));
            PomSorter.sortDependencyManagement(basePath, Collections.singletonList(basePath.relativize(deploymentBomPath).toString()));
        }
        generateItest(cfg, templateParams);

    }

    Path getExtensionProjectBaseDir() {
        return extensionsPath.resolve(artifactIdBase);
    }

    Path getExtensionRuntimeBaseDir() {
        return getExtensionProjectBaseDir().resolve("runtime");
    }

    Path getExtensionDeploymentBaseDir() {
        return getExtensionProjectBaseDir().resolve("deployment");
    }

    void generateExtensionProjects(Configuration cfg, TemplateParams templateParams) {
        final Path extParentPomPath = getExtensionProjectBaseDir().resolve("pom.xml");
        evalTemplate(cfg, "parent-pom.xml", extParentPomPath, templateParams);

        final Path extensionRuntimeBaseDir = getExtensionRuntimeBaseDir();
        final Path dir = extensionRuntimeBaseDir.resolve("src/main/java")
                .resolve(templateParams.javaPackageBase.replace('.', '/'));
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new RuntimeException("Could not create directories " + dir, e);
        }
        evalTemplate(cfg, "runtime-pom.xml", extensionRuntimeBaseDir.resolve("pom.xml"),
                templateParams);

        if (keywords != null && !keywords.isEmpty()) {
            final Path metaInfDir = extensionRuntimeBaseDir.resolve("src/main/resources/META-INF");
            try {
                Files.createDirectories(metaInfDir);
            } catch (IOException e) {
                throw new RuntimeException("Could not create " + metaInfDir, e);
            }
            evalTemplate(cfg, "quarkus-extension.yaml", metaInfDir.resolve("quarkus-extension.yaml"), templateParams);
        }

        evalTemplate(cfg, "deployment-pom.xml", getExtensionDeploymentBaseDir().resolve("pom.xml"),
                templateParams);
        final Path processorPath = getExtensionDeploymentBaseDir()
                .resolve("src/main/java")
                .resolve(templateParams.javaPackageBase.replace('.', '/'))
                .resolve("deployment")
                .resolve(toCapCamelCase(templateParams.artifactIdBase) + "Processor.java");
        evalTemplate(cfg, "Processor.java", processorPath, templateParams);
    }

    PomTransformer pomTransformer(Path basePomXml) {
        return new PomTransformer(basePomXml, charset);
    }

    TemplateParams getTemplateParams() throws MojoExecutionException {
        final TemplateParams templateParams = new TemplateParams();

        templateParams.artifactId = artifactId;
        templateParams.artifactIdPrefix = artifactIdPrefix;
        templateParams.artifactIdBase = artifactIdBase;
        templateParams.groupId = groupId;
        templateParams.version = version;

        templateParams.namePrefix = namePrefix;
        templateParams.nameBase = nameBase;
        templateParams.nameSegmentDelimiter = nameSegmentDelimiter;
        templateParams.quarkusVersion = QUARKUS_VERSION_POM_EXPR;
        templateParams.bomEntryVersion = bomEntryVersion.replace('@', '$');

        templateParams.javaPackageBase = javaPackageBase != null ? javaPackageBase
                : getJavaPackage(templateParams.groupId, javaPackageInfix, artifactId);
        templateParams.additionalRuntimeDependencies = getAdditionalRuntimeDependencies();
        templateParams.runtimeBomPathSet = runtimeBomPath != null;

        templateParams.description = model.delegate.getDescription();
        final String rawLabel = model.delegate.getLabel();
        if (rawLabel != null) {
            templateParams.keywords = Stream.of(rawLabel.split(","))
                    .map(String::trim)
                    .map(label -> label.toLowerCase(Locale.ROOT))
                    .sorted()
                    .collect(Collectors.toList());
        } else {
            templateParams.keywords = Collections.emptyList();
        }
        templateParams.guideUrl = guideUrl;
        templateParams.categories = categories;
        templateParams.nativeSupported = nativeSupported;
        templateParams.kind = model.kind.name();
        templateParams.models = models;

        return templateParams;
    }

    void generateItest(Configuration cfg, TemplateParams model) {
        final Path itestParentPath;
        final Path itestDir;
        if (nativeSupported) {
            if (itestParent == null) {
                itestParent = basePath.resolve(CQ_INTEGRATION_TESTS_PATH).toFile();
            }
            itestParentPath = itestParent.toPath();
            itestDir = itestParentPath.getParent().resolve(artifactIdBase);
        } else {
            itestParentPath = getExtensionProjectBaseDir().resolve("pom.xml");
            itestDir = itestParentPath.getParent().resolve("integration-test");
        }

        final Model itestParent = CqUtils.readPom(itestParentPath, charset);
        if (!"pom".equals(itestParent.getPackaging())) {
            throw new RuntimeException(
                    "Can add an extension integration test only under a project with packagin 'pom'; found: "
                            + itestParent.getPackaging() + " in " + itestParentPath);
        }
        getLog().info(String.format("Adding module [%s] to [%s]", itestDir.getFileName().toString(), itestParentPath));
        pomTransformer(itestParentPath).transform(Transformation.addModule(itestDir.getFileName().toString()));
        if (nativeSupported) {
            PomSorter.sortModules(itestParentPath);
        }

        model.itestParentGroupId = getGroupId(itestParent);
        model.itestParentArtifactId = itestParent.getArtifactId();
        model.itestParentVersion = CqUtils.getVersion(itestParent);
        model.itestParentRelativePath = "../pom.xml";

        final Path itestPomPath = itestDir.resolve("pom.xml");
        evalTemplate(cfg, "integration-test-pom.xml", itestPomPath, model);
        PomSorter.updateMvndRules(basePath, itestPomPath, PomSorter.findExtensionArtifactIds(basePath, extensionDirs));

        if (nativeSupported) {
            evalTemplate(cfg, "integration-test-application.properties",
                    itestDir.resolve("src/main/resources/application.properties"), model);
        }

        final String artifactIdBaseCapCamelCase = toCapCamelCase(model.artifactIdBase);
        final Path testResourcePath = itestDir.resolve("src/main/java/" + model.javaPackageBase.replace('.', '/')
                + "/it/" + artifactIdBaseCapCamelCase + "Resource.java");
        evalTemplate(cfg, "TestResource.java", testResourcePath, model);
        final Path testClassDir = itestDir
                .resolve("src/test/java/" + model.javaPackageBase.replace('.', '/') + "/it");
        evalTemplate(cfg, "Test.java", testClassDir.resolve(artifactIdBaseCapCamelCase + "Test.java"),
                model);
        if (nativeSupported) {
            evalTemplate(cfg, "IT.java", testClassDir.resolve(artifactIdBaseCapCamelCase + "IT.java"),
                    model);
        }

    }

    List<Gavtcs> getAdditionalRuntimeDependencies() {
        final List<Gavtcs> result = new ArrayList<>();
        if (additionalRuntimeDependencies != null && !additionalRuntimeDependencies.isEmpty()) {
            for (String rawGavtc : additionalRuntimeDependencies) {
                rawGavtc = replacePlaceholders(rawGavtc);
                result.add(Gavtcs.of(rawGavtc));
            }
        }
        return result;
    }

    String replacePlaceholders(String gavtc) {
        final StringBuffer transformedGavtc = new StringBuffer();
        final Matcher m = PLACEHOLDER_PATTERN.matcher(gavtc);
        while (m.find()) {
            final String key = m.group(1);
            if ("$".equals(key)) {
                m.appendReplacement(transformedGavtc, QUOTED_DOLLAR);
            } else if (key.startsWith("cq.")) {
                final String fieldName = key.substring("cq.".length());
                try {
                    final Field field = this.getClass().getDeclaredField(fieldName);
                    Object val = field.get(this);
                    if (val != null) {
                        m.appendReplacement(transformedGavtc, String.valueOf(val));
                    }
                } catch (NoSuchFieldException | SecurityException | IllegalArgumentException
                        | IllegalAccessException e) {
                    throw new RuntimeException(e);
                }
            } else {
                final Object val = project.getProperties().get(key);
                if (val != null) {
                    m.appendReplacement(transformedGavtc, String.valueOf(val));
                }
            }
        }
        m.appendTail(transformedGavtc);
        return transformedGavtc.toString();
    }

    static String getGroupId(Model basePom) {
        return basePom.getGroupId() != null ? basePom.getGroupId()
                : basePom.getParent() != null && basePom.getParent().getGroupId() != null
                        ? basePom.getParent().getGroupId()
                        : null;
    }

    static String toCapCamelCase(String artifactIdBase) {
        final StringBuilder sb = new StringBuilder(artifactIdBase.length());
        for (String segment : artifactIdBase.split("[.\\-]+")) {
            sb.append(Character.toUpperCase(segment.charAt(0)));
            if (segment.length() > 1) {
                sb.append(segment.substring(1));
            }
        }
        return sb.toString();
    }

    static String toSnakeCase(String artifactIdBase) {
        final StringBuilder sb = new StringBuilder(artifactIdBase.length());
        final String[] segments = artifactIdBase.split("[.\\-]+");
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                sb.append('_');
            }
            sb.append(segments[i].toLowerCase(Locale.ROOT));
        }
        return sb.toString();
    }

    static String getJavaPackage(String groupId, String javaPackageInfix, String artifactId) {
        final Stack<String> segments = new Stack<>();
        for (String segment : groupId.split("[.\\-]+")) {
            if (segments.isEmpty() || !segments.peek().equals(segment)) {
                segments.add(segment);
            }
        }
        if (javaPackageInfix != null) {
            for (String segment : javaPackageInfix.split("[.\\-]+")) {
                segments.add(segment);
            }
        }
        for (String segment : artifactId.split("[.\\-]+")) {
            if (!segments.contains(segment)) {
                segments.add(segment);
            }
        }
        return segments.stream() //
                .map(s -> s.toLowerCase(Locale.ROOT)) //
                .map(s -> SourceVersion.isKeyword(s) ? s + "_" : s) //
                .collect(Collectors.joining("."));
    }

    void evalTemplate(Configuration cfg, String templateUri, Path dest, TemplateParams model) {
        getLog().info("Adding " + dest);
        try {
            final Template template = cfg.getTemplate(templateUri);
            Files.createDirectories(dest.getParent());
            try (Writer out = Files.newBufferedWriter(dest)) {
                template.process(model, out);
            }
        } catch (IOException | TemplateException e) {
            throw new RuntimeException("Could not evaluate template " + templateUri, e);
        }
    }

    static String artifactIdBase(String artifactId) {
        final int lBPos = artifactId.indexOf('(');
        final int rBPos = artifactId.indexOf(')');
        if (lBPos >= 0 && rBPos >= 0) {
            return artifactId.substring(lBPos + 1, rBPos);
        } else {
            return artifactId;
        }
    }

    public static class TemplateParams {
        public boolean nativeSupported;
        public String itestParentRelativePath;
        public String itestParentVersion;
        public String itestParentArtifactId;
        public String itestParentGroupId;
        public String groupId;
        public String artifactId;
        public String artifactIdPrefix;
        public String artifactIdBase;
        public String version;
        public String namePrefix;
        public String nameBase;
        public String nameSegmentDelimiter;
        public String javaPackageBase;
        public String quarkusVersion;
        public List<Gavtcs> additionalRuntimeDependencies;
        public boolean runtimeBomPathSet;
        public String bomEntryVersion;
        public String description;
        public List<String> keywords;
        public String guideUrl;
        public List<String> categories;
        public String kind;
        public List<WrappedModel> models;
        private final TemplateMethodModelEx toCapCamelCase = new TemplateMethodModelEx() {
            @Override
            public Object exec(List arguments) throws TemplateModelException {
                if (arguments.size() != 1) {
                    throw new TemplateModelException("Wrong argument count in toCamelCase()");
                }
                return toCapCamelCase(String.valueOf(arguments.get(0)));
            }
        };
        private final TemplateMethodModelEx toSnakeCase = new TemplateMethodModelEx() {
            @Override
            public Object exec(List arguments) throws TemplateModelException {
                if (arguments.size() != 1) {
                    throw new TemplateModelException("Wrong argument count in toCamelCase()");
                }
                return toSnakeCase(String.valueOf(arguments.get(0)));
            }
        };

        public boolean isNativeSupported() {
            return nativeSupported;
        }

        public String getJavaPackageBase() {
            return javaPackageBase;
        }

        public String getArtifactIdPrefix() {
            return artifactIdPrefix;
        }

        public String getArtifactIdBase() {
            return artifactIdBase;
        }

        public String getNamePrefix() {
            return namePrefix;
        }

        public String getNameBase() {
            return nameBase;
        }

        public String getNameSegmentDelimiter() {
            return nameSegmentDelimiter;
        }

        public String getQuarkusVersion() {
            return quarkusVersion;
        }

        public String getGroupId() {
            return groupId;
        }

        public String getVersion() {
            return version;
        }

        public String getArtifactId() {
            return artifactId;
        }

        public List<Gavtcs> getAdditionalRuntimeDependencies() {
            return additionalRuntimeDependencies;
        }

        public boolean isRuntimeBomPathSet() {
            return runtimeBomPathSet;
        }

        public String getItestParentRelativePath() {
            return itestParentRelativePath;
        }

        public String getItestParentVersion() {
            return itestParentVersion;
        }

        public String getItestParentArtifactId() {
            return itestParentArtifactId;
        }

        public String getItestParentGroupId() {
            return itestParentGroupId;
        }

        public String getBomEntryVersion() {
            return bomEntryVersion;
        }

        public String getDescription() {
            return description;
        }

        public List<String> getKeywords() {
            return keywords;
        }

        public String getGuideUrl() {
            return guideUrl;
        }

        public List<String> getCategories() {
            return categories;
        }

        public String getKind() {
            return kind;
        }

        public List<WrappedModel> getModels() {
            return models;
        }

        public TemplateMethodModelEx getToCapCamelCase() {
            return toCapCamelCase;
        }

        public TemplateMethodModelEx getToSnakeCase() {
            return toSnakeCase;
        }

    }

}
