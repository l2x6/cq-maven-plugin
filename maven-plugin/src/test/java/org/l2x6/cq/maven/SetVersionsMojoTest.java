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
import java.util.function.Predicate;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.junit.jupiter.api.Test;
import org.l2x6.cq.test.utils.TestUtils;
import org.l2x6.pom.tuner.MavenSourceTree.ActiveProfiles;
import org.l2x6.pom.tuner.PomTransformer.SimpleElementWhitespace;
import org.l2x6.pom.tuner.model.Profile;

public class SetVersionsMojoTest {

    private static SetVersionsMojo initMojo(final Path projectDir) throws IOException {
        final SetVersionsMojo mojo = new SetVersionsMojo() {

            @Override
            Predicate<Profile> getProfiles() {
                return ActiveProfiles.of();
            }

        };
        final Path basePath = projectDir.toAbsolutePath().normalize();
        mojo.basedir = basePath.toFile();
        mojo.encoding = "utf-8";
        mojo.simpleElementWhitespace = SimpleElementWhitespace.SPACE;
        mojo.newVersion = "2.4.0-foo";
        return mojo;
    }

    @Test
    void basic() throws MojoExecutionException, MojoFailureException,
            IllegalArgumentException, IllegalAccessException, NoSuchFieldException, SecurityException, IOException {
        final String testName = "set-versions-basic";
        final SetVersionsMojo mojo = initMojo(TestUtils.createProjectFromTemplate("set-versions", testName));
        mojo.execute();
        TestUtils.assertTreesMatch(Paths.get("src/test/expected/" + testName), mojo.basedir.toPath());
    }

}
