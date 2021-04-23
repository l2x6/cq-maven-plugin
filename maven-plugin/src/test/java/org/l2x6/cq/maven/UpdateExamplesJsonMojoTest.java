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
package org.l2x6.cq.maven;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.l2x6.cq.maven.CqUtils;
import org.l2x6.cq.maven.UpdateExamplesJsonMojo;

public class UpdateExamplesJsonMojoTest {

    private static UpdateExamplesJsonMojo initMojo(final Path projectDir) throws IOException {
        final UpdateExamplesJsonMojo mojo = new UpdateExamplesJsonMojo();
        final Path basePath = projectDir.toAbsolutePath().normalize();
        mojo.examplesJsonFile = basePath.resolve(UpdateExamplesJsonMojo.DEFAULT_EXAMPLES_JSON).toFile();
        mojo.examplesDir = basePath.toFile();
        mojo.encoding = CqUtils.DEFAULT_ENCODING;
        return mojo;
    }


    @Test
    void updateExamplesJson() throws MojoExecutionException, MojoFailureException, IOException {
        final Path baseDir = TestUtils.createProjectFromTemplate("update-examples-json", "update-examples-json-test");
        final UpdateExamplesJsonMojo mojo = initMojo(baseDir);
        mojo.execute();

        final Path examplesJsonRelpath = Paths.get(UpdateExamplesJsonMojo.DEFAULT_EXAMPLES_JSON);

        final Path examplesJson = Paths.get("target/test-classes/expected/update-examples-json").resolve(examplesJsonRelpath).toAbsolutePath();

        Assertions.assertThat(baseDir.resolve(examplesJsonRelpath)).hasSameTextualContentAs(examplesJson);
    }

}
