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
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import com.google.gson.GsonBuilder;

/**
 * Stores example metadata into an {@code examples.json} file.
 *
 * @since 0.25.0
 */
@Mojo(name = "update-examples-json", requiresProject = false, inheritByDefault = false)
public class UpdateExamplesJsonMojo extends AbstractMojo {

    static final String DEFAULT_EXAMPLES_JSON = "docs/modules/ROOT/attachments/examples.json";

    private static final String DESCRIPTION_PREFIX = ":cq-example-description: An example that ";

    /**
     * Where the generated {@code examples.json} file should be stored
     *
     * @since 0.25.0
     */
    @Parameter(property = "cq.examplesJsonFile", required = true, defaultValue = DEFAULT_EXAMPLES_JSON)
    File examplesJsonFile;

    /**
     * Where to look for example Maven modules
     *
     * @since 0.25.0
     */
    @Parameter(property = "cq.examplesDir", required = true, defaultValue = ".")
    File examplesDir;

    /**
     * Encoding to read and write files in the current source tree
     *
     * @since 0.25.0
     */
    @Parameter(defaultValue = CqUtils.DEFAULT_ENCODING, required = true, property = "cq.encoding")
    String encoding;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        final Path examplesJsonPath = examplesJsonFile.toPath();
        final Path examplesDirPath = examplesDir.toPath();
        final Charset charset = Charset.forName(encoding);

        try {
            Files.createDirectories(examplesJsonPath.getParent());
        } catch (IOException e) {
            throw new RuntimeException("Could not create " + examplesJsonPath.getParent(), e);
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
                                    example.description = Character.toUpperCase(shortDescription.charAt(0))
                                            + shortDescription.substring(1);
                                } else if (line.startsWith(":") || line.isEmpty()) {
                                    /* ignore */
                                } else {
                                    break;
                                }
                            }
                            example.link = "https://github.com/apache/camel-quarkus-examples/tree/main/" + dirName;
                            return example.validate();
                        } catch (Exception e) {
                            throw new RuntimeException("Could not read " + readmePath, e);
                        }

                    })
                    .sorted()
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new RuntimeException("Could not list " + examplesDirPath, e);
        }

        try (Writer w = Files.newBufferedWriter(examplesJsonPath, charset)) {
            new GsonBuilder().setPrettyPrinting().create().toJson(exampleData, w);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write to " + examplesJsonPath, e);
        }
    }

    static class Example implements Comparable<Example> {
        private static final Comparator<Example> BY_TITLE = Comparator.comparing(e -> e.title);
        private String title;
        private String description;
        private String link;

        @Override
        public int compareTo(Example o) {
            return BY_TITLE.compare(this, o);
        }

        public Example validate() {
            Objects.requireNonNull(title, "title cannot be null in "+ this);
            Objects.requireNonNull(description, "description cannot be null in "+ this);
            Objects.requireNonNull(link, "link cannot be null in "+ this);
            return this;
        }

        @Override
        public String toString() {
            return "Example [title=" + title + ", description=" + description + ", link=" + link + "]";
        }

        public static Comparator<Example> getByTitle() {
            return BY_TITLE;
        }

    }

}
