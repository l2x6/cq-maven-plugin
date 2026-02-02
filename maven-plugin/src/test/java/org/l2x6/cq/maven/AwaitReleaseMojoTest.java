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

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

public class AwaitReleaseMojoTest {
    @Test
    void artifactIdFilter() {
        final Path docs = Path.of("foo/foo-docs");
        final Path tests = Path.of("foo/foo-tests");
        final Path core = Path.of("foo/foo-core");

        Assertions.assertThat(Stream.of(docs, tests, core)
                .filter(AwaitReleaseMojo.artifactIdFilter(List.of("*-docs", "*-tests")))
                .toList()).containsExactly(core);

        Assertions.assertThat(Stream.of(docs, tests, core)
                .filter(AwaitReleaseMojo.artifactIdFilter(null))
                .toList()).containsExactly(docs, tests, core);

    }
}
