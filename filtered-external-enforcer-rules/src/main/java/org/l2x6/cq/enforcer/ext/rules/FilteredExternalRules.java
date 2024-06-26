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
package org.l2x6.cq.enforcer.ext.rules;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Objects;
import javax.inject.Inject;
import javax.inject.Named;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.TransformerFactoryConfigurationError;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;
import org.apache.maven.enforcer.rule.api.AbstractEnforcerRuleConfigProvider;
import org.apache.maven.enforcer.rule.api.EnforcerRuleError;
import org.apache.maven.enforcer.rule.api.EnforcerRuleException;
import org.apache.maven.plugin.MojoExecution;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.codehaus.plexus.util.xml.Xpp3DomBuilder;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

/**
 * An enforcer rule that will provide rules configuration from an external resource.
 *
 * @author <a href="mailto:gastaldi@apache.org">George Gastaldi</a>
 * @since  3.2.0
 */
@Named("filteredExternalRules")
public final class FilteredExternalRules extends AbstractEnforcerRuleConfigProvider {
    private static final String LOCATION_PREFIX_CLASSPATH = "classpath:";

    /**
     * The external rules location. If it starts with "classpath:", the resource is read from the classpath.
     * Otherwise, it is handled as a filesystem path, either absolute, or relative to <code>${project.basedir}</code>
     */
    private String location;
    /**
     * An optional location of a XSLT file used to transform the rule document available via {@link #location} before
     * it is applied.
     */
    private String xsltLocation;

    private final MojoExecution mojoExecution;

    private final CqExpressionEvaluator evaluator;

    @Inject
    public FilteredExternalRules(MojoExecution mojoExecution, CqExpressionEvaluator evaluator) {
        this.mojoExecution = Objects.requireNonNull(mojoExecution);
        this.evaluator = Objects.requireNonNull(evaluator);
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public void setXsltLocation(String xsltLocation) {
        this.xsltLocation = xsltLocation;
    }

    @Override
    public Xpp3Dom getRulesConfig() throws EnforcerRuleError {

        try (InputStream descriptorStream = transform(location, resolveDescriptor(location), xsltLocation)) {
            Xpp3Dom enforcerRules = Xpp3DomBuilder.build(descriptorStream, "UTF-8");
            if (enforcerRules.getChildCount() == 1 && "enforcer".equals(enforcerRules.getName())) {
                return enforcerRules.getChild(0);
            } else {
                throw new EnforcerRuleError("Enforcer rules configuration not found in: " + location);
            }
        } catch (IOException | XmlPullParserException e) {
            throw new EnforcerRuleError(e);
        }
    }

    InputStream resolveDescriptor(String path) throws EnforcerRuleError {
        InputStream descriptorStream;
        if (path != null) {
            if (path.startsWith(LOCATION_PREFIX_CLASSPATH)) {
                String classpathLocation = path.substring(LOCATION_PREFIX_CLASSPATH.length());
                getLog().debug("Read rules form classpath location: " + classpathLocation);
                ClassLoader classRealm = mojoExecution.getMojoDescriptor().getRealm();
                descriptorStream = classRealm.getResourceAsStream(classpathLocation);
                if (descriptorStream == null) {
                    throw new EnforcerRuleError("Location '" + classpathLocation + "' not found in classpath");
                }
            } else {
                File descriptorFile = evaluator.alignToBaseDirectory(new File(path));
                getLog().debug("Read rules form file location: " + descriptorFile);
                try {
                    descriptorStream = Files.newInputStream(descriptorFile.toPath());
                } catch (IOException e) {
                    throw new EnforcerRuleError("Could not read descriptor in " + descriptorFile, e);
                }
            }
        } else {
            throw new EnforcerRuleError("No location provided");
        }
        return descriptorStream;
    }

    InputStream transform(String sourceLocation, InputStream sourceXml, String xsltLocation) {
        if (xsltLocation == null || xsltLocation.isBlank()) {
            return sourceXml;
        }
        try (InputStream in = resolveDescriptor(xsltLocation);
                ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            Transformer transformer = TransformerFactory.newInstance().newTransformer(new StreamSource(in));
            transformer.transform(new StreamSource(sourceXml), new StreamResult(baos));
            return new ByteArrayInputStream(baos.toByteArray());
        } catch (IOException | EnforcerRuleException | TransformerConfigurationException
                | TransformerFactoryConfigurationError e) {
            throw new RuntimeException("Could not open resource " + xsltLocation);
        } catch (TransformerException e) {
            throw new RuntimeException("Could not transform " + sourceLocation + " usinng XSLT " + xsltLocation);
        }
    }

    @Override
    public String toString() {
        return String.format("ExternalRules[location=%s, xsltLocation=%s]", location, xsltLocation);
    }
}
