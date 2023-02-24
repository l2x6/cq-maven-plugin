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

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import org.apache.maven.model.Model;
import org.l2x6.pom.tuner.model.Gav;

/**
 * A set of {@link SyncExpression}s, possibly depending on each other and resolvable in a proper order.
 */
public class SyncExpressions {

    private final Map<String, SyncExpression> expressions;

    private SyncExpressions(Map<String, SyncExpression> expressions) {
        this.expressions = expressions;
    }

    /**
     * Evaluate these {@link SyncExpressions} in proper order and pass the resolved values to the given
     * {@code newValuesConsumer}
     *
     * @param mavenExpressionEvaluator a {@link Function} that for a given Maven expression such as <code>${foo.bar}</code>
     *                                 or
     *                                 <code>prefix-${foo}-infix-${foo}-suffix</code> returns its value in the context of a
     *                                 Maven POM file
     * @param pomModels                a repository of {@link Model}s, possibly downloading those from remote Maven
     *                                 repositories under the
     *                                 hood
     * @param newValuesConsumer        a consumer for the resolved values
     */
    public void evaluate(
            Function<String, String> mavenExpressionEvaluator,
            Function<Gav, Model> pomModels,
            BiConsumer<SyncExpression, String> newValuesConsumer) {
        final Map<String, SyncExpression> expressionsCopy = new LinkedHashMap<>(this.expressions);
        while (!expressionsCopy.isEmpty()) {
            int oldSize = expressionsCopy.size();
            for (Iterator<Entry<String, SyncExpression>> it = expressionsCopy.entrySet().iterator(); it.hasNext();) {
                final Entry<String, SyncExpression> entry = it.next();
                final SyncExpression expression = entry.getValue();
                final Set<String> requiredProps = expression.getRequiredProperties();
                if (requiredProps.isEmpty() || expressionsCopy.keySet().stream().noneMatch(requiredProps::contains)) {
                    final String newValue = expression.evaluate(mavenExpressionEvaluator, pomModels);
                    newValuesConsumer.accept(expression, newValue);
                    it.remove();
                }
            }
            if (oldSize == expressionsCopy.size()) {
                throw new IllegalStateException("Cannot resolve @sync properties " + expressionsCopy.entrySet()
                        + ". Is there perhaps a dependency cycle there?");
            }
        }
    }

    public static class Builder {
        private Map<String, SyncExpression> expressions = new LinkedHashMap<>();

        public Builder expression(SyncExpression expression) {
            this.expressions.put(expression.getPropertyNode().getLocalName(), expression);
            return this;
        }

        public SyncExpressions build() {
            final Map<String, SyncExpression> exprs = Collections.unmodifiableMap(expressions);
            expressions = null;
            return new SyncExpressions(exprs);
        }
    }

    /**
     * @return a new {@link Builder}
     */
    public static Builder builder() {
        return new Builder();
    }
}
