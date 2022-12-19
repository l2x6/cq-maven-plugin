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
package org.l2x6.cq.maven.prod;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.apache.maven.artifact.versioning.ComparableVersion;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.DepthWalk.RevWalk;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.l2x6.pom.tuner.Comparators;
import org.l2x6.pom.tuner.MavenSourceTree;
import org.l2x6.pom.tuner.MavenSourceTree.ActiveProfiles;
import org.l2x6.pom.tuner.PomTransformer;
import org.l2x6.pom.tuner.PomTransformer.ContainerElement;
import org.l2x6.pom.tuner.PomTransformer.NodeGavtcs;
import org.l2x6.pom.tuner.PomTransformer.SimpleElementWhitespace;
import org.l2x6.pom.tuner.PomTransformer.TransformationContext;
import org.l2x6.pom.tuner.PomTunerUtils;
import org.l2x6.pom.tuner.model.Dependency;
import org.l2x6.pom.tuner.model.Gavtcs;
import org.l2x6.pom.tuner.model.Profile;
import org.w3c.dom.Document;

/**
 * Initialize a CEQ product branch.
 *
 * @since 3.0.0
 */
@Mojo(name = "prod-init", threadSafe = true, requiresProject = false, inheritByDefault = false)
public class ProdInitMojo extends AbstractMojo {

    private static final String FUSE_SNAPSHOT_SUFFIX = ".fuse-SNAPSHOT";

    /**
     * The basedir
     *
     * @since 3.0.0
     */
    @Parameter(property = "cq.basedir", defaultValue = "${project.basedir}")
    File basedir;

    /**
     * Encoding to read and write files in the current source tree
     *
     * @since 0.40.0
     */
    @Parameter(defaultValue = "utf-8", required = true, property = "cq.encoding")
    String encoding;
    Charset charset;

    @Parameter(defaultValue = ProdExcludesMojo.CAMEL_QUARKUS_PRODUCT_SOURCE_JSON_PATH, required = true, property = "cq.productJson")
    File productJson;

    /**
     * Skip the execution of this mojo.
     *
     * @since 3.0.0
     */
    @Parameter(property = "cq.prod-init.skip", defaultValue = "false")
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

    /**
     * The Camel version
     *
     * @since 3.0.0
     */
    @Parameter(property = "camel.version")
    String camelVersion;

    /**
     * Quarkus version
     *
     * @since 3.0.0
     */
    @Parameter(defaultValue = "${quarkus.version}", readonly = true)
    String quarkusVersion;

    /**
     * Quarkiverse CXF version
     *
     * @since 3.3.0
     */
    @Parameter(defaultValue = "${quarkiverse-cxf.version}", readonly = true)
    String quarkiverseCxfVersion;

    /**
     * GRPC version
     *
     * @since 3.3.0
     */
    @Parameter(defaultValue = "${grpc.version}", readonly = true)
    String grpcVersion;

    /**
     * ProtoBuf version
     *
     * @since 3.3.0
     */
    @Parameter(defaultValue = "${protobuf.version}", readonly = true)
    String protobufVersion;

    /** {@inheritDoc} */
    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("Skipping as requested by the user");
            return;
        }
        charset = Charset.forName(encoding);

        final String majorVersion = version.split("\\.")[0];

        if (version.endsWith(FUSE_SNAPSHOT_SUFFIX)) {
            throw new IllegalStateException(
                    "This branch seems to have been initialized already, because it already has a version ending with '.fuse-SNAPSHOT'");
        }
        if (version.endsWith("-SNAPSHOT")) {
            throw new IllegalStateException(
                    "Cannot initialize a -SNAPSHOT version; expecting a release version, such as 2.7.1");
        }

        /* Add .fuse-SNAPSHOT suffix to the version */
        final Predicate<Profile> profiles = ActiveProfiles.of();
        final Path pomXmlPath = basedir.toPath().resolve("pom.xml");
        final MavenSourceTree t = MavenSourceTree.of(pomXmlPath, charset, Dependency::isVirtual);
        t.setVersions(version + FUSE_SNAPSHOT_SUFFIX, profiles, simpleElementWhitespace);

        /* Edit root pom.xml */
        new PomTransformer(pomXmlPath, charset, simpleElementWhitespace).transform(
                (Document document, TransformationContext context) -> {

                    /* Add some community props */
                    final ContainerElement props = context.getOrAddContainerElement("properties");

                    getLog().info("Adding to pom.xml: camel-community.version property");
                    props.addChildTextElementIfNeeded("camel-community.version",
                            "${camel.major.minor}." + camelVersion.split("\\.")[2],
                            Comparator.comparing(Map.Entry::getKey, Comparators.before("camel.version")));

                    getLog().info("Adding to pom.xml: camel-quarkus-community.version property");
                    props.addChildTextElementIfNeeded("camel-quarkus-community.version", version,
                            Comparator.comparing(Map.Entry::getKey, Comparators.before("cassandra-quarkus.version")));

                    getLog().info("Adding to pom.xml: quarkus-community.version property");
                    props.addChildTextElementIfNeeded("quarkus-community.version", quarkusVersion,
                            Comparator.comparing(Map.Entry::getKey, Comparators.after("quarkus.version")));

                    getLog().info("Adding to pom.xml: quarkiverse-cxf-community.version property");
                    props.addChildTextElementIfNeeded("quarkiverse-cxf-community.version", quarkiverseCxfVersion,
                            Comparator.comparing(Map.Entry::getKey, Comparators.after("quarkiverse-cxf.version")));

                    getLog().info("Adding to pom.xml: graalvm-community.version property");
                    props.addChildTextElementIfNeeded("graalvm-community.version", "${graalvm.version}",
                            Comparator.comparing(Map.Entry::getKey, Comparators.after("graalvm.version")));

                    getLog().info("Adding to pom.xml: grpc-community.version property");
                    props.addChildTextElementIfNeeded("grpc-community.version", grpcVersion,
                            Comparator.comparing(Map.Entry::getKey, Comparators.after("grpc.version")));

                    getLog().info("Adding to pom.xml: protobuf-community.version property");
                    props.addChildTextElementIfNeeded("protobuf-community.version", protobufVersion,
                            Comparator.comparing(Map.Entry::getKey, Comparators.after("protobuf.version")));

                    getLog().info("Setting camel-quarkus.extension.finder.strict = false in pom.xml");
                    props.addOrSetChildTextElement("camel-quarkus.extension.finder.strict", "false");

                    /* Set cq-plugin.version to the version of the currently executing mojo if it is newer than than the one on pom.xml */
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

                    /* Remove docs module */
                    getLog().info("Removing from pom.xml: docs module");
                    final String xPath = PomTunerUtils.anyNs("project", "modules", "module") + "[text() = 'docs']";
                    context.removeNode(xPath, true, true, false);

                    /* Add product module */
                    getLog().info("Adding to pom.xml: product module");
                    final ContainerElement profileParent = context.getOrAddProfileParent(null);
                    final ContainerElement modules = profileParent.getOrAddChildContainerElement("modules");
                    modules.addChildTextElement("module", "product");

                    /* Change the version of camel-build-tools under license-maven-plugin to camel-community.version */
                    final ContainerElement managedPlugins = context.getOrAddContainerElements("build", "pluginManagement",
                            "plugins");
                    final ContainerElement licensePlugin = managedPlugins.childElementsStream()
                            .map(ContainerElement::asGavtcs)
                            .filter(gavtcs -> "com.mycila".equals(gavtcs.getGroupId())
                                    && "license-maven-plugin".equals(gavtcs.getArtifactId()))
                            .findFirst()
                            .orElseThrow()
                            .getNode();
                    final ContainerElement buildToolsDep = licensePlugin
                            .getOrAddChildContainerElement("dependencies")
                            .getOrAddChildContainerElement("dependency");
                    final String camelCommunityVersion = "${camel-community.version}";
                    getLog().info("Setting version in pom.xml: camel-buildtools "
                            + buildToolsDep.getChildContainerElement("version").get().getNode().getTextContent() + " -> "
                            + camelCommunityVersion);
                    buildToolsDep.setVersion(camelCommunityVersion);

                    ContainerElement mappingsElement = licensePlugin
                            .getChildContainerElement("configuration", "mapping")
                            .orElseThrow(() -> new IllegalStateException(
                                    "Could not find <mappings> in the cofiguration of com.mycila:license-maven-plugin"));
                    mappingsElement.addChildTextElementIfNeeded("Jenkinsfile.redhat", "SLASHSTAR_STYLE",
                            Comparator.comparing(Map.Entry::getKey, Comparators.after("Jenkinsfile")));

                    /* Add cq-prod-maven-plugin twice */
                    final Gavtcs cqProdMavenPluginGav = new Gavtcs("org.l2x6.cq", "cq-prod-maven-plugin",
                            "${cq-plugin.version}");
                    getLog().info("Adding to pom.xml: " + cqProdMavenPluginGav);
                    managedPlugins.addGavtcsIfNeeded(cqProdMavenPluginGav,
                            Gavtcs.groupFirstComparator());

                    final ContainerElement plugins = context.getOrAddContainerElements("build", "plugins");
                    final ContainerElement cqPluginElement = plugins
                            .addGavtcs(new Gavtcs("org.l2x6.cq", "cq-prod-maven-plugin", null));
                    final ContainerElement execution = cqPluginElement.getOrAddChildContainerElement("executions")
                            .getOrAddChildContainerElement("execution");
                    execution.addChildTextElement("id", "check-excludes");
                    execution.addChildTextElement("phase", "validate");
                    execution.addChildTextElement("inherited", "false");
                    final ContainerElement goals = execution.getOrAddChildContainerElement("goals");
                    goals.addChildTextElement("goal", "prod-excludes-check");

                });

        /* Edit poms/bom/pom.xml */
        new PomTransformer(basedir.toPath().resolve("poms/bom/pom.xml"), charset, simpleElementWhitespace).transform(
                (Document document, TransformationContext context) -> {

                    /* Change the version of org.graalvm.js:* from ${graalvm.version} to ${graalvm-community.version} */
                    final ContainerElement dependencyManagementDeps = context.getOrAddContainerElements(
                            "dependencyManagement",
                            "dependencies");
                    dependencyManagementDeps.childElementsStream()
                            .map(ContainerElement::asGavtcs)
                            .filter(gavtcs -> gavtcs.getGroupId().equals("org.graalvm.js"))
                            .forEach(node -> node.getNode().setVersion("${graalvm-community.version}"));
                });

        /* Edit poms/bom-test/pom.xml */
        new PomTransformer(basedir.toPath().resolve("poms/bom-test/pom.xml"), charset, simpleElementWhitespace).transform(
                (Document document, TransformationContext context) -> {

                    /* Change the version of io.quarkiverse.cxf:quarkus-cxf-bom-test from ${quarkiverse-cxf.version} to ${quarkiverse-cxf-community.version} */
                    Gavtcs qcxfBom = new Gavtcs("io.quarkiverse.cxf", "quarkus-cxf-bom-test", "${quarkiverse-cxf.version}",
                            "pom", null, "import");
                    final ContainerElement dependencyManagementDeps = context.getOrAddContainerElements(
                            "dependencyManagement",
                            "dependencies");
                    final NodeGavtcs qcxfBomNode = dependencyManagementDeps.childElementsStream()
                            .map(ContainerElement::asGavtcs)
                            .filter(gavtcs -> gavtcs.equals(qcxfBom))
                            .findFirst()
                            .get();
                    qcxfBomNode.getNode().setVersion("${quarkiverse-cxf-community.version}");
                });

        /* Edit integration-tests/grpc/pom.xml */
        new PomTransformer(basedir.toPath().resolve("integration-tests/grpc/pom.xml"), charset, simpleElementWhitespace)
                .transform(
                        (Document document, TransformationContext context) -> {

                            final ContainerElement plugins = context.getOrAddContainerElements(
                                    "build", "plugins");
                            final NodeGavtcs protobufPlugnNode = plugins.childElementsStream()
                                    .map(ContainerElement::asGavtcs)
                                    .filter(gavtcs -> gavtcs.getArtifactId().equals("protobuf-maven-plugin"))
                                    .findFirst()
                                    .get();
                            ContainerElement config = protobufPlugnNode.getNode()
                                    .getChildContainerElement("executions", "execution", "configuration").get();
                            config.addOrSetChildTextElement("protocArtifact",
                                    "com.google.protobuf:protoc:${protobuf-community.version}:exe:${os.detected.classifier}");
                            config.addOrSetChildTextElement("pluginArtifact",
                                    "io.grpc:protoc-gen-grpc-java:${grpc-community.version}:exe:${os.detected.classifier}");
                        });

        // Force Camel community version for unsupported Maven plugins
        final Path buildParentItPomPath = basedir.toPath().resolve("poms/build-parent-it/pom.xml");
        new PomTransformer(buildParentItPomPath, charset, simpleElementWhitespace)
                .transform((Document document, TransformationContext context) -> {
                    final ContainerElement managedPlugins = context.getOrAddContainerElements("build", "pluginManagement",
                            "plugins");
                    managedPlugins.childElementsStream()
                            .map(ContainerElement::asGavtcs)
                            .filter(gavtcs -> "camel-salesforce-maven-plugin".equals(gavtcs.getArtifactId())
                                    || "camel-servicenow-maven-plugin".equals(gavtcs.getArtifactId()))
                            .peek(gavtcs -> getLog()
                                    .info("Updating " + gavtcs.getArtifactId() + " version to ${camel-community.version}"))
                            .map(PomTransformer.NodeGavtcs::getNode)
                            .forEach(containerElement -> containerElement.setVersion("${camel-community.version}"));
                });

        /*
         * Copy certain files from the older product branch
         */
        final List<String> filesToCopy = Arrays.asList("Jenkinsfile.redhat",
                "product/jenkinsfile-stage-template.txt",
                "product/pom.xml",
                "product/README.adoc",
                "product/src/main/groovy/generate-camel-quarkus-product-json.groovy",
                "product/src/main/resources/camel-quarkus-product-source.json");

        try (Repository repo = new FileRepositoryBuilder()
                .readEnvironment() // scan environment GIT_* variables
                .setWorkTree(basedir)
                .build()) {

            final ObjectId fromBranchId = repo.resolve(fromBranch);

            try (RevWalk revWalk = new RevWalk(repo, Integer.MAX_VALUE)) {
                final RevCommit commit = revWalk.parseCommit(fromBranchId);
                // and using commit's tree find the path
                final RevTree tree = commit.getTree();

                for (String relPath : filesToCopy) {
                    getLog().info("Copying from " + fromBranch + ": " + relPath);
                    try (TreeWalk treeWalk = new TreeWalk(repo)) {
                        treeWalk.addTree(tree);
                        treeWalk.setRecursive(true);
                        treeWalk.setFilter(PathFilter.create(relPath));
                        if (!treeWalk.next()) {
                            throw new IllegalStateException("Could not find '" + relPath + "' in branch " + fromBranch);
                        }
                        final ObjectId objectId = treeWalk.getObjectId(0);

                        final Path destFile = basedir.toPath().resolve(relPath);
                        Files.createDirectories(destFile.getParent());
                        try (OutputStream out = Files.newOutputStream(destFile)) {
                            repo.open(objectId).copyTo(out);
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Could not copy files from " + fromBranch + " to the work tree", e);
        }

        /* Adjust the version in product/pom.xml */
        final String newVersion = version + FUSE_SNAPSHOT_SUFFIX;
        getLog().info("Setting versions to " + newVersion);
        new PomTransformer(basedir.toPath().resolve("product/pom.xml"), charset, simpleElementWhitespace)
                .transform((Document document, TransformationContext context) -> {
                    ContainerElement parent = context.getContainerElement("project", "parent")
                            .orElseThrow(() -> new IllegalStateException("No parent element in " + context.getPomXmlPath()));
                    parent.setVersion(newVersion);
                });
    }

}
