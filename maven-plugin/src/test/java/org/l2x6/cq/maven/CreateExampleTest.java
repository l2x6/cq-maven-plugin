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

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.junit.jupiter.api.Test;
import org.l2x6.cq.test.utils.TestUtils;

import static org.l2x6.cq.maven.CreateExampleMojo.DEFAULT_TEMPLATES_URI_BASE;

public class CreateExampleTest {
    private static CreateExampleMojo initMojo(final Path projectDir) {
        CreateExampleMojo mojo = new CreateExampleMojo();
        final Path basePath = projectDir.toAbsolutePath().normalize();
        mojo.basedir = basePath.toFile();
        mojo.encoding = CqUtils.DEFAULT_ENCODING;
        mojo.templatesUriBase = DEFAULT_TEMPLATES_URI_BASE;

        return mojo;
    }

    @Test
    void createExampleProject() throws IOException, MojoExecutionException, MojoFailureException {
        final String testName = "create-example";
        final Path basePath = TestUtils.createProjectFromTemplate("create-example", testName);
        final CreateExampleMojo mojo = initMojo(basePath);

        mojo.artifactIdBase = "foo-bar";
        mojo.exampleName = "Foo Bar With Cheese";
        mojo.exampleDescription = "Shows how to use the Foo Bar feature in Camel Quarkus";
        mojo.execute();

        TestUtils.assertTreesMatch(Paths.get("src/test/expected/" + testName), basePath);
    }

    @Test
    void createExampleProjectWithoutExplicitName() throws IOException, MojoExecutionException, MojoFailureException {
        final String testName = "create-example-without-name";
        final Path basePath = TestUtils.createProjectFromTemplate("create-example", testName);
        final CreateExampleMojo mojo = initMojo(basePath);

        mojo.artifactIdBase = "camel-quarkus-cheese-wine";
        mojo.exampleDescription = "Shows how to use the Cheese Wine feature in Camel Quarkus";
        mojo.execute();

        TestUtils.assertTreesMatch(Paths.get("src/test/expected/" + testName), basePath);
    }
}
