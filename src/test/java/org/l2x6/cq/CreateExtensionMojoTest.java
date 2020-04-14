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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.maven.model.Build;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.PluginManagement;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;

public class CreateExtensionMojoTest {

    private static CreateExtensionMojo initMojo(final Path projectDir) throws IOException {
        final CreateExtensionMojo mojo = new CreateExtensionMojo();
        mojo.project = new MavenProject();
        final Path basePath = projectDir.toAbsolutePath().normalize();
        mojo.baseDir = basePath.toFile();
        mojo.nativeSupported = true;

        final Path pom = projectDir.resolve("pom.xml");
        if (Files.exists(pom)) {
            mojo.project.setFile(pom.toFile());
            final Model rawModel = CqUtils.readPom(pom, StandardCharsets.UTF_8);
            // the project would have an interpolated model at runtime, which we can't fully init here
            // here are just some key parts
            if (rawModel.getDependencyManagement() != null) {
                List<Dependency> deps = rawModel.getDependencyManagement().getDependencies();
                if (deps != null && !deps.isEmpty()) {
                    Dependency deploymentBom = null;
                    for (Dependency dep : deps) {
                        if (dep.getArtifactId().equals("quarkus-bom-deployment") && dep.getGroupId().equals("io.quarkus")) {
                            deploymentBom = dep;
                        }
                    }
                    if (deploymentBom != null) {
                        String version = deploymentBom.getVersion();
                        if (CreateExtensionMojo.QUARKUS_VERSION_POM_EXPR.equals(version)) {
                            version = rawModel.getProperties().getProperty(version.substring(2, version.length() - 1));
                            if (version == null) {
                                throw new IllegalStateException(
                                        "Failed to resolve " + deploymentBom.getVersion() + " from " + pom);
                            }
                        }
                        Dependency dep = new Dependency();
                        dep.setGroupId("io.quarkus");
                        dep.setArtifactId("quarkus-core-deployment");
                        dep.setType("jar");
                        dep.setVersion(version);
                        deps.add(dep);
                    }
                }
            }
            mojo.project.setModel(rawModel);
        }

        Build build = mojo.project.getBuild();
        if (build.getPluginManagement() == null) {
            build.setPluginManagement(new PluginManagement());
        }
        mojo.runtimeBom = basePath.resolve("boms/runtime/pom.xml").toFile();
        mojo.deploymentBom = basePath.resolve("boms/deployment/pom.xml").toFile();
        mojo.encoding = CqUtils.DEFAULT_ENCODING;
        mojo.templatesUriBase = CqUtils.DEFAULT_TEMPLATES_URI_BASE;
        mojo.quarkusVersion = CreateExtensionMojo.DEFAULT_QUARKUS_VERSION;
        mojo.bomEntryVersion = CreateExtensionMojo.DEFAULT_BOM_ENTRY_VERSION;
        mojo.nameSegmentDelimiter = CreateExtensionMojo.CQ_NAME_SEGMENT_DELIMITER;
        mojo.artifactIdPrefix = CreateExtensionMojo.CQ_ARTIFACT_ID_PREFIX;
        mojo.namePrefix = "Camel Quarkus :: ";
        mojo.extensionsDir = basePath.resolve(CreateExtensionMojo.CQ_EXTENSIONS_DIR).toFile();
        mojo.javaPackageInfix = CreateExtensionMojo.CQ_JAVA_PACKAGE_INFIX;
        mojo.additionalRuntimeDependencies = Arrays.asList(CreateExtensionMojo.CQ_ADDITIONAL_RUNTIME_DEPENDENCIES.split(","));
        mojo.extensionDirs = Collections.singletonList(new ExtensionDir("extensions", "camel-quarkus-"));

        return mojo;
    }

    private static Path createProjectFromTemplate(String testProjectName, String copyPrefix) throws IOException {
        final Path srcDir = Paths.get("src/test/resources/projects/" + testProjectName);
        /*
         * We want to run on the same project multiple times with different args so let's create a copy with a random
         * suffix
         */
        final Path copyDir = newProjectDir(copyPrefix);
        Files.walk(srcDir).forEach(source -> {
            final Path dest = copyDir.resolve(srcDir.relativize(source));
            try {
                Files.copy(source, dest);
            } catch (IOException e) {
                if (!Files.isDirectory(dest)) {
                    throw new RuntimeException(e);
                }
            }
        });
        return copyDir;
    }

    private static Path newProjectDir(String copyPrefix) throws IOException {
        int count = 0;
        while (count < 100) {
            Path path = Paths.get("target/test-classes/projects/" + copyPrefix);// + "-" + UUID.randomUUID().toString().substring(0, 7));
            if (!Files.exists(path)) {
                Files.createDirectories(path);
                return path;
            }
            count++;
        }

        // if we have tried too many times we just give up instead of looping forever which could cause the test to never end
        throw new RuntimeException("Unable to create a directory for copying the test application into");
    }

    @Test
    void createExtensionComponent() throws MojoExecutionException, MojoFailureException,
            IllegalArgumentException, IllegalAccessException, NoSuchFieldException, SecurityException, IOException {
        final String testName = "create-extension-component";
        final CreateExtensionMojo mojo = initMojo(createProjectFromTemplate("create-extension-pom", testName));
        mojo.artifactIdBase = "dozer";
        mojo.execute();

        assertTreesMatch(Paths.get("src/test/resources/expected/"+ testName),
                mojo.baseDir.toPath());
    }

    @Test
    void createExtensionComponentJvm() throws MojoExecutionException, MojoFailureException,
            IllegalArgumentException, IllegalAccessException, NoSuchFieldException, SecurityException, IOException {
        final String testName = "create-extension-component-jvm";
        final CreateExtensionMojo mojo = initMojo(createProjectFromTemplate("create-extension-pom", testName));
        mojo.artifactIdBase = "dozer";
        mojo.nativeSupported = false;
        mojo.execute();

        assertTreesMatch(Paths.get("src/test/resources/expected/"+ testName),
                mojo.baseDir.toPath());
    }

    @Test
    void createExtensionDataformat() throws MojoExecutionException, MojoFailureException,
            IllegalArgumentException, IllegalAccessException, NoSuchFieldException, SecurityException, IOException {
        final String testName = "create-extension-dataformat";
        final CreateExtensionMojo mojo = initMojo(createProjectFromTemplate("create-extension-pom", testName));
        mojo.artifactIdBase = "base64";

        mojo.execute();

        assertTreesMatch(Paths.get("src/test/resources/expected/"+ testName),
                mojo.baseDir.toPath());
    }


    @Test
    void createExtensionDataformatJvm() throws MojoExecutionException, MojoFailureException,
            IllegalArgumentException, IllegalAccessException, NoSuchFieldException, SecurityException, IOException {
        final String testName = "create-extension-dataformat-jvm";
        final CreateExtensionMojo mojo = initMojo(createProjectFromTemplate("create-extension-pom", testName));
        mojo.artifactIdBase = "base64";
        mojo.nativeSupported = false;

        mojo.execute();

        assertTreesMatch(Paths.get("src/test/resources/expected/"+ testName),
                mojo.baseDir.toPath());
    }

    @Test
    void createExtensionLanguage() throws MojoExecutionException, MojoFailureException,
            IllegalArgumentException, IllegalAccessException, NoSuchFieldException, SecurityException, IOException {
        final String testName = "create-extension-language";
        final CreateExtensionMojo mojo = initMojo(createProjectFromTemplate("create-extension-pom", testName));
        mojo.artifactIdBase = "xpath";
        mojo.execute();

        assertTreesMatch(Paths.get("src/test/resources/expected/" + testName ),
                mojo.baseDir.toPath());
    }

    @Test
    void createExtensionLanguageJvm() throws MojoExecutionException, MojoFailureException,
            IllegalArgumentException, IllegalAccessException, NoSuchFieldException, SecurityException, IOException {
        final String testName = "create-extension-language-jvm";
        final CreateExtensionMojo mojo = initMojo(createProjectFromTemplate("create-extension-pom", testName));
        mojo.artifactIdBase = "xpath";
        mojo.nativeSupported = false;
        mojo.execute();

        assertTreesMatch(Paths.get("src/test/resources/expected/" + testName ),
                mojo.baseDir.toPath());
    }

    @Test
    void createExtensionOther() throws MojoExecutionException, MojoFailureException,
            IllegalArgumentException, IllegalAccessException, NoSuchFieldException, SecurityException, IOException {
        final String testName = "create-extension-other";
        final CreateExtensionMojo mojo = initMojo(createProjectFromTemplate("create-extension-pom", testName));
        mojo.artifactIdBase = "attachments";
        mojo.execute();

        assertTreesMatch(Paths.get("src/test/resources/expected/" + testName),
                mojo.baseDir.toPath());
    }


    @Test
    void createExtensionOtherJvm() throws MojoExecutionException, MojoFailureException,
            IllegalArgumentException, IllegalAccessException, NoSuchFieldException, SecurityException, IOException {
        final String testName = "create-extension-other-jvm";
        final CreateExtensionMojo mojo = initMojo(createProjectFromTemplate("create-extension-pom", testName));
        mojo.artifactIdBase = "attachments";
        mojo.nativeSupported = false;
        mojo.execute();

        assertTreesMatch(Paths.get("src/test/resources/expected/" + testName),
                mojo.baseDir.toPath());
    }
    static void assertTreesMatch(Path expected, Path actual) throws IOException {
        final Set<Path> expectedFiles = new LinkedHashSet<>();
        Files.walk(expected).filter(Files::isRegularFile).forEach(p -> {
            final Path relative = expected.relativize(p);
            expectedFiles.add(relative);
            final Path actualPath = actual.resolve(relative);
            org.assertj.core.api.Assertions.assertThat(actualPath).hasSameTextualContentAs(p, StandardCharsets.UTF_8);
        });

        final Set<Path> unexpectedFiles = new LinkedHashSet<>();
        Files.walk(actual).filter(Files::isRegularFile).forEach(p -> {
            final Path relative = actual.relativize(p);
            if (!expectedFiles.contains(relative)) {
                unexpectedFiles.add(relative);
            }
        });
        if (!unexpectedFiles.isEmpty()) {
            fail(String.format("Files found under [%s] but not defined as expected under [%s]:%s", actual,
                    expected, unexpectedFiles.stream().map(Path::toString).collect(Collectors.joining("\n    "))));
        }
    }

    @Test
    void getPackage() throws IOException {
        assertEquals("org.apache.camel.quarkus.aws.sns.deployment", CreateExtensionMojo
                .getJavaPackage("org.apache.camel.quarkus", null, "camel-quarkus-aws-sns-deployment"));
        assertEquals("org.apache.camel.quarkus.component.aws.sns.deployment", CreateExtensionMojo
                .getJavaPackage("org.apache.camel.quarkus", "component", "camel-quarkus-aws-sns-deployment"));
    }

    @Test
    void toCapCamelCase() throws IOException {
        assertEquals("FooBarBaz", CreateExtensionMojo.toCapCamelCase("foo-bar-baz"));
    }

    @Test
    void toSnakeCase() throws IOException {
        assertEquals("foo_bar_baz", CreateExtensionMojo.toSnakeCase("Foo-bar-baz"));
    }

}
