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
package org.l2x6.cq.maven.prod;

import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.apache.camel.catalog.Kind;
import org.apache.camel.tooling.model.ArtifactModel;
import org.apache.camel.tooling.model.BaseModel;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.l2x6.cq.common.CqCatalog;
import org.l2x6.cq.common.CqCatalog.Flavor;
import org.l2x6.cq.common.CqCatalog.GavCqCatalog;

/**
 * Exports the list of components, languages, data formats and others to a CSV file.
 */
@Mojo(name = "export-csv", threadSafe = true, requiresProject = false)
public class ExportComponentCsvMojo extends AbstractMojo {

    /**
     * The maven project.
     */
    @Parameter(property = "project", required = true, readonly = true)
    protected MavenProject project;

    @Parameter(required = true, property = "cq.camelQuarkusCatalogVersion")
    protected String camelQuarkusCatalogVersion;

    @Parameter(required = true, property = "cq.camelCatalogVersion")
    protected String camelCatalogVersion;

    /**
     * The directory where to store the output files.
     */
    @Parameter(defaultValue = ".")
    protected File outputDir;

    @Parameter(defaultValue = "${settings.localRepository}", readonly = true)
    String localRepository;

    /**
     * Execute goal.
     *
     * @throws MojoExecutionException execution of the main class or one of the
     *                                threads it generated failed.
     * @throws MojoFailureException   something bad happened...
     */
    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        final Path localRepositoryPath = Paths.get(localRepository);
        final Path outputPath = outputDir.toPath();
        try (GavCqCatalog camelCatalog = GavCqCatalog.open(localRepositoryPath, Flavor.camel, camelCatalogVersion);
                GavCqCatalog camelQuarkusCatalog = GavCqCatalog.open(localRepositoryPath, Flavor.camelQuarkus,
                        camelQuarkusCatalogVersion)) {
            CqCatalog.kinds().forEach(kind -> {
                final Path outputFile = outputPath.resolve(kind.name() + "s.csv");
                try (Writer out = Files.newBufferedWriter(outputFile, StandardCharsets.UTF_8)) {
                    out.write(
                            "Priority\tGA product target\tTP2 done\tName\tScheme\tartifactId\tKind\tDeprecated\tCQ community\tProduct\tCommunity issue\tIntegration test\tSprint\tComment\n");
                    camelCatalog.models(kind)
                            .filter(CqCatalog::isFirstScheme)
                            .sorted(CqCatalog.compareArtifactId().thenComparing(BaseModel.compareTitle()))
                            .forEach(model -> {
                                // prio
                                try {
                                    out.write("\t"); // empty prio
                                    out.write("\t"); // empty GA product target
                                    out.write("\t"); // TP2 done
                                    out.write(model.getTitle());
                                    out.write('\t');
                                    out.write(model.getName());
                                    out.write('\t');
                                    out.write(model.getArtifactId());
                                    out.write('\t');
                                    out.write(model.getKind());
                                    out.write('\t');
                                    out.write(String.valueOf(model.isDeprecated()));
                                    out.write('\t');
                                    out.write(quarkusCommunitySupport(camelQuarkusCatalog, kind, model));
                                    out.write('\n');
                                } catch (IOException e) {
                                    throw new RuntimeException("Could not write to " + outputFile, e);
                                }
                            });
                } catch (IOException e) {
                    throw new RuntimeException("Could not write to " + outputFile, e);
                }
            });
        }
    }

    private String quarkusCommunitySupport(GavCqCatalog camelQuarkusCatalog, Kind kind, ArtifactModel<?> model) {
        try {
            BaseModel<?> cqModel = camelQuarkusCatalog.load(kind, model.getName());
            return cqModel.isNativeSupported() ? "Native" : "JVM";
        } catch (RuntimeException e) {
            if (e.getCause() instanceof NoSuchFileException) {
                return "n/a";
            }
            throw e;
        }
    }

    static String primaryGroup(Kind kind, String rawLabels, String name) {
        if (kind != Kind.component) {
            return kind.name();
        }
        if (name.startsWith("aws")) {
            return "aws";
        }
        if (name.startsWith("azure")) {
            return "azure";
        }
        if (name.startsWith("google")) {
            return "google";
        }
        if (name.startsWith("spring")) {
            return "spring";
        }
        if (name.startsWith("kubernetes") || name.startsWith("openshift") || name.startsWith("openstack")
                || name.startsWith("digitalocean")) {
            return "cloud";
        }
        if (rawLabels != null) {
            final Set<String> labels = new HashSet<>(Arrays.asList(rawLabels.split(",")));
            if (labels.contains("core")) {
                return "core";
            } else if (labels.contains("file") || labels.contains("document")) {
                return "file";
            } else if (labels.contains("http") || labels.contains("websocket")) {
                return "http";
            } else if (labels.contains("messaging")) {
                return "messaging";
            } else if (labels.contains("database") || labels.contains("nosql") || labels.contains("sql")
                    || labels.contains("bigdata")) {
                return "database";
            } else if (labels.contains("clustering")) {
                return "clustering";
            } else if (labels.contains("monitoring")) {
                return "monitoring";
            } else if (labels.contains("api")) {
                return "api";
            } else if (labels.contains("cache")) {
                return "cache";
            } else if (labels.size() == 1) {
                return labels.iterator().next();
            }
        }
        return "";
    }

}
