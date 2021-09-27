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
package org.l2x6.cq.test.utils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.l2x6.cq.common.CqCommonUtils;
import org.l2x6.pom.tuner.PomTunerUtils;

import static org.junit.jupiter.api.Assertions.fail;

public class TestUtils {

    public static Path createProjectFromTemplate(String testProjectName, String copyPrefix) throws IOException {
        final Path srcDir = Paths.get("src/test/projects/" + testProjectName);
        /*
         * We want to run on the same project multiple times with different args so let's create a copy with a random
         * suffix
         */
        final Path copyDir = newProjectDir(copyPrefix);
        Files.walk(srcDir).forEach(source -> {
            final Path dest = copyDir.resolve(srcDir.relativize(source));
            try {
                Files.copy(source, dest);
            } catch (IOException e) {
                if (!Files.isDirectory(dest)) {
                    throw new RuntimeException(e);
                }
            }
        });
        return copyDir;
    }

    public static Path newProjectDir(String copyPrefix) throws IOException {
        final Path path = Paths.get("target/projects/" + copyPrefix);// + "-" +
                                                                     // UUID.randomUUID().toString().substring(0, 7));
        CqCommonUtils.ensureDirectoryExistsAndEmpty(path);
        return path;
    }

    public static void assertTreesMatch(Path expected, Path actual) throws IOException {
        final Set<Path> expectedFiles = new LinkedHashSet<>();
        try (Stream<Path> files = Files.walk(expected)) {
            files
                    .filter(Files::isRegularFile)
                    .sorted(relUnixPathComparator(expected))
                    .forEach(p -> {
                        final Path relative = expected.relativize(p);
                        expectedFiles.add(relative);
                        final Path actualPath = actual.resolve(relative);
                        org.assertj.core.api.Assertions.assertThat(actualPath).hasSameTextualContentAs(p,
                                StandardCharsets.UTF_8);
                    });
        }

        final Set<Path> unexpectedFiles = new LinkedHashSet<>();
        try (Stream<Path> files = Files.walk(actual)) {
            files
                    .filter(Files::isRegularFile)
                    .sorted(relUnixPathComparator(actual))
                    .forEach(p -> {
                        final Path relative = actual.relativize(p);
                        if (!expectedFiles.contains(relative)) {
                            unexpectedFiles.add(relative);
                        }
                    });
        }
        if (!unexpectedFiles.isEmpty()) {
            fail(String.format("Files found under [%s] but not defined as expected under [%s]:%s", actual,
                    expected, unexpectedFiles.stream().map(Path::toString).collect(Collectors.joining("\n    "))));
        }
    }

    static Comparator<Path> relUnixPathComparator(Path rootDir) {
        return (Path p1, Path p2) -> {
            final String p1Rel = PomTunerUtils.toUnixPath(rootDir.relativize(p1).toString());
            final String p2Rel = PomTunerUtils.toUnixPath(rootDir.relativize(p2).toString());
            return p1Rel.compareTo(p2Rel);
        };
    }

}
