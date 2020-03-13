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
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

public class PomSorterTest {

    @Test
    void sortDependencyManagement() throws IOException {
        final Path baseDir = Paths.get("target/test-classes/projects/pom-sorter/dependency-management");
        PomSorter.sortDependencyManagement(baseDir, Arrays.asList("pom1.xml"));
        final Path expected = Paths.get("src/test/resources/expected/pom-sorter/dependency-management");
        CreateExtensionMojoTest.assertTreesMatch(expected, baseDir);
    }

    @Test
    void sortModules() throws IOException {
        final Path baseDir = Paths.get("target/test-classes/projects/pom-sorter/modules");
        PomSorter.sortModules(baseDir, Arrays.asList("pom1.xml"));
        final Path expected = Paths.get("src/test/resources/expected/pom-sorter/modules");
        CreateExtensionMojoTest.assertTreesMatch(expected, baseDir);
    }

    @Test
    void updateMvndRules() throws IOException {
        final Set<String> aids = new TreeSet<>(Arrays.asList("camel-quarkus-base64",
                "camel-quarkus-direct",
                "camel-quarkus-foo",
                "camel-quarkus-bar"));
        final Path baseDir = Paths.get("target/test-classes/projects/pom-sorter");
        PomSorter.updateMvndRules(baseDir, Arrays.asList("mvnd-rules"), aids);
        final Path expected = Paths.get("src/test/resources/expected/pom-sorter/mvnd-rules");
        CreateExtensionMojoTest.assertTreesMatch(expected, baseDir.resolve("mvnd-rules"));
    }
}
