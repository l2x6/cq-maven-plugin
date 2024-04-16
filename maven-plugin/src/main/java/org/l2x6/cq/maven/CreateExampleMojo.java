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
import freemarker.template.Template;
import freemarker.template.TemplateException;
import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import java.util.stream.Stream;
import org.apache.maven.model.Model;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.l2x6.cq.common.CqCommonUtils;

/**
 * Scaffolds a new Camel Quarkus example project.
 *
 * @since 4.8.0
 */
@Mojo(name = "create-example", requiresProject = false)
public class CreateExampleMojo extends AbstractMojo {
    static final String DEFAULT_TEMPLATES_URI_BASE = "classpath:/create-example-templates";

    /**
     * Directory where the new example should be added. Default is the current directory of the running Java process.
     *
     * @since 4.8.0
     */
    @Parameter(property = "cq.basedir", defaultValue = "${project.basedir}")
    File basedir;
    private Path basePath;

    /**
     * The default encoding used to process example project templates.
     *
     * @since 4.8.0
     */
    @Parameter(defaultValue = CqUtils.DEFAULT_ENCODING, required = true, property = "cq.encoding")
    String encoding;
    Charset charset;

    /**
     * The base directory where example project templates are located. Can be used to override the plugin default template
     * content.
     *
     * @since 4.8.0
     */
    @Parameter(defaultValue = DEFAULT_TEMPLATES_URI_BASE, required = true, property = "cq.templatesUriBase")
    String templatesUriBase;

    /**
     * The base name without the camel-quarkus-examples- prefix for the Maven artifactId of the new project.
     *
     * @since 4.8.0
     */
    @Parameter(required = true, property = "cq.artifactIdBase")
    String artifactIdBase;

    /**
     * The name of the example project. This is usually your chosen Maven project artifactId & directory name.
     * When not specified the name of the project is determined from the artifactIdBase parameter.
     *
     * @since 4.8.0
     */
    @Parameter(property = "cq.exampleName")
    String exampleName;

    /**
     * A brief description of the example project to be included within the generate project README.
     *
     * @since 4.8.0
     */
    @Parameter(required = true, property = "cq.exampleDescription")
    String exampleDescription;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        basePath = basedir != null ? basedir.toPath().toAbsolutePath().normalize() : Paths.get(".");
        charset = Charset.forName(encoding);

        try (Stream<Path> dirs = Files.list(basePath)) {
            // Try to get the basic set of example project pom.xml properties from an existing example
            final Path firstPomXml = dirs
                    .map(dir -> dir.resolve("pom.xml"))
                    .filter(Files::isRegularFile)
                    .findFirst()
                    .orElseThrow(() -> new RuntimeException("Could not find any example project under " + basePath));

            final Configuration configuration = CqUtils.getTemplateConfig(basePath, CqUtils.DEFAULT_TEMPLATES_URI_BASE,
                    templatesUriBase, encoding);
            final Model fistModel = CqCommonUtils.readPom(firstPomXml, charset);
            final Properties props = fistModel.getProperties();

            if (props.isEmpty()) {
                throw new RuntimeException("Could not any properties defined in " + firstPomXml.toAbsolutePath());
            }

            final String artifactId = artifactIdBase.replace("camel-quarkus-", "");
            final String packageName = artifactId.replace('-', '.');
            final String classNamePrefix = CqUtils.toCapCamelCase(artifactId);
            final String name = exampleName == null ? classNamePrefix.replaceAll("([a-z])([A-Z])", "$1 $2") : exampleName;
            props.put("version", fistModel.getVersion());
            props.put("artifactId", "camel-quarkus-examples-" + artifactId);
            props.put("exampleName", name);
            props.put("examplePackageName", packageName);
            props.put("classNamePrefix", classNamePrefix);
            props.put("description", exampleDescription);

            Path exampleProjectBaseDir = basePath.resolve(artifactId);
            Path exampleProjectSrcDir = exampleProjectBaseDir
                    .resolve("src/main/java/org/acme/" + packageName.replace('.', '/'));
            Path exampleProjectTestDir = exampleProjectBaseDir
                    .resolve("src/test/java/org/acme/" + packageName.replace('.', '/'));
            Files.createDirectories(exampleProjectBaseDir);
            Files.createDirectories(exampleProjectSrcDir);
            Files.createDirectories(exampleProjectTestDir);

            generateFileFromTemplate(configuration, props, "README.adoc", exampleProjectBaseDir.resolve("README.adoc"));
            generateFileFromTemplate(configuration, props, "pom.xml", exampleProjectBaseDir.resolve("pom.xml"));
            generateFileFromTemplate(configuration, props, "Routes.java",
                    exampleProjectSrcDir.resolve(classNamePrefix + "Routes.java"));
            generateFileFromTemplate(configuration, props, "Test.java",
                    exampleProjectTestDir.resolve(classNamePrefix + "Test.java"));
            generateFileFromTemplate(configuration, props, "IT.java",
                    exampleProjectTestDir.resolve(classNamePrefix + "IT.java"));

            getLog().info("Generated example project " + artifactId
                    + "\n\n Run org.l2x6.cq:cq-maven-plugin:update-examples-json to update examples.json");
        } catch (IOException e) {
            throw new RuntimeException("Could not list " + basePath, e);
        }
    }

    static void generateFileFromTemplate(Configuration configuration, Properties model, String templateName, Path destination) {
        try {
            final Template template = configuration.getTemplate(templateName);
            try (Writer out = Files.newBufferedWriter(destination)) {
                template.process(model, out);
            }
        } catch (IOException | TemplateException e) {
            throw new RuntimeException("Could not evaluate template " + templateName, e);
        }
    }
}
