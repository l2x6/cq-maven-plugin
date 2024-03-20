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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import freemarker.template.Configuration;
import freemarker.template.TemplateMethodModelEx;
import freemarker.template.TemplateModelException;
import io.quarkus.annotation.processor.Constants;
import io.quarkus.annotation.processor.generate_doc.ConfigDocItem;
import io.quarkus.annotation.processor.generate_doc.ConfigDocKey;
import io.quarkus.annotation.processor.generate_doc.DocGeneratorUtil;
import io.quarkus.annotation.processor.generate_doc.FsMap;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
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
        model.put("configOptions",
                listConfigOptions(runtimeModuleDir, deploymentModuleDir, multiModuleProjectDirectory.toPath(), ownLinkRe,
                        configOptionExcludeRes,
                        descriptionReplacementRes));
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
            Path multiModuleProjectDirectory, Pattern ownLinkRe,
            List<Pattern> configOptionExcludeRes, List<Entry<Pattern, String>> descriptionReplacementRes) {

        final List<String> configRootClasses = loadConfigRoots(runtimeModuleDir, deploymentModuleDir);
        if (configRootClasses.isEmpty()) {
            return Collections.emptyList();
        }
        final Path configRootsModelsDir = multiModuleProjectDirectory
                .resolve("target/asciidoc/generated/config/all-configuration-roots-generated-doc");
        if (!Files.exists(configRootsModelsDir)) {
            throw new IllegalStateException("You should run " + UpdateDocPageMojo.class.getSimpleName()
                    + " after compilation with io.quarkus.annotation.processor.ExtensionAnnotationProcessor");
        }
        final FsMap configRootsModels = new FsMap(configRootsModelsDir);

        final ObjectMapper mapper = new ObjectMapper();
        final List<ConfigDocItem> configDocItems = new ArrayList<>();
        for (String configRootClass : configRootClasses) {
            final String rawModel = configRootsModels.get(configRootClass);
            if (rawModel == null) {
                throw new IllegalStateException("Could not find " + configRootClass + " in " + configRootsModelsDir);
            }
            try {
                final List<ConfigDocItem> items = mapper.readValue(rawModel, Constants.LIST_OF_CONFIG_ITEMS_TYPE_REF);
                configDocItems.addAll(items);
            } catch (JsonProcessingException e) {
                throw new RuntimeException("Could not parse " + rawModel, e);
            }
        }
        if (!descriptionReplacementRes.isEmpty() || ownLinkRe != null) {
            for (ConfigDocItem configDocItem : configDocItems) {
                ConfigDocKey k = configDocItem.getConfigDocKey();
                String newVal = k.getConfigDoc();
                if (!descriptionReplacementRes.isEmpty()) {
                    for (Entry<Pattern, String> en : descriptionReplacementRes) {
                        newVal = en.getKey().matcher(newVal).replaceAll(en.getValue());
                    }
                }
                if (ownLinkRe != null) {
                    newVal = ownLinkRe.matcher(newVal).replaceAll("xref:$1.adoc");
                }
                k.setConfigDoc(newVal);
            }
        }
        DocGeneratorUtil.sort(configDocItems);
        return configDocItems.stream()
                .map(ConfigItem::of)
                .filter(i -> configOptionExcludeRes.stream().noneMatch(p -> p.matcher(i.getKey()).find()))
                .collect(Collectors.toList());
    }

    static List<String> loadConfigRoots(Path... basePath) {
        final List<String> result = new ArrayList<>();
        Stream.of(basePath)
                .map(p -> p.resolve("target/classes/META-INF/quarkus-config-roots.list"))
                .filter(Files::exists)
                .forEach(configRootsListPath -> {

                    try (Stream<String> lines = Files.lines(configRootsListPath, StandardCharsets.UTF_8)) {
                        lines
                                .map(String::trim)
                                .filter(l -> !l.isEmpty())
                                .map(l -> l.replace('$', '.'))
                                .forEach(result::add);
                    } catch (IOException e) {
                        throw new RuntimeException("Could not read from " + configRootsListPath, e);
                    }
                });
        return Collections.unmodifiableList(result);
    }

    public static class ConfigItem {

        private final String key;
        private final String illustration;
        private final String configDoc;
        private final String type;
        private final String defaultValue;
        private final boolean optional;
        private final String since;
        private final String environmentVariable;

        public static ConfigItem of(ConfigDocItem configDocItem) {
            final ConfigDocKey configDocKey = configDocItem.getConfigDocKey();
            return new ConfigItem(
                    configDocKey.getKey(),
                    configDocKey.getConfigPhase().getIllustration(),
                    configDocKey.getConfigDoc(),
                    typeContent(configDocKey),
                    configDocKey.getDefaultValue(),
                    configDocKey.isOptional(),
                    configDocKey.getSince(),
                    configDocKey.getEnvironmentVariable());
        }

        static String typeContent(ConfigDocKey configDocKey) {
            String typeContent = "";
            if (configDocKey.hasAcceptedValues()) {
                if (configDocKey.isEnum()) {
                    typeContent = joinEnumValues(configDocKey.getAcceptedValues());
                } else {
                    typeContent = joinAcceptedValues(configDocKey.getAcceptedValues());
                }
            } else if (configDocKey.hasType()) {
                typeContent = configDocKey.computeTypeSimpleName();
                final String javaDocLink = configDocKey.getJavaDocSiteLink();
                if (!javaDocLink.isEmpty()) {
                    typeContent = String.format("link:%s[%s]\n", javaDocLink, typeContent);
                }
                typeContent = "`" + typeContent + "`";
            }
            if (configDocKey.isList()) {
                typeContent = "List of `" + typeContent + "`";
            }
            return typeContent;
        }

        static String joinAcceptedValues(List<String> acceptedValues) {
            if (acceptedValues == null || acceptedValues.isEmpty()) {
                return "";
            }

            return acceptedValues.stream()
                    .collect(Collectors.joining("`, `", Constants.CODE_DELIMITER, Constants.CODE_DELIMITER));
        }

        static String joinEnumValues(List<String> enumValues) {
            if (enumValues == null || enumValues.isEmpty()) {
                return Constants.EMPTY;
            }

            // nested macros are only detected when cell starts with a new line, e.g. a|\n myMacro::[]
            return String.join(", ", enumValues);
        }

        public ConfigItem(String key, String illustration, String configDoc, String type, String defaultValue,
                boolean optional, String since, String environmentVariable) {
            this.key = key;
            this.illustration = illustration;
            this.configDoc = configDoc;
            this.type = type;
            this.defaultValue = defaultValue;
            this.optional = optional;
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
    }
}
