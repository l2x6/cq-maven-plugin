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
package org.l2x6.cq.camel.maven.prod;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.apache.maven.artifact.versioning.ComparableVersion;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.l2x6.pom.tuner.Comparators;
import org.l2x6.pom.tuner.MavenSourceTree;
import org.l2x6.pom.tuner.MavenSourceTree.ActiveProfiles;
import org.l2x6.pom.tuner.PomTransformer;
import org.l2x6.pom.tuner.PomTransformer.ContainerElement;
import org.l2x6.pom.tuner.PomTransformer.SimpleElementWhitespace;
import org.l2x6.pom.tuner.PomTransformer.TransformationContext;
import org.l2x6.pom.tuner.model.Dependency;
import org.l2x6.pom.tuner.model.GavtcsPattern;
import org.l2x6.pom.tuner.model.Profile;
import org.w3c.dom.Document;

/**
 * Initialize a Camel product branch.
 *
 * @since 4.19.0
 */
@Mojo(name = "camel-prod-init", threadSafe = true, requiresProject = false, inheritByDefault = false)
public class CamelProdInitMojo extends AbstractMojo {

    private static final String RHBAC_SNAPSHOT_SUFFIX = ".rhbac-SNAPSHOT";

    /**
     * The basedir
     *
     * @since 4.19.0
     */
    @Parameter(property = "cq.basedir", defaultValue = "${project.basedir}")
    File basedir;

    /**
     * Encoding to read and write files in the current source tree
     *
     * @since 4.19.0
     */
    @Parameter(defaultValue = "utf-8", required = true, property = "cq.encoding")
    String encoding;
    Charset charset;

    /**
     * Skip the execution of this mojo.
     *
     * @since 3.0.0
     */
    @Parameter(property = "cq.camel-prod-init.skip", defaultValue = "false")
    boolean skip;

    /**
     * How to format simple XML elements ({@code <elem/>}) - with or without space before the slash.
     *
     * @since 3.0.0
     */
    @Parameter(property = "cq.simpleElementWhitespace", defaultValue = "SPACE")
    SimpleElementWhitespace simpleElementWhitespace;

    /**
     * The current project's version
     *
     * @since 3.0.0
     */
    @Parameter(defaultValue = "${project.version}", readonly = true)
    String version;

    /**
     * A name of a product branch from which the current branch should be initilized.
     *
     * @since 3.0.0
     */
    @Parameter(defaultValue = "${cq.fromBranch}", required = true)
    String fromBranch;

    /**
     * The root directory of the Camel Quarkus source tree.
     *
     * @since 3.0.0
     */
    @Parameter(defaultValue = "${maven.multiModuleProjectDirectory}", readonly = true)
    File multiModuleProjectDirectory;

    @Parameter(defaultValue = "${plugin}", readonly = true)
    private PluginDescriptor pluginDescriptor;

    /** {@inheritDoc} */
    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("Skipping as requested by the user");
            return;
        }
        charset = Charset.forName(encoding);

        if (version.endsWith(RHBAC_SNAPSHOT_SUFFIX)) {
            throw new IllegalStateException(
                    "This branch seems to have been initialized already, because it already has a version ending with '"
                            + RHBAC_SNAPSHOT_SUFFIX + "'");
        }
        if (version.endsWith("-SNAPSHOT")) {
            throw new IllegalStateException(
                    "Cannot initialize a -SNAPSHOT version; expecting a release version, such as 2.7.1");
        }

        /* Add .rhbac-SNAPSHOT suffix to the version */
        final Predicate<Profile> profiles = ActiveProfiles.of();
        final Path pomXmlPath = basedir.toPath().resolve("pom.xml");
        final MavenSourceTree t = MavenSourceTree.of(pomXmlPath, charset, Dependency::isVirtual);
        t.setVersions(version + RHBAC_SNAPSHOT_SUFFIX, profiles, simpleElementWhitespace);

        /* Edit root pom.xml */
        new PomTransformer(pomXmlPath, charset, simpleElementWhitespace).transform(
                (Document document, TransformationContext context) -> {

                    /* Add some community props */
                    final ContainerElement props = context.getOrAddContainerElement("properties");

                    getLog().info("Adding to pom.xml: camel-community-version property");
                    props.addChildTextElementIfNeeded("camel-community-version", version,
                            Comparator.comparing(Map.Entry::getKey, Comparators.beforeFirst()));

                    /*
                     * Set cq-plugin.version to the version of the currently executing mojo if it is newer than the
                     * one on pom.xml
                     */
                    final String currentCqVersion = pluginDescriptor.getVersion();
                    final String availableCqPluginVersion = props.getChildContainerElement("cq-plugin.version")
                            .orElseThrow(() -> new IllegalStateException(
                                    "Could not find cq-plugin.version property in the root pom.xml file"))
                            .getNode().getTextContent();
                    if (new ComparableVersion(availableCqPluginVersion)
                            .compareTo(new ComparableVersion(currentCqVersion)) < 0) {
                        getLog().info("Upgrading in pom.xml: cq-plugin.version " + availableCqPluginVersion + " -> "
                                + currentCqVersion);
                        props.addOrSetChildTextElement("cq-plugin.version", currentCqVersion);
                    }

                    /* Change the version of camel-build-tools under license-maven-plugin to camel-community-version */
                    final ContainerElement managedPlugins = context.getOrAddContainerElements(
                            "build",
                            "pluginManagement",
                            "plugins");
                    final ContainerElement licensePlugin = managedPlugins.childElementsStream()
                            .map(ContainerElement::asGavtcs)
                            .filter(GavtcsPattern.of("com.mycila:license-maven-plugin"))
                            .findFirst()
                            .orElseThrow()
                            .getNode();
                    final ContainerElement excludes = licensePlugin
                            .getOrAddChildContainerElement("configuration")
                            .getOrAddChildContainerElement("licenseSets")
                            .getOrAddChildContainerElement("licenseSet")
                            .getOrAddChildContainerElement("excludes");
                    Stream.of("**/Cargo.lock", "build.metadata", ".snyk")
                            .forEach(pattern -> excludes.addChildTextElementIfNeeded(
                                    "exclude",
                                    pattern,
                                    Comparator.comparing(Map.Entry::getValue, Comparators.beforeFirst())));
                });

    }

}
