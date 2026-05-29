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
package org.l2x6.cq.common.sync;

import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;
import org.apache.maven.model.Model;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.l2x6.cq.common.CqCommonUtils;
import org.l2x6.pom.tuner.model.Gav;
import org.w3c.dom.Element;

public class SyncExpressionsTest {

    @Test
    void collectRequiredProperties() {
        Assertions.assertThat(SyncExpression.collectRequiredProperties("${foo.version}")).containsExactly("foo.version");
        Assertions.assertThat(SyncExpression.collectRequiredProperties("prefix-${foo.version}-infix-${bar.version}-suffix"))
                .containsExactly("foo.version", "bar.version");
    }

    @Test
    void evaluationOrder1() {

        SyncExpressions expressions = SyncExpressions.builder()
                .expression(
                        new SyncExpression(
                                new eu.maveniverse.domtrip.Element("bom.version"),
                                "org.l2x6.cq.test",
                                "some-parent",
                                "${some-parent.version}",
                                "prop",
                                "bom.version"))
                .expression(
                        new SyncExpression(
                                new eu.maveniverse.domtrip.Element("some-lib.version"),
                                "org.l2x6.cq.test",
                                "bom",
                                "${bom.version}",
                                "dep",
                                "org.some.lib:some-lib"))
                .build();

        final MavenExpressionEvaluator mavenExpressionEvaluator = new MavenExpressionEvaluator("${some-parent.version}",
                "1.2.3");
        final Function<Gav, Model> pomModels = new PomModels(
                "src/test/resources/sync-versions/bom-pom.xml",
                "src/test/resources/sync-versions/some-parent-pom.xml");
        expressions.evaluate(mavenExpressionEvaluator, pomModels, mavenExpressionEvaluator);

        Assertions.assertThat(mavenExpressionEvaluator.values)
                .containsExactlyInAnyOrderEntriesOf(Map.of("${some-parent.version}",
                        "1.2.3", "${bom.version}", "2.3.4", "${some-lib.version}", "3.4.5"));
    }

    @Test
    void evaluationOrder2() {
        SyncExpressions expressions = SyncExpressions.builder()
                .expression(
                        new SyncExpression(
                                new eu.maveniverse.domtrip.Element("some-lib.version"),
                                "org.l2x6.cq.test",
                                "bom",
                                "${bom.version}",
                                "dep",
                                "org.some.lib:some-lib"))
                .expression(
                        new SyncExpression(
                                new eu.maveniverse.domtrip.Element("bom.version"),
                                "org.l2x6.cq.test",
                                "some-parent",
                                "${some-parent.version}",
                                "prop",
                                "bom.version"))
                .build();

        final MavenExpressionEvaluator mavenExpressionEvaluator = new MavenExpressionEvaluator("${some-parent.version}",
                "1.2.3");
        final Function<Gav, Model> pomModels = new PomModels(
                "src/test/resources/sync-versions/bom-pom.xml",
                "src/test/resources/sync-versions/some-parent-pom.xml");
        expressions.evaluate(mavenExpressionEvaluator, pomModels, mavenExpressionEvaluator);

        Assertions.assertThat(mavenExpressionEvaluator.values)
                .containsExactlyInAnyOrderEntriesOf(Map.of("${some-parent.version}",
                        "1.2.3", "${bom.version}", "2.3.4", "${some-lib.version}", "3.4.5"));

    }

    @Test
    void circularDependency1() {
        SyncExpressions expressions = SyncExpressions.builder()
                .expression(
                        new SyncExpression(
                                new eu.maveniverse.domtrip.Element("some-parent.version"),
                                "org.l2x6.cq.test",
                                "some-parent",
                                "${some-parent.version}",
                                "prop",
                                "bom.version"))
                .build();

        final MavenExpressionEvaluator mavenExpressionEvaluator = new MavenExpressionEvaluator();
        final Function<Gav, Model> pomModels = new PomModels(
                "src/test/resources/sync-versions/bom-pom.xml",
                "src/test/resources/sync-versions/some-parent-pom.xml");
        Assertions.assertThatThrownBy(() -> expressions.evaluate(mavenExpressionEvaluator, pomModels, mavenExpressionEvaluator))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dependency cycle");
    }

    @Test
    void circularDependency2() {
        SyncExpressions expressions = SyncExpressions.builder()
                .expression(
                        new SyncExpression(
                                new eu.maveniverse.domtrip.Element("some-parent.version"),
                                "org.l2x6.cq.test",
                                "some-parent",
                                "${some-parent.version}",
                                "prop",
                                "bom.version"))
                .expression(
                        new SyncExpression(
                                new eu.maveniverse.domtrip.Element("some-parent.version"),
                                "org.l2x6.cq.test",
                                "some-parent",
                                "${some-parent.version}",
                                "prop",
                                "bom.version"))
                .build();

        final MavenExpressionEvaluator mavenExpressionEvaluator = new MavenExpressionEvaluator();
        final Function<Gav, Model> pomModels = new PomModels(
                "src/test/resources/sync-versions/bom-pom.xml",
                "src/test/resources/sync-versions/some-parent-pom.xml");
        Assertions.assertThatThrownBy(() -> expressions.evaluate(mavenExpressionEvaluator, pomModels, mavenExpressionEvaluator))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dependency cycle");
    }

    static class PomModels implements Function<Gav, Model> {

        private final Map<Gav, Model> models = new LinkedHashMap<>();

        public PomModels(String... resourcePaths) {
            for (String resourcePath : resourcePaths) {
                final Model model = CqCommonUtils.readPom(Paths.get(resourcePath), StandardCharsets.UTF_8);
                models.put(new Gav("org.l2x6.cq.test", model.getArtifactId(), model.getVersion()), model);
            }
        }

        @Override
        public Model apply(Gav gav) {
            return models.get(gav);
        }

    }

    static class MavenExpressionEvaluator implements Function<String, String>, BiConsumer<SyncExpression, String> {

        private final Map<String, String> values = new LinkedHashMap<>();

        public MavenExpressionEvaluator(String... initialValues) {
            for (int i = 0; i < initialValues.length;) {
                values.put(initialValues[i++], initialValues[i++]);
            }
        }

        @Override
        public String apply(String expr) {
            String result = values.get(expr);
            if (result == null) {
                throw new IllegalStateException("No value for expression " + expr);
            }
            return result;
        }

        @Override
        public void accept(SyncExpression expr, String value) {
            values.put("${" + expr.getPropertyNode().localName() + "}", value);
        }

    }

}
