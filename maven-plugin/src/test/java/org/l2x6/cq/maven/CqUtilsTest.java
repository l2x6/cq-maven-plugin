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
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.camel.tooling.model.ComponentModel;
import org.apache.camel.tooling.model.SupportLevel;
import org.junit.jupiter.api.Test;
import org.l2x6.cq.common.ExtensionStatus;
import org.l2x6.pom.tuner.model.Ga;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class CqUtilsTest {

    @Test
    void toCapCamelCase() throws IOException {
        assertEquals("FooBarBaz", CqUtils.toCapCamelCase("foo+-bar.baz"));
    }

    @Test
    void toSnakeCase() throws IOException {
        assertEquals("foo_bar_baz", CqUtils.toSnakeCase("Foo+-bar.baz"));
    }

    @Test
    void toKebabCase() throws IOException {
        assertEquals("foo-bar-baz", CqUtils.toKebabCase("foo+-BAR.baZ"));
    }

    @Test
    void findExtensionArtifactIds() {
        Set<Ga> set = Stream.of("foo", "foo-deployment", "bar-deployment")
                .map(a -> new Ga("org.foo", a))
                .collect(Collectors.toSet());
        assertEquals(
                Set.of("foo"),
                CqUtils.findExtensionArtifactIds(set)
                        .collect(Collectors.toSet()));
    }

    @Test
    void supportLevelToStatusEmpty() {
        assertEquals(ExtensionStatus.stable, CqUtils.supportLevelToStatus(Collections.emptyList()));
    }

    @Test
    void supportLevelToStatusNoSupportLevel() {
        assertEquals(ExtensionStatus.stable, CqUtils.supportLevelToStatus(List.of(new ComponentModel())));
    }

    @Test
    void supportLevelToStatusStable() {
        assertEquals(ExtensionStatus.stable,
                CqUtils.supportLevelToStatus(List.of(model(SupportLevel.Stable))));
    }

    @Test
    void supportLevelToStatusPreview() {
        assertEquals(ExtensionStatus.preview,
                CqUtils.supportLevelToStatus(List.of(model(SupportLevel.Preview))));
    }

    @Test
    void supportLevelToStatusExperimental() {
        assertEquals(ExtensionStatus.experimental,
                CqUtils.supportLevelToStatus(List.of(model(SupportLevel.Experimental))));
    }

    @Test
    void supportLevelToStatusBaseStability() {
        assertEquals(ExtensionStatus.stable,
                CqUtils.supportLevelToStatus(List.of(model(SupportLevel.baseStability))));
    }

    @Test
    void supportLevelToStatusLeastStableModel() {
        assertEquals(ExtensionStatus.experimental,
                CqUtils.supportLevelToStatus(List.of(model(SupportLevel.Stable), model(SupportLevel.Experimental),
                        model(SupportLevel.Preview))));
    }

    static ComponentModel model(SupportLevel supportLevel) {
        ComponentModel model = new ComponentModel();
        model.setSupportLevel(supportLevel);
        return model;
    }

}
