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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.camel.tooling.model.ArtifactModel;
import org.apache.maven.model.Model;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import freemarker.template.Configuration;

/**
 * Updates {@code quarkus-extension.yaml} files in extension modules based on the info from Camel Catalog.
 */
@Mojo(name = "update-quarkus-metadata", requiresProject = true, inheritByDefault = false)
public class UpdateQuarkusMetadataMojo extends AbstractExtensionListMojo {

    private static final String NAME_PREFIX = "Camel Quarkus :: ";
    private static final String NAME_SUFFIX = " :: Runtime";

    /**
     * The root directory of the Camel Quarkus source tree
     *
     * @since 0.3.0
     */
    @Parameter(property = "cq.rootDir", defaultValue = "${project.basedir}")
    File rootDir;

    /**
     * URI prefix to use when looking up FreeMarker templates when generating {@code quarkus-extension.yaml} files.
     *
     * @since 0.3.0
     */
    @Parameter(defaultValue = CqUtils.DEFAULT_TEMPLATES_URI_BASE, required = true, property = "cq.templatesUriBase")
    String templatesUriBase;

    /**
     * Encoding to read and write files in the current source tree
     *
     * @since 0.0.1
     */
    @Parameter(defaultValue = CqUtils.DEFAULT_ENCODING, required = true, property = "cq.encoding")
    String encoding;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        final CqCatalog catalog = new CqCatalog();
        CqUtils.findExtensions(extensionDirectories.stream().map(File::toPath).sorted(),
                artifactIdBase -> !skipArtifactIdBases.contains(artifactIdBase))
                .forEach(extModule -> {
                    final String artifactIdBase = extModule.getArtifactIdBase();
                    final Path quarkusExtensionsYamlPath = extModule.getExtensionDir()
                            .resolve("runtime/src/main/resources/META-INF/quarkus-extension.yaml");
                    if (Files.exists(quarkusExtensionsYamlPath)) {
                        try {
                            final String oldContent = new String(Files.readAllBytes(quarkusExtensionsYamlPath),
                                    StandardCharsets.UTF_8);
                            if (/* oldContent.contains(SKIP_MARKER) || */ oldContent.contains("unlisted: true")) {
//                                getLog().info("Skipping a manually maintained or unlisted file "
//                                        + rootDir.toPath().relativize(quarkusExtensionsYamlPath));
                                return;
                            }
                        } catch (IOException e) {
                            throw new RuntimeException("Could not read " + quarkusExtensionsYamlPath, e);
                        }
                    }
                    //getLog().info("Regenerating " + rootDir.toPath().relativize(quarkusExtensionsYamlPath));
                    final List<ArtifactModel<?>> models = catalog.filterModels(artifactIdBase).stream()
                            .filter(CqCatalog::isFirstScheme)
                            .collect(Collectors.toList());
                    if (models.isEmpty()) {
                        //throw new IllegalStateException("Found zero models for " + extModule);
                    } else {
                        final Model runtimePom = CqUtils.readPom(extModule.getRuntimePomPath(), StandardCharsets.UTF_8);
                        final String name = runtimePom.getName();
                        if (!name.startsWith(NAME_PREFIX)) {
                            throw new RuntimeException("The name in " + extModule.getRuntimePomPath() +" must start with '"+ NAME_PREFIX +"'; found: " + name);
                        }
                        if (!name.endsWith(NAME_SUFFIX)) {
                            throw new RuntimeException("The name in " + extModule.getRuntimePomPath() +" must end with '"+ NAME_SUFFIX +"'; found: " + name);
                        }
                        final String title = name.substring(NAME_PREFIX.length(), name.length() - NAME_SUFFIX.length());
                        final String description;
                        if (models.size() == 1) {
                            description = models.get(0).getDescription();
                            System.out.println(description);
                        } else {
                            //System.out.println("models.size() = " + models.size());
                            if (runtimePom.getDescription() == null || runtimePom.getDescription().trim().isEmpty()) {
                                description = models.stream()
                                        .map(m -> m.getDescription())
                                        .collect(Collectors.toSet())
                                        .stream()
                                        .collect(Collectors.joining(" "));

                                //throw new RuntimeException("Description must be set in " + extModule.getRuntimePomPath());
                            } else {
                                description = runtimePom.getDescription();
                            }
                        }
                        final Set<String> keywords = models.stream()
                                .map(m -> m.getLabel())
                                .map(lbls -> lbls.split(","))
                                .flatMap(Stream::of)
                                .collect(Collectors.toCollection(TreeSet::new));

                        final TemplateParams templateParams = TemplateParams.builder()
                                .nameBase(title)
                                .description(description)
                                .keywords(keywords)
                                .nativeSupported(extModule.isNativeSupported())
                                .guideUrl(CqUtils.extensionDocUrl(rootDir.toPath(), artifactIdBase, models.get(0) /* FIXME */.getKind()))
                                .categories(org.l2x6.cq.CqUtils.DEFAULT_CATEGORIES)
                                .build();
                        final Configuration cfg = CqUtils.getTemplateConfig(rootDir.toPath(), CqUtils.DEFAULT_TEMPLATES_URI_BASE,
                                templatesUriBase, encoding);

                        CqUtils.evalTemplate(cfg, "quarkus-extension.yaml", quarkusExtensionsYamlPath, templateParams,
                                m -> {
                                });
                    }

                });
    }

}
