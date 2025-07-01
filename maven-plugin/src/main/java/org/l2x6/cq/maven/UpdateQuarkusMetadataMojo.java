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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.camel.tooling.model.ArtifactModel;
import org.apache.maven.model.Model;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.l2x6.cq.common.CqCatalog;
import org.l2x6.cq.common.CqCatalog.Flavor;
import org.l2x6.cq.common.CqCommonUtils;
import org.l2x6.cq.common.ExtensionStatus;

/**
 * Updates {@code quarkus-extension.yaml} files in extension modules based on the info from Camel Catalog.
 */
@Mojo(name = "update-quarkus-metadata", requiresProject = true, inheritByDefault = false)
public class UpdateQuarkusMetadataMojo extends AbstractExtensionListMojo {

    private static final String NAME_SUFFIX = " :: Runtime";
    private static final Pattern CONFIG_PREFIX_PATTERN = Pattern
            .compile("prefix\\s*=\\s*\"([^\"]+)\"");

    /**
     * URI prefix to use when looking up FreeMarker templates when generating {@code quarkus-extension.yaml} files.
     * {@code file:} URIs will be resolved relative to {@link #rootDir}.
     *
     * @since 0.3.0
     */
    @Parameter(defaultValue = CqUtils.DEFAULT_TEMPLATES_URI_BASE, required = true, property = "cq.templatesUriBase")
    String templatesUriBase;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        final CqCatalog catalog = new CqCatalog(Flavor.camel);
        final List<String> errors = new ArrayList<>();
        findExtensions()
                .filter(extModule -> !extModule.getArtifactIdBase().startsWith("support-"))
                .forEach(extModule -> {
                    final String artifactIdBase = extModule.getArtifactIdBase();
                    final Path quarkusExtensionsYamlPath = extModule.getExtensionDir()
                            .resolve("runtime/src/main/resources/META-INF/quarkus-extension.yaml");
                    getLog().info("Regenerating " + multiModuleProjectDirectory.toPath().relativize(quarkusExtensionsYamlPath));
                    final List<ArtifactModel<?>> models = catalog.primaryModel(artifactIdBase);
                    final Model runtimePom = CqCommonUtils.readPom(extModule.getRuntimePomPath(), StandardCharsets.UTF_8);
                    final Path relativeRuntimePomPath = multiModuleProjectDirectory.toPath()
                            .relativize(extModule.getRuntimePomPath());

                    final String name = runtimePom.getName();
                    if (!name.endsWith(NAME_SUFFIX)) {
                        throw new RuntimeException("The name in " + relativeRuntimePomPath + " must end with '" + NAME_SUFFIX
                                + "'; found: " + name);
                    }
                    final int startDelimPos = name.lastIndexOf(" :: ", name.length() - NAME_SUFFIX.length() - 1);
                    if (startDelimPos < 0) {
                        throw new RuntimeException(
                                "The name in " + relativeRuntimePomPath + " must start with '<whatever> :: '; found: " + name);
                    }
                    final String titleBase = name.substring(startDelimPos + 4, name.length() - NAME_SUFFIX.length());
                    final String rawKeywords = runtimePom.getProperties().getProperty("quarkus.metadata.keywords");
                    final Set<String> configPrefixes = resolveConfigPrefixes(extModule, runtimePom);
                    final List<String> keywords = rawKeywords != null ? Arrays.asList(rawKeywords.split(","))
                            : Collections.emptyList();
                    final boolean unlisted = runtimePom.getProperties().containsKey("quarkus.metadata.unlisted")
                            ? Boolean.parseBoolean(runtimePom.getProperties().getProperty("quarkus.metadata.unlisted"))
                            : !extModule.isNativeSupported();
                    final boolean deprecated = models.stream().anyMatch(ArtifactModel::isDeprecated) || Boolean
                            .parseBoolean(runtimePom.getProperties().getProperty("quarkus.metadata.deprecated", "false"));

                    final ExtensionStatus status = ExtensionStatus.valueOf(runtimePom.getProperties().getProperty(
                            "quarkus.metadata.status", ExtensionStatus.of(extModule.isNativeSupported()).toString()));

                    final TemplateParams templateParams = CqUtils.quarkusExtensionYamlParams(models, artifactIdBase, titleBase,
                            runtimePom.getDescription(), configPrefixes, keywords, unlisted, deprecated,
                            extModule.isNativeSupported(), status,
                            multiModuleProjectDirectory.toPath(), getLog(), errors);
                    final Configuration cfg = CqUtils.getTemplateConfig(multiModuleProjectDirectory.toPath(),
                            CqUtils.DEFAULT_TEMPLATES_URI_BASE,
                            templatesUriBase, encoding);

                    CqUtils.evalTemplate(cfg, "quarkus-extension.yaml", quarkusExtensionsYamlPath, templateParams,
                            m -> {
                            });

                });
        if (!errors.isEmpty()) {
            throw new MojoFailureException(errors.stream().collect(Collectors.joining("\n")));
        }
    }

    static Set<String> resolveConfigPrefixes(ExtensionModule extension, Model runtimePom) {
        Set<String> configPrefixes = new TreeSet<>();

        // Try to determine extension config prefixes from a property in the runtime pom.xml or fallback to parsing config Java source code
        String runtimePomConfigPrefixes = runtimePom.getProperties().getProperty("quarkus.metadata.configPrefixes");
        if (runtimePomConfigPrefixes != null) {
            configPrefixes.addAll(Arrays.asList(runtimePomConfigPrefixes.split(",")));
        } else {
            try (Stream<Path> stream = Files.walk(extension.getExtensionDir())) {
                stream.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith("Config.java"))
                        .forEach(path -> {
                            try {
                                Matcher matcher = CONFIG_PREFIX_PATTERN.matcher(Files.readString(path));
                                if (matcher.find()) {
                                    configPrefixes.add(matcher.group(1));
                                }
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        });
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        return configPrefixes;
    }

}
