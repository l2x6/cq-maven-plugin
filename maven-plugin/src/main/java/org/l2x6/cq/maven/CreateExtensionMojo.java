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
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.camel.tooling.model.ArtifactModel;
import org.apache.maven.model.Model;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.shared.utils.io.DirectoryScanner;
import org.l2x6.cq.maven.TemplateParams.Builder;
import org.l2x6.cq.maven.TemplateParams.ExtensionStatus;
import org.l2x6.maven.utils.Gavtcs;
import org.l2x6.maven.utils.PomTransformer;
import org.l2x6.maven.utils.PomTransformer.Transformation;

import freemarker.template.Configuration;

/**
 * Scaffolds a new Camel Quarkus extension.
 */
@Mojo(name = "create", requiresProject = true, inheritByDefault = false)
public class CreateExtensionMojo extends CreateTestMojo {
    static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("@\\{([^\\}]+)\\}");
    static final String QUOTED_DOLLAR = Matcher.quoteReplacement("$");

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
     * If {@code true} the mojo creates some empty directories for user's convenience, such as {@code src/main/java/...}
     * for
     * user's convenience. Otherwise, these empty directories are not created. Setting this option to {@code false}
     * might be useful when testing.
     *
     * @since 0.0.9
     */
    @Parameter(property = "cq.createConvenienceDirs", defaultValue = "true")
    boolean createConvenienceDirs = true;

    /**
     * A list of directory paths relative to the current module's {@code baseDir} containing Maven modules in which
     * virtual dependencies should be updated. After running this mojo, the selected {@code pom.xml} files will depend
     * on artifacts with type {@code pom} and scope {@code test} of all runtime extension modules available in the
     * current source tree.
     *
     * @since 0.18.0
     */
    @Parameter
    List<DirectoryScanner> updateVirtualDependenciesAllExtensions;

    @Override
    void doExecute() {

        runtimeBomPath = runtimeBom.toPath();

        if (this.description == null) {
            this.description = model.getDescription();
        }
        if (this.keywords == null) {
            this.keywords = Collections.emptyList();
        }
        if (this.guideUrl == null) {
            this.guideUrl = CqUtils.extensionDocUrl(artifactIdBase);
        }
        if (this.categories == null || this.categories.isEmpty()) {
            this.categories = CqUtils.DEFAULT_CATEGORIES;
        }

        final TemplateParams.Builder templateParams = getTemplateParams();

        generateExtensionProjects(cfg, templateParams);
        if (!extensionsModel.getModules().contains(artifactIdBase)) {
            getLog().info(String.format("Adding module [%s] to [%s]", artifactIdBase, extensionsPomPath));
            pomTransformer(extensionsPomPath).transform(Transformation.addModule(artifactIdBase));
        }
        PomSorter.sortModules(extensionsPomPath);

        if (runtimeBomPath != null) {
            List<PomTransformer.Transformation> transformations = new ArrayList<PomTransformer.Transformation>();
            getLog().info(
                    String.format("Adding [%s] to dependencyManagement in [%s]", templateParams.getArtifactId(),
                            runtimeBomPath));
            transformations
                    .add(Transformation.addManagedDependency(templateParams.getGroupId(), templateParams.getArtifactId(),
                            templateParams.getBomEntryVersion()));

            final String aId = templateParams.getArtifactId() + "-deployment";
            getLog().info(String.format("Adding [%s] to dependencyManagement in [%s]", aId, runtimeBomPath));
            transformations
                    .add(Transformation.addManagedDependency(templateParams.getGroupId(), aId,
                            templateParams.getBomEntryVersion()));

            for (Gavtcs gavtcs : templateParams.getAdditionalRuntimeDependencies()) {
                getLog().info(String.format("Adding [%s] to dependencyManagement in [%s]", gavtcs, runtimeBomPath));
                transformations.add(Transformation.addManagedDependency(gavtcs));
            }
            pomTransformer(runtimeBomPath).transform(transformations);
            PomSorter.sortDependencyManagement(runtimeBomPath);
        }

        generateItest(cfg, templateParams);

        final Set<Gavtcs> allExtensions = findExtensions()
                .map(e -> new Gavtcs("org.apache.camel.quarkus", "camel-quarkus-" + e.getArtifactIdBase(), null))
                .collect(Collectors.toSet());
        FormatPomsMojo.updateVirtualDependenciesAllExtensions(updateVirtualDependenciesAllExtensions, allExtensions, getCharset(), simpleElementWhitespace);

    }

    TemplateParams.Builder getTemplateParams() {
        Builder templateParams = super.getTemplateParams();
        templateParams.quarkusVersion(QUARKUS_VERSION_POM_EXPR);
        templateParams.bomEntryVersion(bomEntryVersion.replace('@', '$'));
        templateParams.additionalRuntimeDependencies(getAdditionalRuntimeDependencies());
        templateParams.runtimeBomPathSet(runtimeBomPath != null);

        templateParams.guideUrl(guideUrl);
        templateParams.categories(categories);

        return templateParams;
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

    Path getExtensionRuntimeBaseDir() {
        return getExtensionProjectBaseDir().resolve("runtime");
    }

    Path getExtensionDeploymentBaseDir() {
        return getExtensionProjectBaseDir().resolve("deployment");
    }

    void generateExtensionProjects(Configuration cfg, TemplateParams.Builder templateParams) {
        final Path extParentPomPath = getExtensionProjectBaseDir().resolve("pom.xml");
        evalTemplate(cfg, "parent-pom.xml", extParentPomPath, templateParams.build());

        final Path extensionRuntimeBaseDir = getExtensionRuntimeBaseDir();
        if (createConvenienceDirs) {
            createDirectories(
                    extensionRuntimeBaseDir.resolve("src/main/java").resolve(templateParams.getJavaPackageBasePath()));
            // TODO: createDirectories(extensionRuntimeBaseDir.resolve("src/main/doc"));
        }
        evalTemplate(cfg, "runtime-pom.xml", extensionRuntimeBaseDir.resolve("pom.xml"),
                templateParams.build());
        final boolean deprecated = models.stream().anyMatch(ArtifactModel::isDeprecated);

        final TemplateParams quarkusExtensionYamlParams = CqUtils.quarkusExtensionYamlParams(models, artifactIdBase, nameBase,
                description, keywords, !nativeSupported, deprecated, nativeSupported, ExtensionStatus.of(nativeSupported),
                runtimeBomPath.getParent().getParent().getParent(), getLog(), new ArrayList<>());
        final Path metaInfDir = extensionRuntimeBaseDir.resolve("src/main/resources/META-INF");
        try {
            Files.createDirectories(metaInfDir);
        } catch (IOException e) {
            throw new RuntimeException("Could not create " + metaInfDir, e);
        }
        evalTemplate(cfg, "quarkus-extension.yaml", metaInfDir.resolve("quarkus-extension.yaml"), quarkusExtensionYamlParams);

        evalTemplate(cfg, "deployment-pom.xml", getExtensionDeploymentBaseDir().resolve("pom.xml"),
                templateParams.build());
        final Path processorPath = getExtensionDeploymentBaseDir()
                .resolve("src/main/java")
                .resolve(templateParams.getJavaPackageBasePath())
                .resolve("deployment")
                .resolve(CqUtils.toCapCamelCase(templateParams.getArtifactIdBase()) + "Processor.java");
        evalTemplate(cfg, "Processor.java", processorPath, templateParams.build());
    }

    private void createDirectories(final Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new RuntimeException("Could not create directories " + dir, e);
        }
    }

    static String getGroupId(Model basePom) {
        return basePom.getGroupId() != null ? basePom.getGroupId()
                : basePom.getParent() != null && basePom.getParent().getGroupId() != null
                        ? basePom.getParent().getGroupId()
                        : null;
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
                    final Field field = getFiled(fieldName);
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

    public Field getFiled(final String fieldName) throws NoSuchFieldException {
        try {
            return this.getClass().getDeclaredField(fieldName);
        } catch (NoSuchFieldException e) {
            return this.getClass().getSuperclass().getDeclaredField(fieldName);
        } catch (SecurityException e) {
            throw new RuntimeException(e);
        }
    }

}
