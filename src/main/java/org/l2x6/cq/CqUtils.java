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
import java.io.Reader;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

import freemarker.cache.ClassTemplateLoader;
import freemarker.cache.FileTemplateLoader;
import freemarker.cache.MultiTemplateLoader;
import freemarker.cache.TemplateLoader;
import freemarker.template.Configuration;
import freemarker.template.TemplateExceptionHandler;

public class CqUtils {
    public static final String CLASSPATH_PREFIX = "classpath:";

    public static final String FILE_PREFIX = "file:";

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

    public static Configuration getTemplateConfig(Path basePath, String defaultUriBase, String templatesUriBase, String encoding) {
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

}
