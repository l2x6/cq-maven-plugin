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
import org.l2x6.pom.tuner.PomTransformer.SimpleElementWhitespace;

public class PromoteMojoTest {

    private static PromoteExtensionMojo initMojo(final Path projectDir) throws IOException {
        final PromoteExtensionMojo mojo = new PromoteExtensionMojo();
        final Path basePath = projectDir.toAbsolutePath().normalize();
        mojo.multiModuleProjectDirectory = basePath.toFile();
        mojo.encoding = CqUtils.DEFAULT_ENCODING;
        mojo.camelQuarkusVersion = "0.1-SNAPSHOT";
        mojo.simpleElementWhitespace = SimpleElementWhitespace.EMPTY;
        mojo.templatesUriBase = CqUtils.DEFAULT_TEMPLATES_URI_BASE;
        return mojo;
    }

    @Test
    void promoteExtension() throws IOException, MojoExecutionException, MojoFailureException {
        final String testName = "promote-extension-foo";
        final Path basePath = TestUtils.createProjectFromTemplate("promote-extension", testName);
        final PromoteExtensionMojo mojo = initMojo(basePath);
        mojo.extensionsDir = "extensions";
        mojo.artifactIdBase = "foo";

        // Promote the extension
        mojo.execute();

        TestUtils.assertTreesMatch(Paths.get("src/test/expected/" + testName), basePath);
    }
}
