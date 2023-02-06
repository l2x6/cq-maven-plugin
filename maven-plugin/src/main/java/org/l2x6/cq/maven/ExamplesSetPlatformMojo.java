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

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.maven.model.Model;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.l2x6.cq.common.CqCommonUtils;
import org.l2x6.pom.tuner.PomTransformer;
import org.l2x6.pom.tuner.PomTransformer.ContainerElement;
import org.l2x6.pom.tuner.PomTransformer.SimpleElementWhitespace;
import org.l2x6.pom.tuner.PomTransformer.TransformationContext;
import org.w3c.dom.Document;

/**
 * Sets either the just released Quarkus platform on all examples under the current directory via
 * {@code -Dcq.quarkus.platform.version=...}
 * or sets the {@code camel-quarkus-bom} instead via {@code -Dcq.camel-quarkus.version=...}
 * <p>
 * Optionally can also set the project versions via {@code -Dcq.newVersion=...}.
 *
 * @since 2.10.0
 */
@Mojo(name = "examples-set-platform", requiresProject = false)
public class ExamplesSetPlatformMojo extends AbstractMojo {

    private static final String CQ_CAMEL_QUARKUS_VERSION = "cq.camel-quarkus.version";
    private static final String CQ_QUARKUS_PLATFORM_VERSION = "cq.quarkus.platform.version";
    /**
     * Directory where the changes should be performed. Default is the current directory of the current Java process.
     *
     * @since 2.10.0
     */
    @Parameter(property = "cq.basedir", defaultValue = "${project.basedir}")
    File basedir;
    private Path basePath;

    /**
     * Encoding to read and write files in the current source tree
     *
     * @since 2.10.0
     */
    @Parameter(defaultValue = CqUtils.DEFAULT_ENCODING, required = true, property = "cq.encoding")
    String encoding;
    Charset charset;

    /**
     * The Quarkus platform version to use in {@code <quarkus.platform.version>} and
     * {@code <camel-quarkus.platform.version>}.
     * If you set this, do not set {@link #camelQuarkusVersion}.
     *
     * @since 2.10.0
     */
    @Parameter(property = CQ_QUARKUS_PLATFORM_VERSION)
    String quarkusPlatformVersion;

    /**
     * The Camel Quarkus version to use in {@code <camel-quarkus.platform.version>}.
     * {@code <quarkus.platform.version>} will be set to the quarkus version available in {@code camel-quarkus-bom}.
     * If you set this, do not set {@link #quarkusPlatformVersion}.
     *
     * @since 2.10.0
     */
    @Parameter(property = CQ_CAMEL_QUARKUS_VERSION)
    String camelQuarkusVersion;

    /**
     * Skip the execution of this mojo.
     *
     * @since 2.10.0
     */
    @Parameter(property = "cq.examples.skip", defaultValue = "false")
    boolean skip;

    /**
     * The project version to set
     *
     * @since 2.10.0
     */
    @Parameter(property = "cq.newVersion")
    String newVersion;

    @Parameter(defaultValue = "${settings.localRepository}", readonly = true)
    String localRepository;

    /**
     * How to format simple XML elements ({@code <elem/>}) - with or without space before the slash.
     *
     * @since 2.10.0
     */
    @Parameter(property = "cq.simpleElementWhitespace", defaultValue = "EMPTY")
    SimpleElementWhitespace simpleElementWhitespace;

    @Parameter(defaultValue = "${plugin}", readonly = true)
    private PluginDescriptor plugin;

    boolean isChecking() {
        return false;
    }

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("Skipping as requested by the user");
            return;
        }
        basePath = basedir != null ? basedir.toPath().toAbsolutePath().normalize() : Paths.get(".");
        charset = Charset.forName(encoding);

        if (isChecking()) {
            if (quarkusPlatformVersion != null) {
                throw new MojoFailureException(CQ_QUARKUS_PLATFORM_VERSION + " should be null in checking mode");
            }
            if (camelQuarkusVersion != null) {
                throw new MojoFailureException(CQ_CAMEL_QUARKUS_VERSION + " should be null in checking mode");
            }

            try (Stream<Path> dirs = Files.list(basePath)) {
                final Path firstPomXml = dirs
                        .map(dir -> dir.resolve("pom.xml"))
                        .filter(Files::isRegularFile)
                        .findFirst()
                        .orElseThrow(() -> new RuntimeException("Could not find any example project under " + basePath));

                final Model fistModel = CqCommonUtils.readPom(firstPomXml, charset);
                final Properties props = fistModel.getProperties();
                final String cqBomVersion = props.getProperty("camel-quarkus.platform.version");
                if (!cqBomVersion.startsWith("$")) {
                    camelQuarkusVersion = cqBomVersion;
                } else {
                    final String quarkusBomVersion = props.getProperty("quarkus.platform.version");
                    if (!quarkusBomVersion.startsWith("$")) {
                        quarkusPlatformVersion = quarkusBomVersion;
                    } else {
                        throw new MojoFailureException(
                                "One of camel-quarkus.platform.version and quarkus.platform.version in " + firstPomXml
                                        + " must be a literal. Found: "
                                        + camelQuarkusVersion + " and " + quarkusBomVersion);
                    }
                }
            } catch (IOException e) {
                throw new RuntimeException("Could not list " + basePath, e);
            }

        } else {
            if (quarkusPlatformVersion != null && camelQuarkusVersion != null) {
                throw new MojoFailureException(
                        "Set only one of " + CQ_QUARKUS_PLATFORM_VERSION + " and " + CQ_CAMEL_QUARKUS_VERSION);
            }
        }

        final String quarkusBomGroupId;
        final String quarkusBomArtifactId;
        final String quarkusBomVersion;
        final String cqBomGroupId;
        final String cqBomArtifactId;
        final String cqBomVersion;
        final String cqVersion;
        if (quarkusPlatformVersion != null) {
            quarkusBomGroupId = "io.quarkus.platform";
            quarkusBomArtifactId = "quarkus-bom";
            quarkusBomVersion = quarkusPlatformVersion;

            cqBomGroupId = "${quarkus.platform.group-id}";
            cqBomArtifactId = "quarkus-camel-bom";
            cqBomVersion = "${quarkus.platform.version}";

            cqVersion = findCamelQuarkusVersion(Paths.get(localRepository), charset, quarkusPlatformVersion);
        } else {
            quarkusBomGroupId = "io.quarkus";
            quarkusBomArtifactId = "quarkus-bom";
            quarkusBomVersion = findQuarkusVersion(Paths.get(localRepository), charset, camelQuarkusVersion);

            cqBomGroupId = "org.apache.camel.quarkus";
            cqBomArtifactId = "camel-quarkus-bom";
            cqBomVersion = camelQuarkusVersion;

            cqVersion = "${camel-quarkus.platform.version}";
        }
        final List<String> issues = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(basePath)) {
            dirs
                    .map(dir -> dir.resolve("pom.xml"))
                    .filter(Files::isRegularFile)
                    .forEach(pomXmlPath -> {
                        if (isChecking()) {
                            final Model model = CqCommonUtils.readPom(pomXmlPath, charset);
                            final Properties props = model.getProperties();

                            assertRequiredProperty(pomXmlPath, props, "quarkus.platform.group-id", quarkusBomGroupId, issues);
                            assertRequiredProperty(pomXmlPath, props, "quarkus.platform.artifact-id", quarkusBomArtifactId,
                                    issues);
                            assertRequiredProperty(pomXmlPath, props, "quarkus.platform.version", quarkusBomVersion, issues);

                            assertRequiredProperty(pomXmlPath, props, "camel-quarkus.platform.group-id", cqBomGroupId, issues);
                            assertRequiredProperty(pomXmlPath, props, "camel-quarkus.platform.artifact-id", cqBomArtifactId,
                                    issues);
                            assertRequiredProperty(pomXmlPath, props, "camel-quarkus.platform.version", cqBomVersion, issues);

                            if (props.containsKey("camel-quarkus.version")) {
                                assertRequiredProperty(pomXmlPath, props, "camel-quarkus.version", cqVersion, issues);
                            }
                        } else {
                            new PomTransformer(pomXmlPath, charset, simpleElementWhitespace).transform(
                                    (Document document, TransformationContext context) -> {
                                        if (newVersion != null && !newVersion.isEmpty()) {
                                            context.getContainerElement("project", "version")
                                                    .ifPresent(version -> version.getNode().setTextContent(newVersion));
                                        }
                                        final ContainerElement props = context.getOrAddContainerElement("properties");

                                        setRequiredProperty(pomXmlPath, props, "quarkus.platform.group-id", quarkusBomGroupId);
                                        setRequiredProperty(pomXmlPath, props, "quarkus.platform.artifact-id",
                                                quarkusBomArtifactId);
                                        setRequiredProperty(pomXmlPath, props, "quarkus.platform.version", quarkusBomVersion);

                                        setRequiredProperty(pomXmlPath, props, "camel-quarkus.platform.group-id",
                                                cqBomGroupId);
                                        setRequiredProperty(pomXmlPath, props, "camel-quarkus.platform.artifact-id",
                                                cqBomArtifactId);
                                        setRequiredProperty(pomXmlPath, props, "camel-quarkus.platform.version",
                                                cqBomVersion);

                                        props.getChildContainerElement("camel-quarkus.version")
                                                .ifPresent(v -> v.getNode().setTextContent(cqVersion));

                                    });
                        }
                    });

        } catch (IOException e) {
            throw new RuntimeException("Could not list " + basePath, e);
        }

        if (isChecking() && !issues.isEmpty()) {
            final String param = quarkusPlatformVersion != null
                    ? "-D" + CQ_QUARKUS_PLATFORM_VERSION + "=" + quarkusPlatformVersion
                    : "-D" + CQ_CAMEL_QUARKUS_VERSION + "=" + camelQuarkusVersion;
            throw new MojoFailureException(
                    "Found " + issues.size() + " consistency issues:\n - "
                            + issues.stream().collect(Collectors.joining("\n - "))
                            + "\n\nYou may want to run mvn org.l2x6.cq:cq-maven-plugin:" + plugin.getVersion()
                            + ":examples-set-platform " + param);
        }
    }

    static void assertRequiredProperty(Path pomXmlPath, Properties props, String key, String expectedValue,
            List<String> issues) {
        final String actual = props.getProperty(key);
        if (!expectedValue.equals(actual)) {
            issues.add("Expected <" + key + ">" + expectedValue + "</" + key + ">, found " + actual + " in " + pomXmlPath);
        }
    }

    static String findQuarkusVersion(Path localRepository, Charset charset, String camelQuarkusVersion) {
        final Path cqPomPath = CqCommonUtils.copyArtifact(
                localRepository,
                "org.apache.camel.quarkus",
                "camel-quarkus",
                camelQuarkusVersion,
                "pom",
                Collections.singletonList("https://repo1.maven.org/maven2"));
        final Model cqModel = CqCommonUtils.readPom(cqPomPath, charset);
        final String v = cqModel.getProperties().entrySet().stream()
                .filter(prop -> prop.getKey().equals("quarkus.version"))
                .map(Entry::getValue)
                .map(Object::toString)
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Could not find <quarkus.version> in " + cqPomPath));
        if (v.startsWith("$")) {
            throw new RuntimeException(
                    "The version of io.quarkus:quarkus-bom in " + cqPomPath + " should be a literal; found: " + v);
        }
        return v;
    }

    static String findCamelQuarkusVersion(Path localRepository, Charset charset, String quarkusPlatformVersion) {
        final Path cqPomPath = CqCommonUtils.copyArtifact(
                localRepository,
                "io.quarkus.platform",
                "quarkus-camel-bom",
                quarkusPlatformVersion,
                "pom",
                Collections.singletonList("https://repo1.maven.org/maven2"));
        final Model cqModel = CqCommonUtils.readPom(cqPomPath, charset);
        final String v = cqModel.getDependencyManagement().getDependencies().stream()
                .filter(dep -> dep.getGroupId().equals("org.apache.camel.quarkus"))
                .findFirst()
                .orElseThrow(() -> new RuntimeException(
                        "Could not find any managed dependency having org.apache.camel.quarkus groupId in " + cqPomPath))
                .getVersion();
        if (v.startsWith("$")) {
            throw new RuntimeException(
                    "Camel Quarkus version on the first managed dependency having org.apache.camel.quarkus groupId should be a literal; found: "
                            + v);
        }
        return v;
    }

    static void setRequiredProperty(Path pomXmlPath, ContainerElement props, String name, String value) {
        props
                .getChildContainerElement(name)
                .orElseThrow(() -> new RuntimeException("Could not find <" + name + "> property in " + pomXmlPath))
                .getNode().setTextContent(value);
    }
}
