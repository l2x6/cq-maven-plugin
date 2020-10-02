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
 * Updates AsciiDoc partial files that contain example metadata.
 *
 * @since 0.23.0
 */
@Mojo(name = "update-example-partials", requiresProject = false, inheritByDefault = false)
public class UpdateExamplePartialsMojo extends AbstractMojo {

    /**
     * Where the generated partials should be stored
     *
     * @since 0.23.0
     */
    @Parameter(property = "cq.partialsDir", required = true, defaultValue = "docs/modules/ROOT/partials/examples")
    File partialsDir;

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
        final Path partialsDirPath = partialsDir.toPath();
        final Path examplesDirPath = examplesDir.toPath();
        final Charset charset = Charset.forName(encoding);

        try {
            Files.createDirectories(partialsDirPath);
        } catch (IOException e) {
            throw new RuntimeException("Could not create "+ partialsDirPath, e);
        }

        final Set<String> wantedPartials = new HashSet<>();
        try (Stream<Path> examples = Files.list(examplesDirPath)) {

            examples
                    .map(p -> examplesDirPath.resolve(p)) // absolutize
                    .filter(p -> Files.isRegularFile(p.resolve("pom.xml")))
                    .forEach(p -> {

                        final String dirName = p.getFileName().toString();
                        final String partialFileName = dirName + ".adoc";
                        wantedPartials.add(partialFileName);

                        final Path readmePath = p.resolve("README.adoc");
                        try {
                            final List<String> readmeLines = Files.readAllLines(readmePath, charset);
                            final StringBuilder sb = new StringBuilder();
                            for (String line : readmeLines) {
                                line = line.trim();
                                if (line.startsWith("= ")) {
                                    final String title = line.substring(2).replace(": A Camel Quarkus example", "");
                                    sb.append(":cq-example-title: " + title + "\n");
                                } else if (line.startsWith(":")) {
                                    sb.append(line + "\n");
                                } else if (line.isEmpty()) {
                                    /* ignore */
                                } else {
                                    break;
                                }
                            }
                            sb.append(":cq-example-url: https://github.com/apache/camel-quarkus-examples/tree/master/"+ dirName +"\n");

                            final Path partialPath = partialsDirPath.resolve(partialFileName);
                            getLog().info("Updating " + partialPath);
                            Files.write(partialPath, sb.toString().getBytes(charset));
                        } catch (IOException e) {
                            throw new RuntimeException("Could not read " + readmePath, e);
                        }

                    });

        } catch (IOException e) {
            throw new RuntimeException("Could not list " + examplesDirPath, e);
        }

        /* Remove the stale files */
        try (Stream<Path> partials = Files.list(partialsDirPath)) {
            partials
                    .filter(p -> p.getFileName().toString().endsWith(".adoc"))
                    .filter(p -> !wantedPartials.contains(p.getFileName().toString()))
                    .forEach(p -> {
                        final Path partialPath = partialsDirPath.resolve(p);
                        getLog().info("Deleting a stale partial " + partialPath);
                        try {
                            Files.delete(partialPath);
                        } catch (IOException e) {
                            throw new RuntimeException("Could not delete " + p, e);
                        }
                    });
        } catch (IOException e) {
            throw new RuntimeException("Could not list " + partialsDirPath, e);
        }

    }

}
