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
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

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
    @Parameter(property = "cq.pagesDir", required = true, defaultValue = "docs/modules/ROOT/pages/examples")
    File pagesDir;

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

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        final Path pagesDirPath = pagesDir.toPath();
        final Path examplesDirPath = examplesDir.toPath();
        final Charset charset = Charset.forName(encoding);

        try {
            Files.createDirectories(pagesDirPath);
        } catch (IOException e) {
            throw new RuntimeException("Could not create "+ pagesDirPath, e);
        }

        final Set<String> wantedPages = new HashSet<>();
        try (Stream<Path> examples = Files.list(examplesDirPath)) {

            examples
                    .map(p -> examplesDirPath.resolve(p)) // absolutize
                    .filter(p -> Files.isRegularFile(p.resolve("pom.xml")))
                    .forEach(p -> {

                        final String dirName = p.getFileName().toString();
                        final String pageFileName = dirName + ".adoc";
                        wantedPages.add(pageFileName);

                        final Path readmePath = p.resolve("README.adoc");
                        try {
                            final List<String> readmeLines = Files.readAllLines(readmePath, charset);
                            final StringBuilder sb = new StringBuilder();
                            for (String line : readmeLines) {
                                line = line.trim();
                                if (line.startsWith("= ")) {
                                    sb.append(line + "\n");
                                    final String title = line.substring(2).replace(": A Camel Quarkus example", "");
                                    sb.append(":cq-example-title: " + title + "\n");
                                } else if (line.startsWith(DESCRIPTION_PREFIX)) {
                                    final String shortDescription = line.substring(DESCRIPTION_PREFIX.length(), line.length());
                                    sb.append(":cq-example-description: ").append(Character.toUpperCase(shortDescription.charAt(0))).append(shortDescription.substring(1)).append('\n');
                                } else if (line.startsWith(":")) {
                                    sb.append(line + "\n");
                                } else if (line.isEmpty()) {
                                    /* ignore */
                                } else {
                                    break;
                                }
                            }
                            sb.append(":cq-example-url: https://github.com/apache/camel-quarkus-examples/tree/master/"+ dirName +"\n");

                            final Path pagePath = pagesDirPath.resolve(pageFileName);
                            getLog().info("Updating " + pagePath);
                            Files.write(pagePath, sb.toString().getBytes(charset));
                        } catch (IOException e) {
                            throw new RuntimeException("Could not read " + readmePath, e);
                        }

                    });

        } catch (IOException e) {
            throw new RuntimeException("Could not list " + examplesDirPath, e);
        }

        /* Remove the stale files */
        try (Stream<Path> pages = Files.list(pagesDirPath)) {
            pages
                    .filter(p -> p.getFileName().toString().endsWith(".adoc"))
                    .filter(p -> !wantedPages.contains(p.getFileName().toString()))
                    .forEach(p -> {
                        final Path pagePath = pagesDirPath.resolve(p);
                        getLog().info("Deleting a stale page " + pagePath);
                        try {
                            Files.delete(pagePath);
                        } catch (IOException e) {
                            throw new RuntimeException("Could not delete " + p, e);
                        }
                    });
        } catch (IOException e) {
            throw new RuntimeException("Could not list " + pagesDirPath, e);
        }

    }

}
