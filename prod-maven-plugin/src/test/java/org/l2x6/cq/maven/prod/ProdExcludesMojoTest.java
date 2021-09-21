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

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.shared.utils.io.DirectoryScanner;
import org.junit.jupiter.api.Test;
import org.l2x6.cq.test.utils.TestUtils;
import org.l2x6.maven.utils.PomTransformer.SimpleElementWhitespace;

public class ProdExcludesMojoTest {

    private static ProdExcludesMojo initMojo(final Path projectDir) throws IOException {
        final ProdExcludesMojo mojo = new ProdExcludesMojo();
        final Path basePath = projectDir.toAbsolutePath().normalize();
        mojo.basedir = basePath.toFile();
        mojo.encoding = "utf-8";
        mojo.productJson = basePath.resolve("product/src/main/resources/camel-quarkus-product-source.json").toFile();
        mojo.simpleElementWhitespace = SimpleElementWhitespace.SPACE;
        final DirectoryScanner scanner = new DirectoryScanner();
        scanner.setBasedir(mojo.basedir);
        scanner.setIncludes("extensions-jvm/*/integration-test/pom.xml", "integration-tests/*/pom.xml",
                "integration-test-groups/*/*/pom.xml");
        mojo.integrationTests = Collections.singletonList(scanner);
        mojo.requiredProductizedCamelArtifacts = basePath
                .resolve(ProdExcludesMojo.DEFAULT_REQUIRED_PRODUCTIZED_CAMEL_ARTIFACTS_TXT).toFile();
        mojo.jenkinsfile = basePath.resolve("Jenkinsfile.redhat").toFile();
        return mojo;
    }

    @Test
    void initial() throws MojoExecutionException, MojoFailureException,
            IllegalArgumentException, IllegalAccessException, NoSuchFieldException, SecurityException, IOException {
        final String testName = "prod-excludes-initial";
        final ProdExcludesMojo mojo = initMojo(TestUtils.createProjectFromTemplate("prod-excludes", testName));
        mojo.execute();

        TestUtils.assertTreesMatch(Paths.get("src/test/expected/" + testName), mojo.basedir.toPath());
    }

    @Test
    void newSupportedExtension() throws MojoExecutionException, MojoFailureException,
            IllegalArgumentException, IllegalAccessException, NoSuchFieldException, SecurityException, IOException {
        final String testName = "prod-excludes-new-supported-extension";
        final ProdExcludesMojo mojo = initMojo(
                TestUtils.createProjectFromTemplate("../expected/prod-excludes-initial", testName));

        final Path dest = mojo.productJson.toPath();
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream("camel-quarkus-product-source-with-new-extension.json")) {
            Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
        }

        mojo.execute();

        TestUtils.assertTreesMatch(Paths.get("src/test/expected/" + testName), mojo.basedir.toPath());
    }

}
