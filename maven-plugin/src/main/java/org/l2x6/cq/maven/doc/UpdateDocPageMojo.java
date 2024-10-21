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
package org.l2x6.cq.maven.doc;

import freemarker.template.Configuration;
import freemarker.template.TemplateMethodModelEx;
import freemarker.template.TemplateModelException;
import io.quarkus.annotation.processor.documentation.config.merger.JavadocMerger;
import io.quarkus.annotation.processor.documentation.config.merger.JavadocRepository;
import io.quarkus.annotation.processor.documentation.config.merger.MergedModel;
import io.quarkus.annotation.processor.documentation.config.merger.MergedModel.ConfigRootKey;
import io.quarkus.annotation.processor.documentation.config.merger.ModelMerger;
import io.quarkus.annotation.processor.documentation.config.model.AbstractConfigItem;
import io.quarkus.annotation.processor.documentation.config.model.ConfigProperty;
import io.quarkus.annotation.processor.documentation.config.model.ConfigProperty.PropertyPath;
import io.quarkus.annotation.processor.documentation.config.model.ConfigRoot;
import io.quarkus.annotation.processor.documentation.config.model.Extension;
import io.quarkus.annotation.processor.documentation.config.model.JavadocElements.JavadocElement;
import io.quarkus.annotation.processor.documentation.config.util.Types;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.Duration;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Model;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.l2x6.cq.common.CqCommonUtils;
import org.l2x6.cq.maven.CqUtils;
import org.l2x6.cq.maven.doc.processor.AppendNewLinePostProcessor;
import org.l2x6.cq.maven.doc.processor.AsciiDocFile;
import org.l2x6.cq.maven.doc.processor.DocumentationPostProcessor;
import org.l2x6.cq.maven.doc.processor.SectionIdPostProcessor;

/**
 * Updates the given extension's documentation page in the {@code docs} module based on data in the current module.
 * <p>
 * Intended primarily for Quarkiverse CXF. Note that there is a similar plugin in Camel Quarkus.
 *
 * @since 3.4.0
 */
@Mojo(name = UpdateDocPageMojo.UPDATE_DOC_PAGE, threadSafe = true)
public class UpdateDocPageMojo extends AbstractDocGeneratorMojo {
    static final String UPDATE_DOC_PAGE = "update-doc-page";
    private static final String TOOLTIP_MACRO = "tooltip:%s[%s]";
    private static final String MORE_INFO_ABOUT_TYPE_FORMAT = "link:#%s[icon:question-circle[title=More information about the %s format]]";

    private static final DocumentationPostProcessor[] documentationPostProcessors = {
            new AppendNewLinePostProcessor(),
            new SectionIdPostProcessor()
    };

    /**
     * If {@code true}, the this mojo is not executed; otherwise it is executed.
     *
     * @since 3.4.0
     */
    @Parameter(defaultValue = "false", property = "cq.update-doc-page.skip")
    boolean skip = false;

    /**
     * A regular expression matching own {@code https://} links, to replace with {@code xref:$1.adoc}.
     * <p>
     * Example: {@code \Qlink:http\Es?\Q://camel.apache.org/camel-quarkus/latest/\E([^\[]+).html}
     *
     * @since 4.4.9
     */
    @Parameter(property = "cq.ownLinkPattern")
    String ownLinkPattern;

    /**
     * A list of regular expressions to match against configration options. All matching options will not appear in
     * the configuration section of the extension page.
     *
     * @since 4.4.9
     */
    @Parameter(property = "cq.configOptionExcludes")
    List<String> configOptionExcludes;

    /**
     * A list of if form {@code regEx/replacement} where {@code regEx} is a regular expression and {@code replacement}
     * is a string to replace for the matches in configuration option descriptions. The default delimiter {@code /} can
     * be changed via {@link #descriptionReplacementDelimiter}.
     *
     * @since 4.4.10
     */
    @Parameter(property = "cq.descriptionReplacements")
    List<String> descriptionReplacements;

    /**
     * A delimiter for {@link #descriptionReplacements}.
     *
     * @since 4.4.10
     */
    @Parameter(defaultValue = "/", property = "cq.descriptionReplacementDelimiter")
    String descriptionReplacementDelimiter;

    @Parameter(defaultValue = "${project}", readonly = true)
    MavenProject project;

    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    private MavenSession session;

    /** {@inheritDoc} */
    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("Skipping per user request");
            return;
        }
        final Path currentModuleDir = baseDir.toPath();
        final Path runtimeModuleDir;
        final Path deploymentModuleDir;
        final MavenProject runtimeProject;
        if ("runtime".equals(currentModuleDir.getFileName().toString())) {
            deploymentModuleDir = currentModuleDir.getParent().resolve("deployment");
            if (session.getAllProjects().stream()
                    .anyMatch(p -> p.getBasedir().toPath().equals(deploymentModuleDir))) {
                getLog().info("Skipping the execution in " + project.getArtifactId() + " and postponing it to "
                        + project.getArtifactId() + "-deployment");
                return;
            }
            runtimeModuleDir = currentModuleDir;
            runtimeProject = project;
        } else if ("deployment".equals(currentModuleDir.getFileName().toString())) {
            runtimeModuleDir = currentModuleDir.getParent().resolve("runtime");
            deploymentModuleDir = currentModuleDir;

            runtimeProject = session.getAllProjects().stream()
                    .filter(p -> p.getBasedir().toPath().equals(runtimeModuleDir))
                    .findFirst()
                    .orElseGet(
                            () -> new MavenProject(CqCommonUtils.readPom(runtimeModuleDir.resolve("pom.xml"), getCharset())));

        } else {
            getLog().info("Skipping a module that is nether Quarkus extension runtime nor deployment module");
            return;
        }
        final Pattern ownLinkRe = ownLinkPattern != null ? Pattern.compile(ownLinkPattern) : null;
        final List<Pattern> configOptionExcludeRes = new ArrayList<>();
        if (configOptionExcludes != null) {
            for (String pattern : configOptionExcludes) {
                configOptionExcludeRes.add(Pattern.compile(pattern));
            }
        }
        final List<Map.Entry<Pattern, String>> descriptionReplacementRes = new ArrayList<>();
        if (descriptionReplacements != null) {
            for (String entry : descriptionReplacements) {
                int i = entry.indexOf(descriptionReplacementDelimiter);
                if (i < 0) {
                    throw new IllegalStateException("descriptionReplacements '" + entry + "' sould contain delimiter '"
                            + descriptionReplacementDelimiter + "'");
                }
                final String pattern = entry.substring(0, i);
                final String replacement = entry.substring(i + descriptionReplacementDelimiter.length());
                descriptionReplacementRes.add(new AbstractMap.SimpleImmutableEntry<>(Pattern.compile(pattern), replacement));
            }
        }

        final Configuration cfg = CqUtils.getTemplateConfig(runtimeModuleDir, DEFAULT_TEMPLATES_URI_BASE, templatesUriBase,
                getCharset().toString());

        final Map<String, Object> model = new HashMap<>();
        final String artifactId = runtimeProject.getArtifactId();
        final Path docPagePath = getMultiModuleProjectDirectoryPath()
                .resolve("docs/modules/ROOT/pages/reference/extensions/" + artifactId + ".adoc");
        model.put("artifactId", artifactId);
        model.put("groupId", runtimeProject.getGroupId());
        model.put("since", getRequiredProperty(runtimeProject, "cq.since"));
        model.put("name", extensionName(runtimeProject.getModel()));
        model.put("status", org.l2x6.cq.common.ExtensionStatus.valueOf(runtimeProject.getProperties()
                .getProperty("quarkus.metadata.status", org.l2x6.cq.common.ExtensionStatus.stable.name())).getCapitalized());
        final boolean deprecated = Boolean
                .parseBoolean(runtimeProject.getProperties().getProperty("quarkus.metadata.deprecated", "false"));
        model.put("deprecated", deprecated);
        model.put("unlisted",
                Boolean.parseBoolean(runtimeProject.getProperties().getProperty("quarkus.metadata.unlisted", "false")));
        model.put("intro",
                loadSection(runtimeModuleDir, "intro.adoc", getCharset(), artifactId, runtimeProject.getDescription()));
        model.put("standards", loadSection(runtimeModuleDir, "standards.adoc", getCharset(), artifactId, null));
        model.put("usage", loadSection(runtimeModuleDir, "usage.adoc", getCharset(), artifactId, null));
        model.put("usageAdvanced", loadSection(runtimeModuleDir, "usage-advanced.adoc", getCharset(), artifactId, null));
        model.put("configuration", loadSection(runtimeModuleDir, "configuration.adoc", getCharset(), artifactId, null));
        model.put("limitations", loadSection(runtimeModuleDir, "limitations.adoc", getCharset(), artifactId, null));
        final List<ConfigItem> configOptions = listConfigOptions(
                runtimeModuleDir,
                deploymentModuleDir,
                ownLinkRe,
                configOptionExcludeRes,
                descriptionReplacementRes,
                artifactId);
        model.put("configOptions", configOptions);
        model.put("hasDurationOption", configOptions.stream().anyMatch(ConfigItem::isTypeDuration));
        model.put("hasMemSizeOption", configOptions.stream().anyMatch(ConfigItem::isTypeMemSize));
        model.put("toAnchor", new TemplateMethodModelEx() {
            @Override
            public Object exec(List arguments) throws TemplateModelException {
                if (arguments.size() != 1) {
                    throw new TemplateModelException("Wrong argument count in toAnchor()");
                }
                String string = String.valueOf(arguments.get(0));
                string = Normalizer.normalize(string, Normalizer.Form.NFKC)
                        .replaceAll("[àáâãäåāąă]", "a")
                        .replaceAll("[çćčĉċ]", "c")
                        .replaceAll("[ďđð]", "d")
                        .replaceAll("[èéêëēęěĕė]", "e")
                        .replaceAll("[ƒſ]", "f")
                        .replaceAll("[ĝğġģ]", "g")
                        .replaceAll("[ĥħ]", "h")
                        .replaceAll("[ìíîïīĩĭįı]", "i")
                        .replaceAll("[ĳĵ]", "j")
                        .replaceAll("[ķĸ]", "k")
                        .replaceAll("[łľĺļŀ]", "l")
                        .replaceAll("[ñńňņŉŋ]", "n")
                        .replaceAll("[òóôõöøōőŏœ]", "o")
                        .replaceAll("[Þþ]", "p")
                        .replaceAll("[ŕřŗ]", "r")
                        .replaceAll("[śšşŝș]", "s")
                        .replaceAll("[ťţŧț]", "t")
                        .replaceAll("[ùúûüūůűŭũų]", "u")
                        .replaceAll("[ŵ]", "w")
                        .replaceAll("[ýÿŷ]", "y")
                        .replaceAll("[žżź]", "z")
                        .replaceAll("[æ]", "ae")
                        .replaceAll("[ÀÁÂÃÄÅĀĄĂ]", "A")
                        .replaceAll("[ÇĆČĈĊ]", "C")
                        .replaceAll("[ĎĐÐ]", "D")
                        .replaceAll("[ÈÉÊËĒĘĚĔĖ]", "E")
                        .replaceAll("[ĜĞĠĢ]", "G")
                        .replaceAll("[ĤĦ]", "H")
                        .replaceAll("[ÌÍÎÏĪĨĬĮİ]", "I")
                        .replaceAll("[Ĵ]", "J")
                        .replaceAll("[Ķ]", "K")
                        .replaceAll("[ŁĽĹĻĿ]", "L")
                        .replaceAll("[ÑŃŇŅŊ]", "N")
                        .replaceAll("[ÒÓÔÕÖØŌŐŎ]", "O")
                        .replaceAll("[ŔŘŖ]", "R")
                        .replaceAll("[ŚŠŞŜȘ]", "S")
                        .replaceAll("[ÙÚÛÜŪŮŰŬŨŲ]", "U")
                        .replaceAll("[Ŵ]", "W")
                        .replaceAll("[ÝŶŸ]", "Y")
                        .replaceAll("[ŹŽŻ]", "Z")
                        .replaceAll("[ß]", "ss");
                string = string.replace('.', '-');

                // Apostrophes.
                string = string.replaceAll("([a-z])'s([^a-z])", "$1s$2");
                // Allow only letters, -, _, .
                string = string.replaceAll("[^\\w-_.]", "-").replaceAll("-{2,}", "-");
                // Get rid of any - at the start and end.
                string = string.replaceAll("-+$", "").replaceAll("^-+", "");

                return string.toLowerCase();
            }
        });
        evalTemplate(getCharset(), docPagePath, cfg, model, "extension-doc-page.adoc", "//");
    }

    public static String extensionName(Model project) {
        return project.getProperties().getProperty("cq.name", CqCommonUtils.getNameBase(project.getName()));
    }

    private static String getRequiredProperty(MavenProject runtimeProject, String key) {
        Object val = runtimeProject.getProperties().get(key);
        if (val == null) {
            throw new IllegalStateException(
                    "Could not find required property " + key + " in module " + runtimeProject.getArtifactId());
        }
        return String.valueOf(val);
    }

    static void evalTemplate(final Charset charset, final Path docPagePath, final Configuration cfg,
            final Map<String, Object> model, String template, String commentMarker) {
        try {
            Files.createDirectories(docPagePath.getParent());
        } catch (IOException e) {
            throw new RuntimeException("Could not create directories " + docPagePath.getParent(), e);
        }
        String pageText = commentMarker
                + " Do not edit directly!\n"
                + commentMarker
                + " This file was generated by cq-maven-plugin:" + UPDATE_DOC_PAGE + "\n"
                + evalTemplate(cfg, template, model, new StringWriter());
        try {
            String oldContent = "";
            if (Files.exists(docPagePath)) {
                oldContent = Files.readString(docPagePath, StandardCharsets.UTF_8);
            }
            if (!oldContent.equals(pageText)) {
                Files.write(docPagePath, pageText.getBytes(charset));
            }
        } catch (IOException e) {
            throw new RuntimeException("Could not write to " + docPagePath, e);
        }
    }

    private static String loadSection(
            Path basePath,
            String fileName,
            Charset charset,
            String artifactId,
            String defaultValue) {
        Path p = basePath.resolve("src/main/doc/" + fileName);
        if (Files.exists(p)) {
            AsciiDocFile file = new AsciiDocFile(p, artifactId, charset);
            for (DocumentationPostProcessor processor : documentationPostProcessors) {
                processor.process(file);
            }
            return file.getContent();
        } else {
            return defaultValue;
        }
    }

    static List<ConfigItem> listConfigOptions(
            Path runtimeModuleDir,
            Path deploymentModuleDir,
            Pattern ownLinkRe,
            List<Pattern> configOptionExcludeRes,
            List<Entry<Pattern, String>> descriptionReplacementRes,
            String artifactIdBase) {

        final List<ConfigProperty> result = new ArrayList<>();

        final List<Path> targetDirectories = Stream.of(runtimeModuleDir, deploymentModuleDir)
                .map(p -> p.resolve("target"))
                .filter(Files::isDirectory)
                .collect(Collectors.toList());

        final JavadocRepository javadocRepository = JavadocMerger.mergeJavadocElements(targetDirectories);
        final MergedModel mergedModel = ModelMerger.mergeModel(targetDirectories);
        for (Entry<Extension, Map<ConfigRootKey, ConfigRoot>> extensionConfigRootsEntry : mergedModel.getConfigRoots()
                .entrySet()) {
            for (Entry<ConfigRootKey, ConfigRoot> configRootEntry : extensionConfigRootsEntry.getValue().entrySet()) {
                final ConfigRoot configRoot = configRootEntry.getValue();
                for (AbstractConfigItem configItem : configRoot.getItems()) {
                    if (configItem instanceof ConfigProperty) {
                        result.add((ConfigProperty) configItem);
                    }
                }
            }
        }
        for (Entry<String, ConfigRoot> configRootEntry : mergedModel.getConfigRootsInSpecificFile().entrySet()) {
            final ConfigRoot configRoot = configRootEntry.getValue();
            for (AbstractConfigItem configItem : configRoot.getItems()) {
                if (configItem instanceof ConfigProperty) {
                    result.add((ConfigProperty) configItem);
                }
            }
        }

        Collections.sort(result);

        Function<String, String> descriptionTransformer = origDescription -> {
            String newVal = origDescription;
            if (!descriptionReplacementRes.isEmpty() || ownLinkRe != null) {
                if (!descriptionReplacementRes.isEmpty()) {
                    for (Entry<Pattern, String> en : descriptionReplacementRes) {
                        newVal = en.getKey().matcher(newVal).replaceAll(en.getValue());
                    }
                }
                if (ownLinkRe != null) {
                    newVal = ownLinkRe.matcher(newVal).replaceAll("xref:$1.adoc");
                }
            }
            return newVal;
        };

        return result.stream()
                .map(cp -> ConfigItem.of(cp, javadocRepository, descriptionTransformer, artifactIdBase))
                .filter(i -> configOptionExcludeRes.stream().noneMatch(p -> p.matcher(i.getKey()).find()))
                .collect(Collectors.toList());
    }

    public static class ConfigItem {

        private final String key;
        private final String illustration;
        private final String configDoc;
        private final String type;
        private final boolean typeDuration;
        private final boolean typeMemSize;
        private final String defaultValue;
        private final boolean optional;
        private final boolean deprecated;
        private final String since;
        private final String environmentVariable;

        public static ConfigItem of(ConfigProperty configDocItem, JavadocRepository javadocRepository,
                Function<String, String> descriptionTransformer, String artifactIdBase) {
            final Optional<JavadocElement> javadoc = javadocRepository
                    .getElement(configDocItem.getSourceType().name(), configDocItem.getSourceName());
            final PropertyPath itemPath = configDocItem.getPath();
            if (javadoc.isEmpty()) {
                throw new IllegalStateException("No JavaDoc for " + itemPath.property() + " alias "
                        + configDocItem.getSourceType().name() + "#" + configDocItem.getSourceName());
            }
            final String illustration = configDocItem.getPhase().isFixedAtBuildTime() ? "icon:lock[title=Fixed at build time]"
                    : "";
            final TypeInfo typeInfo = typeContent(configDocItem, javadocRepository, true, artifactIdBase);
            return new ConfigItem(
                    itemPath.property(),
                    illustration,
                    descriptionTransformer.apply(javadoc.get().description()),
                    typeInfo.description,
                    typeInfo.isDuration,
                    typeInfo.isMemSize,
                    configDocItem.getDefaultValue(),
                    configDocItem.isOptional(),
                    configDocItem.isDeprecated(),
                    javadoc.get().since(),
                    itemPath.environmentVariable());
        }

        static TypeInfo typeContent(ConfigProperty configProperty, JavadocRepository javadocRepository,
                boolean enableEnumTooltips, String artifactIdBase) {
            String typeContent = "";

            if (configProperty.isEnum() && enableEnumTooltips) {
                typeContent = joinEnumValues(configProperty, javadocRepository);
            } else {
                typeContent = "`" + configProperty.getTypeDescription() + "`";
                if (configProperty.getJavadocSiteLink() != null) {
                    typeContent = String.format("link:%s[%s]", configProperty.getJavadocSiteLink(), typeContent);
                }
            }
            if (configProperty.isList()) {
                typeContent = "List of `" + typeContent + "`";
            }

            boolean isDuration = false;
            boolean isMemSize = false;
            if (Duration.class.getName().equals(configProperty.getType())) {
                typeContent += " " + String.format(MORE_INFO_ABOUT_TYPE_FORMAT,
                        "duration-note-anchor-" + artifactIdBase, Duration.class.getSimpleName());
                isDuration = true;
            } else if (Types.MEMORY_SIZE_TYPE.equals(configProperty.getType())) {
                typeContent += " " + String.format(MORE_INFO_ABOUT_TYPE_FORMAT,
                        "memory-size-note-anchor-" + artifactIdBase, "MemorySize");
                isMemSize = true;
            }

            return new TypeInfo(typeContent, isDuration, isMemSize);
        }

        static class TypeInfo {
            final String description;
            final boolean isDuration;
            final boolean isMemSize;

            TypeInfo(String description, boolean isDuration, boolean isMemSize) {
                this.description = description;
                this.isDuration = isDuration;
                this.isMemSize = isMemSize;
            }
        }

        static String joinEnumValues(ConfigProperty configProperty, JavadocRepository javadocRepository) {
            return configProperty.getEnumAcceptedValues().values().entrySet().stream()
                    .map(e -> {
                        Optional<JavadocElement> javadocElement = javadocRepository.getElement(configProperty.getType(),
                                e.getKey());
                        if (javadocElement.isEmpty()) {
                            return "`" + e.getValue().configValue() + "`";
                        }
                        return String.format(TOOLTIP_MACRO, e.getValue().configValue(),
                                cleanTooltipContent(javadocElement.get().description()));
                    })
                    .collect(Collectors.joining(", "));
        }

        /**
         * Note that this is extremely brittle. Apparently, colons breaks the tooltips but if escaped with \, the \
         * appears in
         * the
         * output.
         * <p>
         * We should probably have some warnings/errors as to what is accepted in enum Javadoc.
         */
        static String cleanTooltipContent(String tooltipContent) {
            return tooltipContent.replace("<p>", "").replace("</p>", "").replace("\n+\n", " ").replace("\n", " ")
                    .replace(":", "\\:").replace("[", "\\]").replace("]", "\\]");
        }

        public ConfigItem(
                String key,
                String illustration,
                String configDoc,
                String type,
                boolean typeDuration,
                boolean typeMemSize,
                String defaultValue,
                boolean optional,
                boolean deprecated,
                String since,
                String environmentVariable) {
            this.key = key;
            this.illustration = illustration;
            this.configDoc = configDoc;
            this.type = type;
            this.typeDuration = typeDuration;
            this.typeMemSize = typeMemSize;
            this.defaultValue = defaultValue;
            this.optional = optional;
            this.deprecated = deprecated;
            this.since = since;
            this.environmentVariable = environmentVariable;
        }

        public String getKey() {
            return key;
        }

        public String getIllustration() {
            return illustration;
        }

        public String getConfigDoc() {
            return configDoc;
        }

        public String getType() {
            return type;
        }

        public boolean isTypeDuration() {
            return typeDuration;
        }

        public boolean isTypeMemSize() {
            return typeMemSize;
        }

        public String getDefaultValue() {
            return defaultValue;
        }

        public boolean isOptional() {
            return optional;
        }

        public String getSince() {
            return since;
        }

        public String getEnvironmentVariable() {
            return environmentVariable;
        }

        public boolean isDeprecated() {
            return deprecated;
        }
    }
}
