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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

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
        mojo.basedir = basePath.toFile();
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
        mojo.createConvenienceDirs = false;

        return mojo;
    }

    @Test
    void createExtensionComponent() throws MojoExecutionException, MojoFailureException,
            IllegalArgumentException, IllegalAccessException, NoSuchFieldException, SecurityException, IOException {
        final String testName = "create-extension-component";
        final CreateExtensionMojo mojo = initMojo(TestUtils.createProjectFromTemplate("create-extension-pom", testName));
        mojo.artifactIdBase = "dozer";
        mojo.execute();

        TestUtils.assertTreesMatch(Paths.get("src/test/resources/expected/"+ testName),
                mojo.basedir.toPath());
    }

    @Test
    void createExtensionComponentJvm() throws MojoExecutionException, MojoFailureException,
            IllegalArgumentException, IllegalAccessException, NoSuchFieldException, SecurityException, IOException {
        final String testName = "create-extension-component-jvm";
        final CreateExtensionMojo mojo = initMojo(TestUtils.createProjectFromTemplate("create-extension-pom", testName));
        mojo.artifactIdBase = "dozer";
        mojo.nativeSupported = false;
        mojo.execute();

        TestUtils.assertTreesMatch(Paths.get("src/test/resources/expected/"+ testName),
                mojo.basedir.toPath());
    }

    @Test
    void createExtensionDataformat() throws MojoExecutionException, MojoFailureException,
            IllegalArgumentException, IllegalAccessException, NoSuchFieldException, SecurityException, IOException {
        final String testName = "create-extension-dataformat";
        final CreateExtensionMojo mojo = initMojo(TestUtils.createProjectFromTemplate("create-extension-pom", testName));
        mojo.artifactIdBase = "base64";

        mojo.execute();

        TestUtils.assertTreesMatch(Paths.get("src/test/resources/expected/"+ testName),
                mojo.basedir.toPath());
    }


    @Test
    void createExtensionDataformatJvm() throws MojoExecutionException, MojoFailureException,
            IllegalArgumentException, IllegalAccessException, NoSuchFieldException, SecurityException, IOException {
        final String testName = "create-extension-dataformat-jvm";
        final CreateExtensionMojo mojo = initMojo(TestUtils.createProjectFromTemplate("create-extension-pom", testName));
        mojo.artifactIdBase = "base64";
        mojo.nativeSupported = false;

        mojo.execute();

        TestUtils.assertTreesMatch(Paths.get("src/test/resources/expected/"+ testName),
                mojo.basedir.toPath());
    }

    @Test
    void createExtensionLanguage() throws MojoExecutionException, MojoFailureException,
            IllegalArgumentException, IllegalAccessException, NoSuchFieldException, SecurityException, IOException {
        final String testName = "create-extension-language";
        final CreateExtensionMojo mojo = initMojo(TestUtils.createProjectFromTemplate("create-extension-pom", testName));
        mojo.artifactIdBase = "xpath";
        mojo.execute();

        TestUtils.assertTreesMatch(Paths.get("src/test/resources/expected/" + testName ),
                mojo.basedir.toPath());
    }

    @Test
    void createExtensionLanguageJvm() throws MojoExecutionException, MojoFailureException,
            IllegalArgumentException, IllegalAccessException, NoSuchFieldException, SecurityException, IOException {
        final String testName = "create-extension-language-jvm";
        final CreateExtensionMojo mojo = initMojo(TestUtils.createProjectFromTemplate("create-extension-pom", testName));
        mojo.artifactIdBase = "xpath";
        mojo.nativeSupported = false;
        mojo.execute();

        TestUtils.assertTreesMatch(Paths.get("src/test/resources/expected/" + testName ),
                mojo.basedir.toPath());
    }

    @Test
    void createExtensionOther() throws MojoExecutionException, MojoFailureException,
            IllegalArgumentException, IllegalAccessException, NoSuchFieldException, SecurityException, IOException {
        final String testName = "create-extension-other";
        final CreateExtensionMojo mojo = initMojo(TestUtils.createProjectFromTemplate("create-extension-pom", testName));
        mojo.artifactIdBase = "attachments";
        mojo.execute();

        TestUtils.assertTreesMatch(Paths.get("src/test/resources/expected/" + testName),
                mojo.basedir.toPath());
    }


    @Test
    void createExtensionOtherJvm() throws MojoExecutionException, MojoFailureException,
            IllegalArgumentException, IllegalAccessException, NoSuchFieldException, SecurityException, IOException {
        final String testName = "create-extension-other-jvm";
        final CreateExtensionMojo mojo = initMojo(TestUtils.createProjectFromTemplate("create-extension-pom", testName));
        mojo.artifactIdBase = "attachments";
        mojo.nativeSupported = false;
        mojo.execute();

        TestUtils.assertTreesMatch(Paths.get("src/test/resources/expected/" + testName),
                mojo.basedir.toPath());
    }
    @Test
    void getPackage() throws IOException {
        assertEquals("org.apache.camel.quarkus.aws.sns.deployment", CqUtils
                .getJavaPackage("org.apache.camel.quarkus", null, "camel-quarkus-aws-sns-deployment"));
        assertEquals("org.apache.camel.quarkus.component.aws.sns.deployment", CqUtils
                .getJavaPackage("org.apache.camel.quarkus", "component", "camel-quarkus-aws-sns-deployment"));
    }

}
