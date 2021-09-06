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
import java.nio.file.Path;
import java.nio.file.Paths;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
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
        mojo.unlinkExcludes = true;
        mojo.simpleElementWhitespace = SimpleElementWhitespace.SPACE;
        return mojo;
    }

    @Test
    void prodeExcludes() throws MojoExecutionException, MojoFailureException,
            IllegalArgumentException, IllegalAccessException, NoSuchFieldException, SecurityException, IOException {
        final String testName = "basic";
        final ProdExcludesMojo mojo = initMojo(TestUtils.createProjectFromTemplate("prod-excludes", testName));
        mojo.execute();

        TestUtils.assertTreesMatch(Paths.get("src/test/resources/expected/" + testName), mojo.basedir.toPath());
    }

}
