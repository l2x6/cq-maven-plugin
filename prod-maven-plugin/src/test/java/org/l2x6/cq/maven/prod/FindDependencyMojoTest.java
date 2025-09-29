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
package org.l2x6.cq.maven.prod;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.l2x6.cq.maven.prod.FindDependencyMojo.Result;
import org.l2x6.cq.maven.prod.FindDependencyMojo.Results;
import org.l2x6.pom.tuner.model.Gav;
import org.l2x6.pom.tuner.model.Gavtcs;

public class FindDependencyMojoTest {
    static final Set<Gav> roots = Set.of(Gav.of("org.foo:foo1:1.2.3"), Gav.of("org.foo:foo2:1.2.3"));
    static final Gavtcs foo1 = Gavtcs.of("org.foo:foo1:1.2.3");
    static final Gavtcs foo2 = Gavtcs.of("org.foo:foo2:1.2.3");
    static final Gavtcs bar1 = Gavtcs.of("org.bar:bar1:2.3.4");
    static final Gavtcs bar2 = Gavtcs.of("org.bar:bar2:3.4.5");

    @Test
    void result() {
        Assertions.assertThat(result(roots).getPath()).isEqualTo(List.of());
        Assertions.assertThat(result(roots, foo1, bar1).getPath()).isEqualTo(List.of(foo1, bar1));
        Assertions.assertThat(result(roots, foo1, foo2, bar1).getPath()).isEqualTo(List.of(foo2, bar1));
        Assertions.assertThat(result(roots, foo1, foo2).getPath()).isEqualTo(List.of(foo2));
        Assertions.assertThat(result(Set.of(), foo1, foo2).getPath()).isEqualTo(List.of(foo1, foo2));
    }

    static Result result(Set<Gav> roots, Gavtcs... stackElements) {
        return Result.of(roots, stack(stackElements), null);
    }

    static Deque<Gavtcs> stack(Gavtcs... stackElements) {
        final Deque<Gavtcs> stack = new ArrayDeque<>();
        Stream.of(stackElements).forEach(stack::push);
        return stack;
    }

    @Test
    void results() {

        final Results results = new Results(roots);

        results.add(stack(foo1, bar1), null);
        {
            Assertions.assertThat(results.results).hasSize(1);
            final Iterator<Result> it = results.results.iterator();
            Assertions.assertThat(it.next().getPath()).isEqualTo(List.of(foo1, bar1));
        }

        results.add(stack(foo1, bar1, foo2), null);
        {
            Assertions.assertThat(results.results).hasSize(2);
            final Iterator<Result> it = results.results.iterator();
            Assertions.assertThat(it.next().getPath()).isEqualTo(List.of(foo1, bar1));
            Assertions.assertThat(it.next().getPath()).isEqualTo(List.of(foo1, bar1, foo2));
        }

        results.add(stack(foo2, foo1, bar1), null); // equivalent to stack(foo1, bar1) that we added already, so no change
        {
            Assertions.assertThat(results.results).hasSize(2);
            final Iterator<Result> it = results.results.iterator();
            Assertions.assertThat(it.next().getPath()).isEqualTo(List.of(foo1, bar1));
            Assertions.assertThat(it.next().getPath()).isEqualTo(List.of(foo1, bar1, foo2));
        }

        results.add(stack(), null); // equivalent to stack(foo1, bar1) that we added already, so no change
        {
            Assertions.assertThat(results.results).hasSize(3);
            final Iterator<Result> it = results.results.iterator();
            Assertions.assertThat(it.next().getPath()).isEqualTo(List.of());
            Assertions.assertThat(it.next().getPath()).isEqualTo(List.of(foo1, bar1));
            Assertions.assertThat(it.next().getPath()).isEqualTo(List.of(foo1, bar1, foo2));
        }

        results.add(stack(foo2, foo1, bar2), null); // equivalent to stack(foo1, bar1) that we added already, so no change
        {
            Assertions.assertThat(results.results).hasSize(4);
            final Iterator<Result> it = results.results.iterator();
            Assertions.assertThat(it.next().getPath()).isEqualTo(List.of());
            Assertions.assertThat(it.next().getPath()).isEqualTo(List.of(foo1, bar1));
            Assertions.assertThat(it.next().getPath()).isEqualTo(List.of(foo1, bar1, foo2));
            Assertions.assertThat(it.next().getPath()).isEqualTo(List.of(foo1, bar2));
        }

    }
}
