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

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.shared.model.fileset.FileSet;
import org.junit.jupiter.api.Test;

public class FormatMojoTest {

    private static FormatPomsMojo initMojo(final Path projectDir) throws IOException {
        final FormatPomsMojo mojo = new FormatPomsMojo();
        final Path basePath = projectDir.toAbsolutePath().normalize();
        mojo.basedir = basePath.toFile();
        mojo.sortDependencyManagementPaths = Collections.emptyList();
        mojo.sortModulesPaths = Collections.emptyList();
        mojo.updateVirtualDependenciesDirs = Collections.emptyList();
        mojo.updateVirtualDependenciesAllExtensionsDirs = Collections.emptyList();
        mojo.extensionDirs = Collections.emptyList();
        mojo.skipArtifactIds = Collections.emptySet();
        mojo.encoding = CqUtils.DEFAULT_ENCODING;
        mojo.extensionDirs = Collections.singletonList(new ExtensionDir("extensions", "camel-quarkus-"));

        return mojo;
    }


    @Test
    void emptyApplicationProperties() throws MojoExecutionException, MojoFailureException, IOException {
        final String testName = "remove-empty-application-properties";
        final FormatPomsMojo mojo = initMojo(TestUtils.createProjectFromTemplate("create-extension-pom", testName));
        final FileSet fileSet = new FileSet();
        fileSet.setDirectory(mojo.basedir.toString() + "/integration-tests");
        fileSet.addInclude("*/src/main/resources/application.properties");
        mojo.removeEmptyApplicationProperties = fileSet;
        mojo.execute();

        TestUtils.assertTreesMatch(Paths.get("src/test/resources/expected/"+ testName),
                mojo.basedir.toPath());

    }

}
