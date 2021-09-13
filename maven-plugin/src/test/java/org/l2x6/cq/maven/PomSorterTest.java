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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.l2x6.cq.common.CqCommonUtils;
import org.l2x6.cq.test.utils.TestUtils;
import org.l2x6.maven.utils.Gavtcs;
import org.l2x6.maven.utils.PomTransformer;
import org.l2x6.maven.utils.PomTransformer.SimpleElementWhitespace;
import org.l2x6.maven.utils.PomTransformer.Transformation;

public class PomSorterTest {

    @Test
    void sortDependencyManagement() throws IOException {
        final Path root = TestUtils.createProjectFromTemplate("pom-sorter", "pom-sorter-dependency-management");
        final Path baseDir = root.resolve("dependency-management");
        PomSorter.sortDependencyManagement(baseDir, Arrays.asList("pom1.xml"));
        final Path expected = Paths.get("src/test/expected/pom-sorter/dependency-management");
        TestUtils.assertTreesMatch(expected, baseDir);
    }

    @Test
    void sortModules() throws IOException {
        final Path root = TestUtils.createProjectFromTemplate("pom-sorter", "pom-sorter-modules");
        final Path baseDir = root.resolve("modules");
        PomSorter.sortModules(baseDir, Arrays.asList("pom1.xml"));
        final Path expected = Paths.get("src/test/expected/pom-sorter/modules");
        TestUtils.assertTreesMatch(expected, baseDir);
    }

    @Test
    void updateVirtualDependencies() throws IOException {
        final Set<String> aids = new TreeSet<>(Arrays.asList("camel-quarkus-base64",
                "camel-quarkus-direct",
                "camel-quarkus-foo",
                "camel-quarkus-bar"));
        final Path baseDir = TestUtils.createProjectFromTemplate("pom-sorter", "pom-sorter-updateVirtualDependencies");
        try (Stream<Path> files = Files.list(baseDir.resolve("mvnd-rules"))) {
            files
                    .filter(p -> Files.isDirectory(p) && !"support".equals(p.getFileName().toString()))
                    .sorted()
                    .map(p -> p.resolve("pom.xml"))
                    .filter(p -> Files.exists(p))
                    .forEach(pomXmlPath -> {
                        new PomTransformer(pomXmlPath, StandardCharsets.UTF_8, SimpleElementWhitespace.EMPTY)
                                .transform(Transformation.updateMappedDependencies(
                                        Gavtcs::isVirtualDeployment,
                                        Gavtcs.deploymentVitualMapper(gavtcs -> aids.contains(gavtcs.getArtifactId())),
                                        Gavtcs.scopeAndTypeFirstComparator(),
                                        CqCommonUtils.VIRTUAL_DEPS_INITIAL_COMMENT));
                    });
        }

        final Path expected = Paths.get("src/test/expected/pom-sorter/mvnd-rules");
        TestUtils.assertTreesMatch(expected, baseDir.resolve("mvnd-rules"));
    }
}
