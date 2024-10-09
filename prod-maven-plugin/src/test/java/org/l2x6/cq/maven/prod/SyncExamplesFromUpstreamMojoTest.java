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
package org.l2x6.cq.maven.prod;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.apache.commons.io.FileUtils;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.Profile;
import org.apache.maven.model.Repository;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.l2x6.cq.common.CqCommonUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyncExamplesFromUpstreamMojoTest {
    private static final Path TMP_DIR = Paths.get(System.getProperty("java.io.tmpdir")).resolve("example-project-sync-testing");
    private static final Path UPSTREAM_EXAMPLES_DIR = TMP_DIR.resolve("upstream/camel-quarkus-examples-3.8.x");
    private static final Path PRODUCT_EXAMPLES_DIR = TMP_DIR.resolve("product/camel-quarkus-examples-3.8.x");
    private static final Path EXAMPLES = Paths.get("src/test/examples/community");
    private static final Path EXAMPLE_FOO = EXAMPLES.resolve("foo");
    private static final Path EXAMPLE_BAR = EXAMPLES.resolve("bar");

    @BeforeEach
    public void beforeEach() {
        try {
            Files.createDirectories(UPSTREAM_EXAMPLES_DIR);
            Files.createDirectories(PRODUCT_EXAMPLES_DIR);
            FileUtils.copyDirectory(EXAMPLE_FOO.toFile(), UPSTREAM_EXAMPLES_DIR.resolve("foo").toFile());
            FileUtils.copyDirectory(EXAMPLE_BAR.toFile(), UPSTREAM_EXAMPLES_DIR.resolve("bar").toFile());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @AfterEach
    public void afterEach() {
        try {
            FileUtils.deleteDirectory(TMP_DIR.toFile());
        } catch (IOException e) {
            // Ignored
        }
    }

    @ParameterizedTest
    @ValueSource(strings = { "3.8.5.redhat-00003", "3.8.5.temporary-redhat-00003", "3.8.5.SP1-temporary-redhat-00003" })
    void syncExamples(String quarkusPlatformVersion) throws Exception {
        SyncExamplesFromUpstreamMojo mojo = initMojo(quarkusPlatformVersion);

        boolean isTemporaryVersion = quarkusPlatformVersion.contains("temporary");
        Map<String, Set<SyncExamplesFromUpstreamMojo.GAV>> projectDependencies = new TreeMap<>();
        resolveDependencies(EXAMPLE_FOO, projectDependencies, isTemporaryVersion);
        resolveDependencies(EXAMPLE_BAR, projectDependencies, isTemporaryVersion);
        Files.walkFileTree(UPSTREAM_EXAMPLES_DIR, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                mojo.preSyncTransformers.stream()
                        .filter(sourceTransformer -> sourceTransformer.canApply(file))
                        .forEach(sourceTransformer -> sourceTransformer.apply(file));
                return FileVisitResult.CONTINUE;
            }
        });

        // Perform project sync
        mojo.syncProjects(projectDependencies);

        // bar project should not be synced since it uses unproductized camel-quarkus-zookeeper
        assertFalse(Files.exists(PRODUCT_EXAMPLES_DIR.resolve("bar")));

        // foo should be synced as all dependencies are productized
        Path projectFoo = PRODUCT_EXAMPLES_DIR.resolve("foo");
        assertTrue(Files.exists(projectFoo));

        // pom.xml should be updated with product versions
        Model model = CqCommonUtils.readPom(projectFoo.resolve("pom.xml"), StandardCharsets.UTF_8);
        Properties properties = model.getProperties();
        assertEquals("3.8.0.redhat-00001", model.getVersion());
        assertEquals("com.redhat.quarkus.platform", properties.getProperty("quarkus.platform.group-id"));
        assertEquals("quarkus-bom", properties.getProperty("quarkus.platform.artifact-id"));
        assertEquals(quarkusPlatformVersion, properties.getProperty("quarkus.platform.version"));
        assertEquals("${quarkus.platform.group-id}", properties.getProperty("camel-quarkus.platform.group-id"));
        assertEquals("quarkus-camel-bom", properties.getProperty("camel-quarkus.platform.artifact-id"));
        assertEquals("${quarkus.platform.version}", properties.getProperty("camel-quarkus.platform.version"));

        // GA repositories should be added
        List<Repository> repositories = model.getRepositories();
        assertEquals(2, repositories.size());
        assertEquals("redhat-ga-repository", repositories.get(0).getId());
        assertEquals("https://maven.repository.redhat.com/ga/", repositories.get(0).getUrl());
        assertTrue(repositories.get(0).getReleases().isEnabled());
        assertFalse(repositories.get(0).getSnapshots().isEnabled());
        assertEquals("redhat-earlyaccess-repository", repositories.get(1).getId());
        assertEquals("https://maven.repository.redhat.com/earlyaccess/all/", repositories.get(1).getUrl());
        assertTrue(repositories.get(1).getReleases().isEnabled());
        assertFalse(repositories.get(1).getSnapshots().isEnabled());

        List<Repository> pluginRepositories = model.getPluginRepositories();
        assertEquals(2, repositories.size());
        assertEquals("redhat-ga-repository", pluginRepositories.get(0).getId());
        assertEquals("https://maven.repository.redhat.com/ga/", pluginRepositories.get(0).getUrl());
        assertTrue(pluginRepositories.get(0).getReleases().isEnabled());
        assertFalse(pluginRepositories.get(0).getSnapshots().isEnabled());
        assertEquals("redhat-earlyaccess-repository", pluginRepositories.get(1).getId());
        assertEquals("https://maven.repository.redhat.com/earlyaccess/all/", pluginRepositories.get(1).getUrl());
        assertTrue(pluginRepositories.get(1).getReleases().isEnabled());
        assertFalse(pluginRepositories.get(1).getSnapshots().isEnabled());

        // kubernetes profile should not be present
        List<Profile> profiles = model.getProfiles();
        assertEquals(2, profiles.size());
        assertEquals("native", profiles.get(0).getId());
        assertEquals("openshift", profiles.get(1).getId());

        // quarkus-artemis-jms should not have an explicit version
        Optional<String> quarkusArtemisVersion = model.getDependencies()
                .stream()
                .filter(dependency -> dependency.getArtifactId().equals("quarkus-artemis-jms"))
                .map(Dependency::getVersion)
                .filter(Objects::nonNull)
                .findFirst();
        assertTrue(quarkusArtemisVersion.isEmpty());

        // Only OpenShift cloud deployment instructions should remain in the README
        String readme = Files.readString(projectFoo.resolve("README.adoc"));
        assertTrue(readme.contains("Deploying to OpenShift"));
        assertFalse(readme.contains("Deploying to Kubernetes"));

        // Only OpenShift manifests should remain
        assertFalse(Files.exists(projectFoo.resolve("src/main/resources/kubernetes/kubernetes.yml")));
        assertTrue(Files.exists(projectFoo.resolve("src/main/resources/kubernetes/openshift.yml")));
    }

    @ParameterizedTest
    @ValueSource(strings = { "3.15.1.redhat-00003", "3.15.1.temporary-redhat-00001", "3.15.1.SP1-temporary-redhat-00001",
            "3.1-invalid" })
    void exampleProjectVersion(String camelQuarkusPlatformVersion) {
        SyncExamplesFromUpstreamMojo mojo = initMojo(camelQuarkusPlatformVersion);

        if (camelQuarkusPlatformVersion.contains("invalid")) {
            assertThrows(IllegalArgumentException.class, mojo::getCamelQuarkusExamplesVersion);
        } else {
            assertEquals("3.15.0.redhat-00001", mojo.getCamelQuarkusExamplesVersion());
        }
    }

    private static SyncExamplesFromUpstreamMojo initMojo(String quarkusPlatformVersion) {
        SyncExamplesFromUpstreamMojo mojo = new SyncExamplesFromUpstreamMojo();
        mojo.quarkusPlatformVersion = quarkusPlatformVersion;
        mojo.quarkusPlatformGroupId = "com.redhat.quarkus.platform";
        mojo.quarkusPlatformArtifactId = "quarkus-bom";
        mojo.camelQuarkusPlatformArtifactId = "quarkus-camel-bom";
        mojo.tmpDir = UPSTREAM_EXAMPLES_DIR.getParent().toFile();
        mojo.syncToDir = PRODUCT_EXAMPLES_DIR.toFile();
        mojo.setUpSourceTransformations();
        return mojo;
    }

    private static void resolveDependencies(Path examplesDir,
            Map<String, Set<SyncExamplesFromUpstreamMojo.GAV>> projectDependencies, boolean isTemporaryVersion) {
        String versionSuffix = isTemporaryVersion ? ".temporary-redhat-00001" : ".redhat-00001";
        Model model = CqCommonUtils.readPom(examplesDir.resolve("pom.xml"), StandardCharsets.UTF_8);
        projectDependencies.put(examplesDir.getFileName().toString(), model.getDependencies()
                .stream()
                .map(dependency -> {
                    String version = dependency.getVersion();
                    if (version == null && !dependency.getArtifactId().equals("camel-quarkus-zookeeper")) {
                        version = "1.0.0" + versionSuffix;
                    }

                    Artifact artifact = new DefaultArtifact(
                            dependency.getGroupId() + ":" + dependency.getArtifactId() + ":" + version);
                    return new SyncExamplesFromUpstreamMojo.GAV(artifact, false);
                })
                .collect(Collectors.toUnmodifiableSet()));
    }
}
