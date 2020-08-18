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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
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
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.plugin.logging.Log;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

import freemarker.cache.ClassTemplateLoader;
import freemarker.cache.FileTemplateLoader;
import freemarker.cache.MultiTemplateLoader;
import freemarker.cache.TemplateLoader;
import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateException;
import freemarker.template.TemplateExceptionHandler;

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

    public static Stream<String> findExtensionArtifactIdBases(Path extensionDir) {
        try {
            return Files.list(extensionDir)
                    .filter(path -> Files.isDirectory(path)
                            && Files.exists(path.resolve("pom.xml"))
                            && Files.exists(path.resolve("runtime")))
                    .map(dir -> dir.getFileName().toString());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static Stream<ExtensionModule> findExtensions(Stream<Path> extensionDirectories,
            Predicate<String> artifactIdFilter) {
        return extensionDirectories
                .map(Path::toAbsolutePath)
                .map(Path::normalize)
                .flatMap(extDir -> CqUtils.findExtensionArtifactIdBases(extDir)
                        .filter(artifactIdFilter)
                        .map(artifactIdBase -> new ExtensionModule(extDir.resolve(artifactIdBase), artifactIdBase))
                        .sorted());
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

    public static Model readPom(final Path path, Charset charset) {
        try (Reader r = Files.newBufferedReader(path, charset)) {
            return new MavenXpp3Reader().read(r);
        } catch (XmlPullParserException | IOException e) {
            throw new RuntimeException("Could not parse " + path, e);
        }
    }

    public static String extensionDocUrl(Path repoRootDir, String artifactIdBase, String kind) {
        final Path docPage = extensionDocPage(repoRootDir, artifactIdBase);
        if (Files.exists(docPage)) {
            return "https://camel.apache.org/camel-quarkus/latest/extensions/" + artifactIdBase + ".html";
        } else {
            return entityDocUrl(artifactIdBase, kind);
        }
    }

    public static Path extensionDocPage(Path repoRootDir, String artifactIdBase) {
        return repoRootDir.resolve("docs/modules/ROOT/pages/extensions/" + artifactIdBase + ".adoc");
    }

    public static String entityDocUrl(String artifactIdBase, String kind) {
        return "https://camel.apache.org/components/latest/" + artifactIdBase + "-" + kind + ".html";
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
                .guideUrl(CqUtils.extensionDocUrl(rootDir, artifactIdBase, kind))
                .categories(org.l2x6.cq.CqUtils.DEFAULT_CATEGORIES)
                .build();
    }

    public static String sanitizeDescription(String description) {
        return description.endsWith(".") ? description.substring(0, description.length() - 1) : description;
    }

    public static String toCapCamelCase(String artifactIdBase) {
        final StringBuilder sb = new StringBuilder(artifactIdBase.length());
        for (String segment : artifactIdBase.split("[.\\-]+")) {
            sb.append(Character.toUpperCase(segment.charAt(0)));
            if (segment.length() > 1) {
                sb.append(segment.substring(1));
            }
        }
        return sb.toString();
    }

    public static String toSnakeCase(String artifactIdBase) {
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

    static Path copyJar(Path localRepository, String groupId, String artifactId, String version) {
        final String relativeJarPath = groupId.replace('.', '/') + "/" + artifactId + "/" + version + "/" + artifactId + "-"
                + version + ".jar";
        final Path localPath = localRepository.resolve(relativeJarPath);
        final boolean localExists = Files.exists(localPath);
        final String remoteUri = "https://repository.apache.org/content/groups/public/" + relativeJarPath;
        Path result;
        try {
            result = Files.createTempFile(null, localPath.getFileName().toString());
            try (InputStream in = (localExists ? Files.newInputStream(localPath) : new URL(remoteUri).openStream());
                    OutputStream out = Files.newOutputStream(result)) {
                final byte[] buf = new byte[4096];
                int len;
                while ((len = in.read(buf)) >= 0) {
                    out.write(buf, 0, len);
                }
            } catch (IOException e) {
                throw new RuntimeException("Could not copy " + (localExists ? localPath : remoteUri) + " to " + result, e);
            }
        } catch (IOException e) {
            throw new RuntimeException("Could not create temp file", e);
        }
        return result;
    }

}
