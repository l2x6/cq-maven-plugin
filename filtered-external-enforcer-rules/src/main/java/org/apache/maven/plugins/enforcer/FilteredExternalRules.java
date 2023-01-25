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
package org.apache.maven.plugins.enforcer;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.TransformerFactoryConfigurationError;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;
import org.apache.commons.io.output.ByteArrayOutputStream;
import org.apache.maven.enforcer.rule.api.EnforcerRule;
import org.apache.maven.enforcer.rule.api.EnforcerRuleException;
import org.apache.maven.enforcer.rule.api.EnforcerRuleHelper;
import org.apache.maven.plugin.MojoExecution;
import org.codehaus.plexus.classworlds.realm.ClassRealm;
import org.codehaus.plexus.component.configurator.ComponentConfigurator;
import org.codehaus.plexus.component.repository.exception.ComponentLookupException;
import org.codehaus.plexus.configuration.PlexusConfiguration;
import org.codehaus.plexus.configuration.xml.XmlPlexusConfiguration;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.codehaus.plexus.util.xml.Xpp3DomBuilder;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

/**
 * An enforcer rule that will invoke rules from an external resource
 *
 * @author <a href="mailto:gastaldi@apache.org">George Gastaldi</a>
 */
public class FilteredExternalRules implements EnforcerRule {
    private static final String LOCATION_PREFIX_CLASSPATH = "classpath:";

    /**
     * The external rules location. If it starts with "classpath:", the resource is read from the classpath
     */
    String location;

    /**
     * An optional location of a XSLT file used to transform the rule document available via {@link #location} before
     * it is applied.
     */
    String xsltLocation;

    public FilteredExternalRules() {
    }

    public FilteredExternalRules(String location, String xsltLocation) {
        this.location = location;
        this.xsltLocation = xsltLocation;
    }

    @Override
    public void execute(EnforcerRuleHelper helper) throws EnforcerRuleException {
        // Find descriptor
        EnforcerDescriptor enforcerDescriptor = getEnforcerDescriptor(helper);
        for (EnforcerRule rule : enforcerDescriptor.getRules()) {
            rule.execute(helper);
        }
    }

    /**
     * Resolve the {@link EnforcerDescriptor} based on the provided {@link #descriptor} or {@link #descriptorRef}
     *
     * @param  helper                used to build the {@link EnforcerDescriptor}
     * @return                       an {@link EnforcerDescriptor} for this rule
     * @throws EnforcerRuleException if any failure happens while reading the descriptor
     */
    EnforcerDescriptor getEnforcerDescriptor(EnforcerRuleHelper helper)
            throws EnforcerRuleException {
        try (InputStream descriptorStream = transform(helper, location, resolveDescriptor(helper, location), xsltLocation)) {
            EnforcerDescriptor descriptor = new EnforcerDescriptor();
            // To get configuration from the enforcer-plugin mojo do:
            // helper.evaluate(helper.getComponent(MojoExecution.class).getConfiguration().getChild("fail").getValue())
            // Configure EnforcerDescriptor from the XML
            ComponentConfigurator configurator = helper.getComponent(ComponentConfigurator.class, "basic");
            configurator.configureComponent(descriptor, toPlexusConfiguration(descriptorStream), helper,
                    getClassRealm(helper));
            return descriptor;
        } catch (EnforcerRuleException e) {
            throw e;
        } catch (Exception e) {
            throw new EnforcerRuleException("Error while enforcing rules", e);
        }
    }

    static InputStream transform(EnforcerRuleHelper helper, String sourceLocation, InputStream sourceXml, String xsltLocation) {
        if (xsltLocation == null || xsltLocation.isBlank()) {
            return sourceXml;
        }
        try (InputStream in = resolveDescriptor(helper, xsltLocation);
                ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            Transformer transformer = TransformerFactory.newInstance().newTransformer(new StreamSource(in));
            transformer.transform(new StreamSource(sourceXml), new StreamResult(baos));
            return new ByteArrayInputStream(baos.toByteArray());
        } catch (IOException | ComponentLookupException | EnforcerRuleException | TransformerConfigurationException
                | TransformerFactoryConfigurationError e) {
            throw new RuntimeException("Could not open resource " + xsltLocation);
        } catch (TransformerException e) {
            throw new RuntimeException("Could not transform " + sourceLocation + " usinng XSLT " + xsltLocation);
        }
    }

    static InputStream resolveDescriptor(EnforcerRuleHelper helper, String location)
            throws ComponentLookupException, EnforcerRuleException {
        InputStream descriptorStream;
        if (location != null) {
            if (location.startsWith(LOCATION_PREFIX_CLASSPATH)) {
                String classpathLocation = location.substring(LOCATION_PREFIX_CLASSPATH.length());
                ClassLoader classRealm = getClassRealm(helper);
                descriptorStream = classRealm.getResourceAsStream(classpathLocation);
                if (descriptorStream == null) {
                    throw new EnforcerRuleException("Location '" + classpathLocation + "' not found in classpath");
                }
            } else {
                File descriptorFile = helper.alignToBaseDirectory(new File(location));
                try {
                    descriptorStream = Files.newInputStream(descriptorFile.toPath());
                } catch (IOException e) {
                    throw new EnforcerRuleException("Could not read descriptor in " + descriptorFile, e);
                }
            }
        } else {
            throw new EnforcerRuleException("No location provided");
        }
        return descriptorStream;
    }

    private static PlexusConfiguration toPlexusConfiguration(InputStream descriptorStream)
            throws XmlPullParserException, IOException {
        Xpp3Dom dom = Xpp3DomBuilder.build(descriptorStream, "UTF-8");
        return new XmlPlexusConfiguration(dom);
    }

    static ClassRealm getClassRealm(EnforcerRuleHelper helper) throws ComponentLookupException {
        return helper.getComponent(MojoExecution.class).getMojoDescriptor().getRealm();
    }

    @Override
    public boolean isCacheable() {
        return false;
    }

    @Override
    public boolean isResultValid(EnforcerRule enforcerRule) {
        return false;
    }

    @Override
    public String getCacheId() {
        return location + (xsltLocation != null && !xsltLocation.isBlank() ? ("-" + xsltLocation) : "");
    }

}
