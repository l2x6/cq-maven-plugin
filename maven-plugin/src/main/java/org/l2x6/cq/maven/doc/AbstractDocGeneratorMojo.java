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
import freemarker.template.Template;
import freemarker.template.TemplateException;
import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.l2x6.cq.common.CqCommonUtils;
import org.l2x6.cq.maven.CqUtils;

/**
 * Base for {@link UpdateDocsMojo} and {@link UpdateDocPageMojo}.
 *
 * @since 3.4.0
 */
abstract class AbstractDocGeneratorMojo extends AbstractMojo {
    public static final String DEFAULT_TEMPLATES_URI_BASE = "classpath:/doc-templates";
    /**
     * A filesystem (prefixed with {@code file:} or classpath resource (prefixed with {@code classpath:} directory where
     * to look for the Freemarker template for rendering the extension documentation page.
     *
     * @since 3.4.0
     */
    @Parameter(defaultValue = DEFAULT_TEMPLATES_URI_BASE, required = true, property = "cq.templatesUriBase")
    String templatesUriBase;

    /**
     * The current module's base directory.
     *
     * @since 3.4.0
     */
    @Parameter(property = "cq.basedir", defaultValue = "${project.basedir}")
    File baseDir;

    /**
     * The path to the docs module base directory
     *
     * @since 3.4.0
     */
    @Parameter(defaultValue = "${maven.multiModuleProjectDirectory}/docs")
    protected File docsBaseDir;

    /**
     * The root directory of the Camel Quarkus source tree.
     *
     * @since 3.4.0
     */
    @Parameter(defaultValue = "${maven.multiModuleProjectDirectory}", readonly = true)
    private File multiModuleProjectDirectory;
    private Path multiModuleProjectDirectoryPath;

    /**
     * Encoding to read and write files in the current source tree
     *
     * @since 3.4.0
     */
    @Parameter(defaultValue = CqUtils.DEFAULT_ENCODING, required = true, property = "cq.encoding")
    String encoding;
    private Charset charset;

    protected static <T extends Writer> T evalTemplate(Configuration cfg, String templateUri, Map<String, Object> model,
            T out) {
        try {
            final Template template = cfg.getTemplate(templateUri);
            try {
                template.process(model, out);
            } catch (TemplateException e) {
                throw new RuntimeException("Could not process template " + templateUri + ":\n\n" + out.toString(), e);
            }
            return out;
        } catch (IOException e) {
            throw new RuntimeException("Could not evaluate template " + templateUri, e);
        }
    }

    protected Charset getCharset() {
        if (charset == null) {
            charset = Charset.forName(encoding);
        }
        return charset;
    }

    public static String extensionName(Function<String, String> getProperty, Supplier<String> getName) {
        String val = getProperty.apply("cq.name");
        if (val == null) {
            return CqCommonUtils.getNameBase(getName.get());
        }
        return val;
    }

    public Path getMultiModuleProjectDirectoryPath() {
        if (multiModuleProjectDirectoryPath == null) {
            multiModuleProjectDirectoryPath = multiModuleProjectDirectory.toPath();
        }
        return multiModuleProjectDirectoryPath;
    }

}
