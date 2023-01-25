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

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Consumer;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.TransformerFactoryConfigurationError;
import javax.xml.transform.dom.DOMResult;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamSource;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import org.l2x6.pom.tuner.model.GavPattern;
import org.l2x6.pom.tuner.model.GavSet;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Represents an XML file containing Maven enforcer plugin bannedDependencies excludes. Optionally an XSLT filter
 * can be applied to the XML file before the banned GAV patterns are extracted.
 */
public class BannedDependencyResource {

    private String location;
    private String xsltLocation;
    private Set<GavPattern> bannedPatterns;

    public BannedDependencyResource() {
    }

    public BannedDependencyResource(String path, String xsltLocation) {
        this.location = path;
        this.xsltLocation = xsltLocation;
    }

    /**
     * Returns a path to a resource containing banned dependencies enforcer rules.
     * <p>
     * If the path is prefixed with {@code classpath:} it will be interpreted as a classpath resource path.
     * Otherwise, it will be interpreted as a filesystem path - preferably an absolute path.
     *
     * @return a path to a resource
     */
    public String getLocation() {
        return location;
    }

    public void setLocation(String path) {
        this.location = path;
    }

    public String getXsltLocation() {
        return xsltLocation;
    }

    public void setXsltLocation(String xsltLocation) {
        this.xsltLocation = xsltLocation;
    }

    /**
     * @return                       a {@link GavSet} whose {@link GavSet#contains(String, String)} method will return
     *                               {@code true} for the artifacts which
     *                               <strong>are</strong> banned.
     *
     * @throws IllegalStateException if {@link #getLocation()} returns null or a blank string
     */
    public Set<GavPattern> getBannedPatterns(Charset charset) {
        if (bannedPatterns == null) {

            if (location == null || location.isBlank()) {
                throw new IllegalStateException("path must be specified for " + this);
            }

            Document document;
            try {
                final Transformer transformer = TransformerFactory.newInstance().newTransformer();
                final DOMResult result = new DOMResult();
                try (Reader r = openReader(location, charset)) {
                    transformer.transform(new StreamSource(r), result);
                    document = (Document) result.getNode();

                    if (xsltLocation != null && !xsltLocation.isBlank()) {
                        document = transform(location, document, xsltLocation, charset);
                    }
                } catch (IOException e) {
                    throw new RuntimeException("Could not read " + location, e);
                } catch (TransformerException e) {
                    throw new RuntimeException("Could not parse " + location, e);
                }
            } catch (TransformerConfigurationException | TransformerFactoryConfigurationError e) {
                throw new RuntimeException(e);
            }

            final XPath xPath = XPathFactory.newInstance().newXPath();

            Set<GavPattern> includes = new LinkedHashSet<>();
            processPatterns(location, document, xPath, "//*[local-name() = 'exclude']/text()", includes::add);

            bannedPatterns = includes;
        }
        return bannedPatterns;
    }

    static Document transform(String sourceLocation, Document sourceDocument, String xsltLocation, Charset charset) {
        if (xsltLocation == null || xsltLocation.isBlank()) {
            return sourceDocument;
        }
        try (Reader xsltReader = openReader(xsltLocation, charset)) {
            Transformer transformer = TransformerFactory.newInstance().newTransformer(new StreamSource(xsltReader));
            final DOMResult result = new DOMResult();
            transformer.transform(new DOMSource(sourceDocument), result);
            return (Document) result.getNode();
        } catch (IOException e) {
            throw new RuntimeException("Could not open resource " + xsltLocation);
        } catch (TransformerException e) {
            throw new RuntimeException("Could not transform " + sourceLocation + " usinng XSLT " + xsltLocation);
        }
    }

    static void processPatterns(String path, Document document, XPath xPath, String xPathExpression,
            Consumer<GavPattern> gavPatternConsumer) {
        try {
            final NodeList nodes = (NodeList) xPath.evaluate(xPathExpression, document, XPathConstants.NODESET);
            for (int i = 0; i < nodes.getLength(); i++) {
                final Node n = nodes.item(i);
                final String bannedPattern = n.getTextContent();
                gavPatternConsumer.accept(GavPattern.of(bannedPattern));
            }
        } catch (XPathExpressionException e) {
            throw new RuntimeException("Could not evaluate " + xPathExpression + " on " + path, e);
        }
    }

    static Reader openReader(String path, Charset charset) throws IOException {
        final String prefix = "classpath:";
        if (path.startsWith(prefix)) {
            path = path.substring(prefix.length());
            if (path.startsWith("/")) {
                path = path.substring(1);
            }
            return new InputStreamReader(BannedDependencyResource.class.getClassLoader().getResourceAsStream(path), charset);
        } else {
            return Files.newBufferedReader(Paths.get(path), charset);
        }
    }

    @Override
    public String toString() {
        return "BannedDependencyResource [location=" + location + ", xsltLocation=" + xsltLocation + "]";
    }

}
