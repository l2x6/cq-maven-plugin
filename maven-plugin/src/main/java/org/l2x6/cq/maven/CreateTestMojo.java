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

import freemarker.template.Configuration;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.camel.tooling.model.ArtifactModel;
import org.apache.maven.model.Model;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.l2x6.cq.common.CqCatalog;
import org.l2x6.cq.common.CqCatalog.Flavor;
import org.l2x6.cq.common.CqCommonUtils;
import org.l2x6.pom.tuner.PomTransformer;
import org.l2x6.pom.tuner.PomTransformer.SimpleElementWhitespace;
import org.l2x6.pom.tuner.PomTransformer.Transformation;

/**
 * Scaffolds a new test.
 *
 * @since 0.28.0
 */
@Mojo(name = "new-test", requiresProject = true, inheritByDefault = false)
public class CreateTestMojo extends AbstractExtensionListMojo {

    static final String QUARKUS_VERSION_PROP = "quarkus.version";

    static final String DEFAULT_QUARKUS_VERSION = "@{" + QUARKUS_VERSION_PROP + "}";
    static final String QUARKUS_VERSION_POM_EXPR = "${" + QUARKUS_VERSION_PROP + "}";
    static final String DEFAULT_BOM_ENTRY_VERSION = "@{project.version}";

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
    static final String CQ_INTEGRATION_TESTS_JVM_PATH = "integration-tests-jvm/pom.xml";
    static final String CQ_TEMPLATES_URI_BASE = "tooling/create-extension-templates";

    static final String CQ_ADDITIONAL_RUNTIME_DEPENDENCIES = "org.apache.camel:camel-@{cq.artifactIdBase}:@{$}{camel.version}";

    protected Path basePath;

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
     * {@link #nameBase}.
     *
     * @since 0.0.1
     */
    @Parameter(property = "cq.namePrefix", defaultValue = CQ_NAME_PREFIX)
    String namePrefix;

    /**
     * The unique part of the name. If you set {@link #nameBase}, set also {@link #namePrefix}.
     *
     * @since 0.0.1
     */
    @Parameter(property = "cq.nameBase")
    String nameBase;

    /**
     * A string that will delimit Maven module name from {@code Parent}, {@code Runtime} and {@code Deployment} tokens in
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
     * URI prefix to use when looking up FreeMarker templates when generating various source files. You need to touch
     * this only if you want to provide your own custom templates.
     * <p>
     * The following URI schemes are supported:
     * <ul>
     * <li>{@code classpath:}</li>
     * <li>{@code file:} (relative to {@code project.basedir})</li>
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
     * will be looked up in the default URI base (CqUtils.DEFAULT_TEMPLATES_URI_BASE). The default templates are
     * maintained <a href=
     * "https://github.com/quarkusio/quarkus/tree/main/devtools/maven/src/main/resources/create-extension-templates">here</a>.
     *
     * @since 0.0.1
     */
    @Parameter(defaultValue = CqUtils.DEFAULT_TEMPLATES_URI_BASE, required = true, property = "cq.templatesUriBase")
    String templatesUriBase;

    /**
     * The directory where the extension's itest should be created. The default value depends on
     * {@link #nativeSupported}: if
     * {@link #nativeSupported} is {@code true}, the default is {@value #CQ_INTEGRATION_TESTS_PATH} otherwise the
     * default is {@code extensions-jvm/<artifactIdBase>/integration-test}.
     *
     * @since 0.0.1
     */
    @Parameter(property = "cq.itestParentPath")
    File itestParent;

    @Parameter(defaultValue = "${project}", readonly = true)
    MavenProject project;

    /**
     * If {@code true} an extension with native support will be generated; otherwise a JVM-only extension will be
     * generated.
     *
     * @since 0.0.1
     */
    @Parameter(property = "cq.nativeSupported", defaultValue = "true")
    boolean nativeSupported;

    /**
     * How to format simple XML elements ({@code <elem/>}) - with or without space before the slash.
     *
     * @since 0.38.0
     */
    @Parameter(property = "cq.simpleElementWhitespace", defaultValue = "EMPTY")
    SimpleElementWhitespace simpleElementWhitespace;

    List<ArtifactModel<?>> models;
    ArtifactModel<?> model;

    Model extensionsModel;
    Path extensionsPomPath;
    Configuration cfg;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        basePath = getRootModuleDirectory();
        if (extensionsDir == null) {
            extensionsDir = nativeSupported ? basePath.resolve(CQ_EXTENSIONS_DIR).toFile()
                    : basePath.resolve(CQ_EXTENSIONS_JVM_DIR).toFile();
        }
        extensionsPath = extensionsDir.toPath();

        final CqCatalog cqCatalog = new CqCatalog(Flavor.camel);
        this.models = cqCatalog.filterModels(artifactIdBase).collect(Collectors.toList());
        final List<ArtifactModel<?>> primaryModels = cqCatalog.primaryModel(artifactIdBase);
        switch (primaryModels.size()) {
        case 0:
            throw new IllegalStateException("Could not find name " + artifactIdBase + " in Camel catalog");
        default:
            this.model = primaryModels.get(0);
            break;
        }

        if (artifactIdPrefix == null) {
            artifactIdPrefix = "";
        }
        artifactId = artifactIdPrefix == null || artifactIdPrefix.isEmpty() ? artifactIdBase
                : artifactIdPrefix + artifactIdBase;

        if (nameBase == null) {
            nameBase = model.getTitle();
            if (nameBase == null) {
                throw new MojoFailureException("Name not found for " + artifactIdBase);
            }
        }
        if (namePrefix == null) {
            namePrefix = "";
        }

        extensionsPomPath = this.extensionsPath.resolve("pom.xml");
        extensionsModel = CqCommonUtils.readPom(extensionsPomPath, getCharset());
        this.groupId = getGroupId(extensionsModel);
        this.version = CqUtils.getVersion(extensionsModel);

        cfg = CqUtils.getTemplateConfig(basePath, CqUtils.DEFAULT_TEMPLATES_URI_BASE, templatesUriBase,
                encoding);
        doExecute();

    }

    void doExecute() {
        final TemplateParams.Builder templateParams = getTemplateParams();
        generateItest(cfg, templateParams);
    }

    Path getExtensionProjectBaseDir() {
        return extensionsPath.resolve(artifactIdBase);
    }

    PomTransformer pomTransformer(Path basePomXml) {
        return new PomTransformer(basePomXml, getCharset(), simpleElementWhitespace);
    }

    TemplateParams.Builder getTemplateParams() {
        final TemplateParams.Builder templateParams = TemplateParams.builder();

        templateParams.artifactId(artifactId);
        templateParams.artifactIdPrefix(artifactIdPrefix);
        templateParams.artifactIdBase(artifactIdBase);
        templateParams.groupId(groupId);
        templateParams.version(version);

        templateParams.namePrefix(namePrefix);
        templateParams.nameBase(nameBase);
        templateParams.nameSegmentDelimiter(nameSegmentDelimiter);

        templateParams.javaPackageBase(javaPackageBase != null ? javaPackageBase
                : CqUtils.getJavaPackage(templateParams.getGroupId(), javaPackageInfix, artifactId));

        templateParams.modelParams(model);

        templateParams.nativeSupported(nativeSupported);
        templateParams.unlisted(!nativeSupported);
        templateParams.models(models);

        return templateParams;
    }

    void generateItest(Configuration cfg, TemplateParams.Builder model) {
        if (itestParent == null) {
            itestParent = basePath.resolve(nativeSupported ? CQ_INTEGRATION_TESTS_PATH : CQ_INTEGRATION_TESTS_JVM_PATH)
                    .toFile();
        }
        final Path itestParentPath = itestParent.toPath();
        final Path itestDir = itestParentPath.getParent().resolve(artifactIdBase);

        final Model itestParent = CqCommonUtils.readPom(itestParentPath, getCharset());
        if (!"pom".equals(itestParent.getPackaging())) {
            throw new RuntimeException(
                    "Can add an extension integration test only under a project with packagin 'pom'; found: "
                            + itestParent.getPackaging() + " in " + itestParentPath);
        }
        getLog().info(String.format("Adding module [%s] to [%s]", itestDir.getFileName().toString(), itestParentPath));
        pomTransformer(itestParentPath).transform(Transformation.addModule(itestDir.getFileName().toString()));
        PomSorter.sortModules(itestParentPath);

        model.itestParentGroupId(getGroupId(itestParent));
        model.itestParentArtifactId(itestParent.getArtifactId());
        model.itestParentVersion(CqUtils.getVersion(itestParent));
        model.itestParentRelativePath("../pom.xml");

        final Path itestPomPath = itestDir.resolve("pom.xml");
        evalTemplate(cfg, "integration-test-pom.xml", itestPomPath, model.build());

        final Set<String> extensionArtifactIds = findExtensions().map(e -> "camel-quarkus-" + e.getArtifactIdBase())
                .collect(Collectors.toSet());
        new PomTransformer(itestPomPath, getCharset(), simpleElementWhitespace)
                .transform(
                        FormatPomsMojo
                                .updateTestVirtualDependencies(gavtcs -> extensionArtifactIds.contains(gavtcs.getArtifactId())),
                        Transformation.keepFirst(CqCommonUtils.virtualDepsCommentXPath(), true));

        if (nativeSupported) {
            evalTemplate(cfg, "integration-test-application.properties",
                    itestDir.resolve("src/main/resources/application.properties"), model.build());
        }

        final String artifactIdBaseCapCamelCase = CqUtils.toCapCamelCase(model.getArtifactIdBase());
        final Path testResourcePath = itestDir.resolve("src/main/java/" + model.getJavaPackageBasePath()
                + "/it/" + artifactIdBaseCapCamelCase + "Resource.java");
        evalTemplate(cfg, "TestResource.java", testResourcePath, model.build());
        final Path testClassDir = itestDir
                .resolve("src/test/java/" + model.getJavaPackageBasePath() + "/it");
        evalTemplate(cfg, "Test.java", testClassDir.resolve(artifactIdBaseCapCamelCase + "Test.java"),
                model.build());
        if (nativeSupported) {
            evalTemplate(cfg, "IT.java", testClassDir.resolve(artifactIdBaseCapCamelCase + "IT.java"),
                    model.build());
        }

    }

    static String getGroupId(Model basePom) {
        return basePom.getGroupId() != null ? basePom.getGroupId()
                : basePom.getParent() != null && basePom.getParent().getGroupId() != null
                        ? basePom.getParent().getGroupId()
                        : null;
    }

    public void evalTemplate(Configuration cfg, String templateUri, Path dest, TemplateParams model) {
        CqUtils.evalTemplate(cfg, templateUri, dest, model, m -> getLog().info(m));
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

}
