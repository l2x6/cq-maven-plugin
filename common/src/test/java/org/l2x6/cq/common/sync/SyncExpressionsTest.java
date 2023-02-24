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
import org.w3c.dom.Attr;
import org.w3c.dom.DOMException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.w3c.dom.TypeInfo;
import org.w3c.dom.UserDataHandler;

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
                                new ElementImpl("bom.version"),
                                "org.l2x6.cq.test",
                                "some-parent",
                                "${some-parent.version}",
                                "prop",
                                "bom.version"))
                .expression(
                        new SyncExpression(
                                new ElementImpl("some-lib.version"),
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
                                new ElementImpl("some-lib.version"),
                                "org.l2x6.cq.test",
                                "bom",
                                "${bom.version}",
                                "dep",
                                "org.some.lib:some-lib"))
                .expression(
                        new SyncExpression(
                                new ElementImpl("bom.version"),
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
                                new ElementImpl("some-parent.version"),
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
                                new ElementImpl("some-parent.version"),
                                "org.l2x6.cq.test",
                                "some-parent",
                                "${some-parent.version}",
                                "prop",
                                "bom.version"))
                .expression(
                        new SyncExpression(
                                new ElementImpl("some-parent.version"),
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
            values.put("${" + expr.getPropertyNode().getLocalName() + "}", value);
        }

    }

    static class ElementImpl implements Element {

        private final String localName;

        private ElementImpl(String localName) {
            this.localName = localName;
        }

        @Override
        public String getNodeName() {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getNodeValue() throws DOMException {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setNodeValue(String nodeValue) throws DOMException {
            throw new UnsupportedOperationException();
        }

        @Override
        public short getNodeType() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Node getParentNode() {
            throw new UnsupportedOperationException();
        }

        @Override
        public NodeList getChildNodes() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Node getFirstChild() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Node getLastChild() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Node getPreviousSibling() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Node getNextSibling() {
            throw new UnsupportedOperationException();
        }

        @Override
        public NamedNodeMap getAttributes() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Document getOwnerDocument() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Node insertBefore(Node newChild, Node refChild) throws DOMException {
            throw new UnsupportedOperationException();
        }

        @Override
        public Node replaceChild(Node newChild, Node oldChild) throws DOMException {
            throw new UnsupportedOperationException();
        }

        @Override
        public Node removeChild(Node oldChild) throws DOMException {
            throw new UnsupportedOperationException();
        }

        @Override
        public Node appendChild(Node newChild) throws DOMException {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean hasChildNodes() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Node cloneNode(boolean deep) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void normalize() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isSupported(String feature, String version) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getNamespaceURI() {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getPrefix() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setPrefix(String prefix) throws DOMException {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getLocalName() {
            return localName;
        }

        @Override
        public boolean hasAttributes() {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getBaseURI() {
            throw new UnsupportedOperationException();
        }

        @Override
        public short compareDocumentPosition(Node other) throws DOMException {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getTextContent() throws DOMException {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setTextContent(String textContent) throws DOMException {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isSameNode(Node other) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String lookupPrefix(String namespaceURI) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isDefaultNamespace(String namespaceURI) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String lookupNamespaceURI(String prefix) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isEqualNode(Node arg) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Object getFeature(String feature, String version) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Object setUserData(String key, Object data, UserDataHandler handler) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Object getUserData(String key) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getTagName() {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getAttribute(String name) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setAttribute(String name, String value) throws DOMException {
            throw new UnsupportedOperationException();
        }

        @Override
        public void removeAttribute(String name) throws DOMException {
            throw new UnsupportedOperationException();
        }

        @Override
        public Attr getAttributeNode(String name) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Attr setAttributeNode(Attr newAttr) throws DOMException {
            throw new UnsupportedOperationException();
        }

        @Override
        public Attr removeAttributeNode(Attr oldAttr) throws DOMException {
            throw new UnsupportedOperationException();
        }

        @Override
        public NodeList getElementsByTagName(String name) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getAttributeNS(String namespaceURI, String localName) throws DOMException {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setAttributeNS(String namespaceURI, String qualifiedName, String value) throws DOMException {
            throw new UnsupportedOperationException();
        }

        @Override
        public void removeAttributeNS(String namespaceURI, String localName) throws DOMException {
            throw new UnsupportedOperationException();
        }

        @Override
        public Attr getAttributeNodeNS(String namespaceURI, String localName) throws DOMException {
            throw new UnsupportedOperationException();
        }

        @Override
        public Attr setAttributeNodeNS(Attr newAttr) throws DOMException {
            throw new UnsupportedOperationException();
        }

        @Override
        public NodeList getElementsByTagNameNS(String namespaceURI, String localName) throws DOMException {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean hasAttribute(String name) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean hasAttributeNS(String namespaceURI, String localName) throws DOMException {
            throw new UnsupportedOperationException();
        }

        @Override
        public TypeInfo getSchemaTypeInfo() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setIdAttribute(String name, boolean isId) throws DOMException {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setIdAttributeNS(String namespaceURI, String localName, boolean isId) throws DOMException {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setIdAttributeNode(Attr idAttr, boolean isId) throws DOMException {
            throw new UnsupportedOperationException();
        }

    }
}
