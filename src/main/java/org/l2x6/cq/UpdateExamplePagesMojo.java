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

import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.StringJoiner;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.camel.util.json.Jsonable;
import org.apache.camel.util.json.Jsoner;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/**
 * Updates AsciiDoc pages that contain example metadata.
 *
 * @since 0.23.0
 */
@Mojo(name = "update-example-pages", requiresProject = false, inheritByDefault = false)
public class UpdateExamplePagesMojo extends AbstractMojo {

    private static final String DESCRIPTION_PREFIX = ":cq-example-description: An example that ";

    /**
     * Where the generated pages should be stored
     *
     * @since 0.23.0
     */
    @Parameter(property = "cq.attachmentsDir", required = true, defaultValue = "docs/modules/ROOT/attachments")
    File attachmentsDir;

    /**
     * Where to look for example Maven modules
     *
     * @since 0.23.0
     */
    @Parameter(property = "cq.examplesDir", required = true, defaultValue = ".")
    File examplesDir;

    /**
     * Encoding to read and write files in the current source tree
     *
     * @since 0.23.0
     */
    @Parameter(defaultValue = CqUtils.DEFAULT_ENCODING, required = true, property = "cq.encoding")
    String encoding;

    private static class Example {
    	private String title;
    	private String description;
    	private String link;

		public String toJson() {
			final StringBuilder json = new StringBuilder();
			json.append('{');
			json.append("\"title\":\"");
			json.append(title);
			json.append("\",\"description\":\"");
			json.append(description);
			json.append("\",\"link\":\"");
			json.append(link);
			json.append("\"}");

			return json.toString();
		}

		public String title() {
			return title;
		}
    }

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        final Path attachmentsDirPath = attachmentsDir.toPath();
        final Path examplesDirPath = examplesDir.toPath();
        final Charset charset = Charset.forName(encoding);

        try {
            Files.createDirectories(attachmentsDirPath);
        } catch (IOException e) {
            throw new RuntimeException("Could not create "+ attachmentsDirPath, e);
        }

        final Set<String> wantedPages = new HashSet<>();
        final List<Example> exampleData;
        try (Stream<Path> examples = Files.list(examplesDirPath)) {
            exampleData = examples
                    .map(p -> examplesDirPath.resolve(p)) // absolutize
                    .filter(p -> Files.isRegularFile(p.resolve("pom.xml")))
                    .map(p -> {
                        final String dirName = p.getFileName().toString();
                        final String pageFileName = dirName + ".adoc";
                        wantedPages.add(pageFileName);

                        Example example = new Example();
                        final Path readmePath = p.resolve("README.adoc");
                        try {
                            final List<String> readmeLines = Files.readAllLines(readmePath, charset);
                            for (String line : readmeLines) {
                                line = line.trim();
                                if (line.startsWith("= ")) {
                                    example.title = line.substring(2).replace(": A Camel Quarkus example", "");
                                } else if (line.startsWith(DESCRIPTION_PREFIX)) {
                                    final String shortDescription = line.substring(DESCRIPTION_PREFIX.length(), line.length());
                                    example.description = Character.toUpperCase(shortDescription.charAt(0)) + shortDescription.substring(1);
                                } else if (line.startsWith(":") || line.isEmpty()) {
                                    /* ignore */
                                } else {
                                    break;
                                }
                            }
                            example.link = "https://github.com/apache/camel-quarkus-examples/tree/master/"+ dirName;

                            return example;
                        } catch (IOException e) {
                            throw new RuntimeException("Could not read " + readmePath, e);
                        }

                    })
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new RuntimeException("Could not list " + examplesDirPath, e);
        }

        final Path examplesDataJsonPath = attachmentsDirPath.resolve("examples.json");
        try {
			final String json = exampleData.stream().sorted(Comparator.comparing(Example::title)).map(Example::toJson).collect(Collectors.joining(",", "[", "]"));
			Files.write(examplesDataJsonPath, Collections.singleton(json));
		} catch (IOException e) {
			throw new UncheckedIOException("Could not write to " + examplesDataJsonPath, e);
		}
    }

}
