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
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.apache.camel.tooling.model.ComponentModel;
import org.apache.maven.model.Model;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateQuarkusMetadataTest {
    static final Path extensionDir = Paths.get("target/test-extension");
    static final Path runtimeSrc = extensionDir.resolve("runtime/src/main/java/org/test");
    static final Path deploymentSrc = extensionDir.resolve("deployment/src/main/java/org/test");

    @BeforeEach
    public void beforeEach() throws IOException {
        Files.createDirectories(runtimeSrc);
        Files.createDirectories(deploymentSrc);
    }

    @AfterEach
    public void afterEach() throws IOException {
        try (Stream<Path> files = Files.walk(extensionDir)) {
            files.sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        }
    }

    @Test
    void resolveExtensionConfigPrefixFromJavaSource() {
        Stream.of("TestDeploymentConfig.java", "TestRuntimeConfig.java").forEach(sourceFile -> {
            try (InputStream stream = UpdateQuarkusMetadataMojo.class.getResourceAsStream("/" + sourceFile)) {
                if (stream == null) {
                    throw new IllegalStateException(sourceFile + " not found");
                }

                if (sourceFile.contains("Runtime")) {
                    Files.write(runtimeSrc.resolve(sourceFile), stream.readAllBytes());
                } else {
                    Files.write(deploymentSrc.resolve(sourceFile), stream.readAllBytes());
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });

        ExtensionModule extensionModule = new ExtensionModule(extensionDir, "test");
        Model model = new Model();
        Set<String> configPrefixes = UpdateQuarkusMetadataMojo.resolveConfigPrefixes(extensionModule, model);
        assertEquals(2, configPrefixes.size());
        assertTrue(configPrefixes.contains("quarkus.camel.foo"));
        assertTrue(configPrefixes.contains("quarkus.camel.bar"));
    }

    @Test
    void resolveDeploymentConfigPrefixFromMavenProperty() {
        ExtensionModule extensionModule = new ExtensionModule(extensionDir, "test");
        Model model = new Model();
        model.getProperties().put("quarkus.metadata.configPrefixes", "quarkus.camel.foo,quarkus.camel.bar");

        Set<String> configPrefixes = UpdateQuarkusMetadataMojo.resolveConfigPrefixes(extensionModule, model);
        assertEquals(2, configPrefixes.size());
        assertTrue(configPrefixes.contains("quarkus.camel.foo"));
        assertTrue(configPrefixes.contains("quarkus.camel.bar"));
    }

    @Test
    void resolveCatalogDescriptionSingleModel() {
        ComponentModel model = new ComponentModel();
        model.setDescription("Send messages to AWS SQS.");
        assertEquals("Send messages to AWS SQS.",
                UpdateQuarkusMetadataMojo.resolveCatalogDescription(List.of(model)));
    }

    @Test
    void resolveCatalogDescriptionSingleModelNull() {
        ComponentModel model = new ComponentModel();
        assertNull(UpdateQuarkusMetadataMojo.resolveCatalogDescription(List.of(model)));
    }

    @Test
    void resolveCatalogDescriptionSingleModelBlank() {
        ComponentModel model = new ComponentModel();
        model.setDescription("   ");
        assertNull(UpdateQuarkusMetadataMojo.resolveCatalogDescription(List.of(model)));
    }

    @Test
    void resolveCatalogDescriptionMultipleModelsDistinct() {
        ComponentModel m1 = new ComponentModel();
        m1.setDescription("Send messages to AWS SQS.");
        ComponentModel m2 = new ComponentModel();
        m2.setDescription("Receive messages from AWS SQS.");
        assertEquals("Send messages to AWS SQS. Receive messages from AWS SQS.",
                UpdateQuarkusMetadataMojo.resolveCatalogDescription(List.of(m1, m2)));
    }

    @Test
    void resolveCatalogDescriptionMultipleModelsDuplicate() {
        ComponentModel m1 = new ComponentModel();
        m1.setDescription("Manage files on AWS S3.");
        ComponentModel m2 = new ComponentModel();
        m2.setDescription("Manage files on AWS S3.");
        assertEquals("Manage files on AWS S3.",
                UpdateQuarkusMetadataMojo.resolveCatalogDescription(List.of(m1, m2)));
    }

    @Test
    void resolveCatalogDescriptionMultipleModelsWithBlank() {
        ComponentModel m1 = new ComponentModel();
        m1.setDescription("Send messages.");
        ComponentModel m2 = new ComponentModel();
        m2.setDescription("");
        ComponentModel m3 = new ComponentModel();
        m3.setDescription("Receive messages.");
        assertEquals("Send messages. Receive messages.",
                UpdateQuarkusMetadataMojo.resolveCatalogDescription(List.of(m1, m2, m3)));
    }

    @Test
    void resolveCatalogDescriptionMultipleModelsAllBlank() {
        ComponentModel m1 = new ComponentModel();
        m1.setDescription("");
        ComponentModel m2 = new ComponentModel();
        m2.setDescription("  ");
        assertNull(UpdateQuarkusMetadataMojo.resolveCatalogDescription(List.of(m1, m2)));
    }

    @Test
    void resolveCatalogDescriptionEmptyList() {
        assertNull(UpdateQuarkusMetadataMojo.resolveCatalogDescription(Collections.emptyList()));
    }

    @Test
    void pomDescriptionUpdated() throws IOException {
        Path pomFile = extensionDir.resolve("pom-update-desc.xml");
        Files.writeString(pomFile,
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                        + "<project>\n"
                        + "    <name>Test :: Runtime</name>\n"
                        + "    <description>Old description</description>\n"
                        + "</project>\n",
                StandardCharsets.UTF_8);

        UpdateQuarkusMetadataMojo.syncPomDescription(pomFile, "New catalog description", StandardCharsets.UTF_8);

        String result = Files.readString(pomFile, StandardCharsets.UTF_8);
        assertTrue(result.contains("<description>New catalog description</description>"));
        assertFalse(result.contains("Old description"));
    }

    @Test
    void pomDescriptionInsertedAfterName() throws IOException {
        Path pomFile = extensionDir.resolve("pom-insert-desc.xml");
        Files.writeString(pomFile,
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                        + "<project>\n"
                        + "    <packaging>jar</packaging>\n"
                        + "    <name>Test :: Runtime</name>\n"
                        + "    <url>foo</url>\n"
                        + "    <inceptionYear>2019</inceptionYear>\n"
                        + "</project>\n",
                StandardCharsets.UTF_8);

        UpdateQuarkusMetadataMojo.syncPomDescription(pomFile, "Inserted description", StandardCharsets.UTF_8);

        String result = Files.readString(pomFile, StandardCharsets.UTF_8);
        Assertions.assertThat(result)
                .contains("<description>Inserted description</description>");
        int namePos = result.indexOf("<name>");
        int descPos = result.indexOf("<description>");
        int urlPos = result.indexOf("<url>");
        assertTrue(namePos < descPos, "<description> should appear after <name>");
        assertTrue(descPos < urlPos, "<description> should appear before <url>");
    }
}
