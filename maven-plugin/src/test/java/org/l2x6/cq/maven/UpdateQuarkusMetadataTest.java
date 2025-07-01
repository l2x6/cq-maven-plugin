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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.Set;
import java.util.stream.Stream;
import org.apache.maven.model.Model;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
