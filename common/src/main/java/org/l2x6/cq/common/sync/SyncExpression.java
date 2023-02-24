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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.l2x6.pom.tuner.model.Gav;
import org.w3c.dom.Element;

/**
 * A <code>@sync</code> expression, such as
 * <code>@sync io.quarkus:quarkus-bom:${quarkus.version} dep:org.apache.avro:avro</code> or
 * <code>@sync io.quarkus:quarkus-build-parent:${quarkus.version} prop:assertj.version</code>
 * <p>
 * Allows to bind a Maven property to some property or dependency version in some external POM file.
 */
public class SyncExpression {
    static final Pattern PROPERTY_PATTERN = Pattern
            .compile(
                    "\\$\\{(?<property>[^\\}]*)\\}");
    static final Pattern SYNC_INSTRUCTION_PATTERN = Pattern
            .compile(
                    "\\s*@sync (?<groupId>[^:]*):(?<artifactId>[^:]*):(?<version>[^:]*) (?<method>[^:]+):(?<element>[^ ]+)\\s*");

    /**
     * Attempts to parse the given {@code commenText} as {@link SyncExpression}. If the comment text contains a valid
     * <code>@sync</code> expression, returns an {@link Optional} containing a new {@link SyncExpression}; otherwise returns
     * an empty {@link Optional}.
     *
     * @param  propertyNode the property {@link Element} associated with the given {@code commentText}
     * @param  commentText  the <code>@sync</code> expression to parse
     * @return              an {@link Optional} containing a new {@link SyncExpression} if the comment text contains a valid
     *                      <code>@sync</code> expression; otherwise returns an empty {@link Optional}
     */
    public static Optional<SyncExpression> parse(Element propertyNode, String commentText) {
        final Matcher m = SYNC_INSTRUCTION_PATTERN.matcher(commentText);
        if (m.matches()) {
            final String groupId = m.group("groupId");
            final String artifactId = m.group("artifactId");
            final String rawVersion = m.group("version");
            final String element = m.group("element");
            final String method = m.group("method");

            return Optional.of(new SyncExpression(propertyNode, groupId, artifactId, rawVersion, method, element));

        }
        return Optional.empty();
    }

    private final String groupId;
    private final String artifactId;
    private final String rawVersion;
    private final String element;
    private final String method;
    private final Set<String> requiredProperties;
    private final Element propertyNode;

    SyncExpression(Element propertyNode, String groupId, String artifactId, String rawVersion, String method,
            String element) {
        this.propertyNode = propertyNode;
        this.groupId = groupId;
        this.artifactId = artifactId;
        this.rawVersion = rawVersion;
        this.element = element;
        this.method = method;
        final Set<String> requiredProperties = collectRequiredProperties(rawVersion);
        this.requiredProperties = Collections.unmodifiableSet(requiredProperties);
    }

    static Set<String> collectRequiredProperties(String rawVersion) {
        final Set<String> requiredProperties = new LinkedHashSet<>();
        final Matcher m = PROPERTY_PATTERN.matcher(rawVersion);
        while (m.find()) {
            requiredProperties.add(m.group("property"));
        }
        return requiredProperties;
    }

    /**
     * @return a {@link Set} of property names that have to be resolved before this {@link SyncExpression} can be evaluated
     */
    public Set<String> getRequiredProperties() {
        return requiredProperties;
    }

    /**
     * Evaluates this {@link SyncExpression}. The parameters are kept quite generic to be able to test easier.
     *
     * @param  evaluator a {@link Function} that for a given Maven expression such as <code>${foo.bar}</code> or
     *                   <code>prefix-${foo}-infix-${foo}-suffix</code> returns its value in the context of a Maven POM file
     * @param  pomModels a repository of {@link Model}s, possibly downloading those from remote Maven repositories under the
     *                   hood
     * @return           the value of the current {@link SyncExpression}
     */
    public String evaluate(Function<String, String> evaluator, Function<Gav, Model> pomModels) {
        final String resolvedVersion = (String) evaluator.apply(rawVersion);
        final Model sourceModel = pomModels.apply(new Gav(groupId, artifactId, resolvedVersion));

        final String newValue;

        switch (method) {
        case "prop":
            final Properties sourceProps = sourceModel.getProperties();
            final String sourceProperty = element;
            newValue = sourceProps.getProperty(sourceProperty);
            break;
        case "dep":
            newValue = dependencyVersion(sourceModel, element, groupId, artifactId, resolvedVersion);
            break;
        default:
            throw new IllegalStateException(
                    "Unexpected method " + method + "; expected prop or dep");
        }
        return newValue;
    }

    /**
     * Evaluates a {@code dep} expression
     *
     * @param  model
     * @param  element
     * @param  groupId
     * @param  artifactId
     * @param  resolvedVersion
     * @return                 the value of the expression
     */
    static String dependencyVersion(Model model, String element, String groupId, String artifactId,
            String resolvedVersion) {
        final String[] ga = element.split(":");
        final List<List<Dependency>> depStreams = new ArrayList<>();
        if (model.getDependencyManagement() != null && model.getDependencyManagement().getDependencies() != null) {
            depStreams.add(model.getDependencyManagement().getDependencies());
        }
        if (model.getDependencies() != null) {
            depStreams.add(model.getDependencies());
        }
        return depStreams.stream()
                .flatMap(List::stream)
                .filter(d -> ga[0].equals(d.getGroupId()) && ga[1].equals(d.getArtifactId())
                        && d.getVersion() != null)
                .map(Dependency::getVersion)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No such dependency " + element
                        + " in " + groupId + ":" + artifactId + ":" + resolvedVersion + ":pom"));
    }

    /**
     * @return the Maven property {@link Element} associated with this {@link SyncExpression}
     */
    public Element getPropertyNode() {
        return propertyNode;
    }

}
