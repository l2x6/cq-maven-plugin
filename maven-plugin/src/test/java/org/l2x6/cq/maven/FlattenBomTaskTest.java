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

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.l2x6.cq.common.FlattenBomTask.BomEntryTransformation;
import org.l2x6.pom.tuner.model.Ga;
import org.l2x6.pom.tuner.model.GavPattern;

public class FlattenBomTaskTest {
    @Test
    void bomEntryTransformationSet() {
        {
            BomEntryTransformation bt = of(
                    "org.apache.kafka:connect-runtime@(1.2).3/$1.4");
            Assertions.assertThat((Object) bt.getGavPattern()).isEqualTo(GavPattern.of("org.apache.kafka:connect-runtime"));
            Assertions.assertThat(bt.replaceVersion("1.2.3")).isEqualTo("1.2.4");
        }
        {
            BomEntryTransformation bt = of(
                    "org.apache.kafka:connect-runtime@[\\.\\-](?:temporary-)?(?:rhbac-)?redhat-\\d+$/");
            Assertions.assertThat(bt.replaceVersion("1.2.3")).isEqualTo("1.2.3");
            Assertions.assertThat(bt.replaceVersion("1.2.3-redhat-00001")).isEqualTo("1.2.3");
            Assertions.assertThat(bt.replaceVersion("1.2.3.redhat-00001")).isEqualTo("1.2.3");
            Assertions.assertThat(bt.replaceVersion("1.2.3-temporary-redhat-00001")).isEqualTo("1.2.3");
            Assertions.assertThat(bt.replaceVersion("1.2.3.temporary-redhat-00001")).isEqualTo("1.2.3");
            Assertions.assertThat(bt.replaceVersion("1.2.3-rhbac-redhat-00001")).isEqualTo("1.2.3");
            Assertions.assertThat(bt.replaceVersion("1.2.3.rhbac-redhat-00001")).isEqualTo("1.2.3");
            Assertions.assertThat(bt.replaceVersion("1.2.3-temporary-rhbac-redhat-00001")).isEqualTo("1.2.3");
            Assertions.assertThat(bt.replaceVersion("1.2.3.temporary-rhbac-redhat-00001")).isEqualTo("1.2.3");
        }
        {
            BomEntryTransformation bt = of(
                    "org.apache.kafka:connect-runtime|javax.activation:activation|javax.servlet:javax.servlet-api|log4j:log4j");
            Assertions.assertThat((Object) bt.getGavPattern()).isEqualTo(GavPattern.of("org.apache.kafka:connect-runtime"));
            Assertions.assertThat(bt.getInternalExclusions()).containsExactly(Ga.of("javax.activation:activation"),
                    Ga.of("javax.servlet:javax.servlet-api"), Ga.of("log4j:log4j"));
        }
    }

    static BomEntryTransformation of(String s) {
        BomEntryTransformation result = new BomEntryTransformation();
        result.set(s);
        return result;
    }
}
