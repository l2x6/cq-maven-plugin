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

import freemarker.cache.ClassTemplateLoader;
import freemarker.cache.FileTemplateLoader;
import freemarker.cache.MultiTemplateLoader;
import freemarker.cache.TemplateLoader;
import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateException;
import freemarker.template.TemplateExceptionHandler;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.Stack;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.lang.model.SourceVersion;
import org.apache.camel.tooling.model.ArtifactModel;
import org.apache.maven.model.Model;
import org.apache.maven.plugin.logging.Log;
import org.l2x6.cq.maven.TemplateParams.ExtensionStatus;
import org.l2x6.maven.utils.MavenSourceTree.Module;

public class CqUtils {
    public static final String CLASSPATH_PREFIX = "classpath:";

    public static final String FILE_PREFIX = "file:";
    public static final List<String> DEFAULT_CATEGORIES = Collections.singletonList("integration");
    public static final String DEFAULT_TEMPLATES_URI_BASE = "classpath:/create-extension-templates";
    public static final String DEFAULT_ENCODING = "utf-8";

    static TemplateLoader createTemplateLoader(Path basePath, String defaultUriBase, String templatesUriBase) {
        final TemplateLoader defaultLoader = new ClassTemplateLoader(CreateExtensionMojo.class,
                defaultUriBase.substring(CLASSPATH_PREFIX.length()));
        if (defaultUriBase.equals(templatesUriBase)) {
            return defaultLoader;
        } else if (templatesUriBase.startsWith(CLASSPATH_PREFIX)) {
            return new MultiTemplateLoader( //
                    new TemplateLoader[] { //
                            new ClassTemplateLoader(CreateExtensionMojo.class,
                                    templatesUriBase.substring(CLASSPATH_PREFIX.length())), //
                            defaultLoader //
                    });
        } else if (templatesUriBase.startsWith(FILE_PREFIX)) {
            final Path resolvedTemplatesDir = basePath.resolve(templatesUriBase.substring(FILE_PREFIX.length()));
            try {
                return new MultiTemplateLoader( //
                        new TemplateLoader[] { //
                                new FileTemplateLoader(resolvedTemplatesDir.toFile()),
                                defaultLoader //
                        });
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        } else {
            throw new IllegalStateException(String.format(
                    "Cannot handle templatesUriBase '%s'; only value starting with '%s' or '%s' are supported",
                    templatesUriBase, CLASSPATH_PREFIX, FILE_PREFIX));
        }
    }

    public static Stream<ExtensionModule> findExtensions(Path basePath, Collection<Module> modules,
            Predicate<String> artifactIdBaseFilter) {
        return modules.stream()
                .filter(p -> p.getGav().getArtifactId().asConstant().endsWith("-deployment"))
                .map(p -> {
                    final Path extensionDir = basePath.resolve(p.getPomPath()).getParent().getParent().toAbsolutePath()
                            .normalize();
                    final String deploymentArtifactId = p.getGav().getArtifactId().asConstant();
                    if (!deploymentArtifactId.startsWith("camel-quarkus-")) {
                        throw new IllegalStateException("Should start with 'camel-quarkus-': " + deploymentArtifactId);
                    }
                    final String artifactIdBase = deploymentArtifactId.substring("camel-quarkus-".length(),
                            deploymentArtifactId.length() - "-deployment".length());
                    return new ExtensionModule(extensionDir, artifactIdBase);
                })
                .filter(e -> artifactIdBaseFilter.test(e.getArtifactIdBase()))
                .sorted();
    }

    public static Configuration getTemplateConfig(Path basePath, String defaultUriBase, String templatesUriBase,
            String encoding) {
        final Configuration templateCfg = new Configuration(Configuration.VERSION_2_3_28);
        templateCfg.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);
        templateCfg.setTemplateLoader(createTemplateLoader(basePath, defaultUriBase, templatesUriBase));
        templateCfg.setDefaultEncoding(encoding);
        templateCfg.setInterpolationSyntax(Configuration.SQUARE_BRACKET_INTERPOLATION_SYNTAX);
        templateCfg.setTagSyntax(Configuration.SQUARE_BRACKET_TAG_SYNTAX);
        return templateCfg;
    }

    static String getVersion(Model basePom) {
        return basePom.getVersion() != null ? basePom.getVersion()
                : basePom.getParent() != null && basePom.getParent().getVersion() != null
                        ? basePom.getParent().getVersion()
                        : null;
    }

    public static String extensionDocUrl(String artifactIdBase) {
        return "https://camel.apache.org/camel-quarkus/latest/reference/extensions/" + artifactIdBase + ".html";
    }

    public static Path extensionDocPage(Path repoRootDir, String artifactIdBase) {
        return repoRootDir.resolve("docs/modules/ROOT/pages/reference/extensions/" + artifactIdBase + ".adoc");
    }

    public static void evalTemplate(Configuration cfg, String templateUri, Path dest, TemplateParams model,
            Consumer<String> log) {
        log.accept("Generating " + dest);
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

    public static TemplateParams quarkusExtensionYamlParams(
            List<ArtifactModel<?>> models,
            String artifactIdBase,
            String titleBase,
            String description,
            List<String> keywords,
            boolean unlisted,
            boolean deprecated,
            boolean isNativeSupported,
            ExtensionStatus status,
            Path rootDir,
            Log log,
            List<String> errors) {
        final String kind;
        if (models.isEmpty()) {
            if (!unlisted) {
                log.debug(artifactIdBase + ": found zero models");
            }
            kind = null;
        } else {
            if (models.size() == 1) {
                final ArtifactModel<?> model = models.get(0);
                if (description == null) {
                    description = model.getDescription();
                }
                String expectedTitle = model.getTitle();
                if (expectedTitle.toLowerCase(Locale.ROOT).startsWith("json ")) {
                    expectedTitle = expectedTitle.substring("json ".length());
                }
                if (titleBase != null && !titleBase.equals(expectedTitle)) {
                    log.warn(artifactIdBase + ": expected name base '" + expectedTitle + "' found '" + titleBase + "'");
                }
                kind = model.getKind();
            } else {
                if (description == null) {
                    final Set<String> uniqueDescriptions = models.stream()
                            .map(m -> m.getDescription())
                            .collect(Collectors.toCollection(LinkedHashSet::new));
                    description = uniqueDescriptions
                            .stream()
                            .collect(Collectors.joining(" "));
                    if (uniqueDescriptions.size() > 1) {
                        log.warn(artifactIdBase
                                + ": Consider adding and explicit <description> if you do not like the concatenated description: "
                                + description);
                    }
                }
                kind = models.get(0).getKind();
            }

        }
        if (description == null) {
            final String msg = artifactIdBase + ": Add and explicit <description>";
            log.error(msg);
            errors.add(msg);
        }

        return TemplateParams.builder()
                .nameBase(titleBase)
                .description(sanitizeDescription(description))
                .keywords(keywords)
                .unlisted(unlisted)
                .deprecated(deprecated)
                .nativeSupported(isNativeSupported)
                .status(status)
                .guideUrl(CqUtils.extensionDocUrl(artifactIdBase))
                .categories(org.l2x6.cq.maven.CqUtils.DEFAULT_CATEGORIES)
                .build();
    }

    public static String sanitizeDescription(String description) {
        return description.endsWith(".") ? description.substring(0, description.length() - 1) : description;
    }

    public static String toCapCamelCase(String artifactIdBase) {
        final StringBuilder sb = new StringBuilder(artifactIdBase.length());
        for (String segment : artifactIdBase.split("[.\\-\\+]+")) {
            sb.append(Character.toUpperCase(segment.charAt(0)));
            if (segment.length() > 1) {
                sb.append(segment.substring(1));
            }
        }
        return sb.toString();
    }

    public static String toSnakeCase(String artifactIdBase) {
        final StringBuilder sb = new StringBuilder(artifactIdBase.length());
        final String[] segments = artifactIdBase.split("[.\\-\\+]+");
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                sb.append('_');
            }
            sb.append(segments[i].toLowerCase(Locale.ROOT));
        }
        return sb.toString();
    }

    public static String toKebabCase(String artifactIdBase) {
        final StringBuilder sb = new StringBuilder(artifactIdBase.length());
        final String[] segments = artifactIdBase.split("[.\\-\\+]+");
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                sb.append('-');
            }
            sb.append(segments[i].toLowerCase(Locale.ROOT));
        }
        return sb.toString();
    }

    public static String getJavaPackage(String groupId, String javaPackageInfix, String artifactId) {
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

}
