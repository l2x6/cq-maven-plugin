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
package org.l2x6.cq.common;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.l2x6.pom.tuner.model.GavPattern;

public class BannedDependencyResourceTest {

    @Test
    public void classpath() {
        BannedDependencyResource resource = new BannedDependencyResource(
                "classpath:enforcer/test-banned-dependencies.xml",
                null);

        final Set<GavPattern> bannedPatterns = resource.getBannedPatterns(StandardCharsets.UTF_8);
        Assertions.assertEquals(78, bannedPatterns.size());
        Assertions.assertTrue(bannedPatterns.contains(GavPattern.of("org.javassist:javassist")));
    }

    @Test
    public void filesystem() {
        BannedDependencyResource resource = new BannedDependencyResource(
                "src/test/resources/enforcer/test-banned-dependencies.xml",
                null);

        final Set<GavPattern> bannedPatterns = resource.getBannedPatterns(StandardCharsets.UTF_8);
        Assertions.assertEquals(78, bannedPatterns.size());
        Assertions.assertTrue(bannedPatterns.contains(GavPattern.of("org.javassist:javassist")));
    }

    @Test
    public void ignore() {
        BannedDependencyResource resource = new BannedDependencyResource(
                "src/test/resources/enforcer/test-banned-dependencies.xml",
                "classpath:enforcer/test-filter.xsl");

        final Set<GavPattern> bannedPatterns = resource.getBannedPatterns(StandardCharsets.UTF_8);
        Assertions.assertEquals(77, bannedPatterns.size());
        Assertions.assertFalse(bannedPatterns.contains(GavPattern.of("org.javassist:javassist")));
    }

}
