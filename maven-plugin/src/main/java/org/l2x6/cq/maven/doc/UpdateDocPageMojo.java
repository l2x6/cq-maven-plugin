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
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
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

    @Parameter(defaultValue = "${project}", readonly = true)
    MavenProject project;

    /** {@inheritDoc} */
    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("Skipping per user request");
            return;
        }
        final Path basePath = baseDir.toPath();

        if (!"runtime".equals(basePath.getFileName().toString())) {
            getLog().info("Skipping a module that is not a Quarkus extension runtime module");
            return;
        }

        final Configuration cfg = CqUtils.getTemplateConfig(basePath, DEFAULT_TEMPLATES_URI_BASE, templatesUriBase,
                getCharset().toString());

        final Map<String, Object> model = new HashMap<>();
        final String artifactId = project.getArtifactId();
        final Path docPagePath = getMultiModuleProjectDirectoryPath()
                .resolve("docs/modules/ROOT/pages/reference/extensions/" + artifactId + ".adoc");
        model.put("artifactId", artifactId);
        model.put("groupId", project.getGroupId());
        model.put("since", getRequiredProperty("cq.since"));
        model.put("name", extensionName(project.getModel()));
        model.put("status", org.l2x6.cq.common.ExtensionStatus.valueOf(project.getProperties()
                .getProperty("quarkus.metadata.status", org.l2x6.cq.common.ExtensionStatus.stable.name())).getCapitalized());
        final boolean deprecated = Boolean
                .parseBoolean(project.getProperties().getProperty("quarkus.metadata.deprecated", "false"));
        model.put("deprecated", deprecated);
        model.put("unlisted", Boolean.parseBoolean(project.getProperties().getProperty("quarkus.metadata.unlisted", "false")));
        model.put("intro", loadSection(basePath, "intro.adoc", getCharset(), artifactId, project.getDescription()));
        model.put("standards", loadSection(basePath, "standards.adoc", getCharset(), artifactId, null));
        model.put("usage", loadSection(basePath, "usage.adoc", getCharset(), artifactId, null));
        model.put("usageAdvanced", loadSection(basePath, "usage-advanced.adoc", getCharset(), artifactId, null));
        model.put("configuration", loadSection(basePath, "configuration.adoc", getCharset(), artifactId, null));
        model.put("configurationPropertiesInclude",
                configurationPropertiesInclude(getMultiModuleProjectDirectoryPath(), artifactId, docPagePath));
        model.put("limitations", loadSection(basePath, "limitations.adoc", getCharset(), artifactId, null));

        evalTemplate(getCharset(), docPagePath, cfg, model, "extension-doc-page.adoc", "//");
    }

    public static String extensionName(Model project) {
        return project.getProperties().getProperty("cq.name", CqCommonUtils.getNameBase(project.getName()));
    }

    static String configurationPropertiesInclude(Path multiModuleProjectDirectoryPath, String artifactId, Path docPagePath) {
        final Path props = multiModuleProjectDirectoryPath.resolve("docs/modules/ROOT/pages/includes/" + artifactId + ".adoc");
        if (Files.isRegularFile(props)) {
            return docPagePath.getParent().relativize(props).toString();
        }
        return null;
    }

    private String getRequiredProperty(String key) {
        Object val = project.getProperties().get(key);
        if (val == null) {
            throw new IllegalStateException(
                    "Could not find required property " + key + " in module " + project.getArtifactId());
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
            Files.write(docPagePath, pageText.getBytes(charset));
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

}
