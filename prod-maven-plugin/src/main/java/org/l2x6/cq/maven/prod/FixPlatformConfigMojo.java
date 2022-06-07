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
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.l2x6.cq.common.CqCommonUtils;
import org.l2x6.pom.tuner.PomTransformer;
import org.l2x6.pom.tuner.PomTransformer.ContainerElement;
import org.l2x6.pom.tuner.PomTransformer.NodeGavtcs;
import org.l2x6.pom.tuner.PomTransformer.SimpleElementWhitespace;
import org.l2x6.pom.tuner.PomTransformer.Transformation;
import org.l2x6.pom.tuner.PomTransformer.TransformationContext;
import org.l2x6.pom.tuner.model.Ga;
import org.l2x6.pom.tuner.model.Gav;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * @since 2.32.0
 */
@Mojo(name = "fix-platform-config", threadSafe = true, requiresProject = false, inheritByDefault = false)
public class FixPlatformConfigMojo extends AbstractMojo {

    private final static Pattern PROD_VERSION_PATTERN = Pattern.compile("[\\.\\-]redhat-\\d\\d\\d\\d\\d");

    /**
     * The basedir
     *
     * @since 2.32.0
     */
    @Parameter(property = "cq.basedir", defaultValue = "${project.basedir}")
    File basedir;

    /**
     * Encoding to read and write files in the current source tree
     *
     * @since 2.32.0
     */
    @Parameter(defaultValue = "utf-8", required = true, property = "cq.encoding")
    String encoding;
    Charset charset;

    /**
     * How to format simple XML elements ({@code <elem/>}) - with or without space before the slash.
     *
     * @since 2.32.0
     */
    @Parameter(property = "cq.simpleElementWhitespace", defaultValue = "SPACE")
    SimpleElementWhitespace simpleElementWhitespace;

    /**
     * Skip the execution of this mojo.
     *
     * @since 2.32.0
     */
    @Parameter(property = "cq.fix-platform-config.skip", defaultValue = "false")
    boolean skip;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {

        charset = Charset.forName(encoding);

        new PomTransformer(basedir.toPath().resolve("pom.xml"), charset, simpleElementWhitespace).transform(fixHiddenMembers());

    }

    Transformation fixHiddenMembers() {
        return (Document document, TransformationContext context) -> {
            final ContainerElement platformConfig = context.getProfileParent(null).get()
                    .getChildContainerElement("build", "plugins").get()
                    .childElementsStream()
                    .map(ContainerElement::asGavtcs)
                    .filter(gavtcs -> gavtcs.getArtifactId().equals("quarkus-platform-bom-maven-plugin"))
                    .findFirst().get().getNode()
                    .getChildContainerElement("configuration", "platformConfig").get();

            final Map<String, Member> mainContextMembers = new TreeMap<>();
            platformConfig.getChildContainerElement("members").get().childElementsStream()
                    .map(elem -> new Member(null, elem, basedir.toPath(), charset))
                    .forEach(m -> mainContextMembers.put(m.name, m));

            final ContainerElement prodPlatformConfig = context.getProfileParent("rhproduct").get()
                    .getChildContainerElement("build", "plugins").get()
                    .childElementsStream()
                    .map(ContainerElement::asGavtcs)
                    .filter(gavtcs -> gavtcs.getArtifactId().equals("quarkus-platform-bom-maven-plugin"))
                    .findFirst().get().getNode()
                    .getChildContainerElement("configuration", "platformConfig").get();
            final List<Member> members = prodPlatformConfig.getChildContainerElement("members").get().childElementsStream()
                    .map(elem -> new Member(mainContextMembers, elem, basedir.toPath(), charset))
                    .collect(Collectors.toList());

            final Set<Ga> prodGasRequiredBySupportedMembers = new TreeSet<>();
            Stream.concat(
                    Stream.of(new GeneratedMember(basedir.toPath().resolve("generated-platform-project/quarkus/bom/pom.xml"),
                            charset)),
                    members.stream()
                            .filter(member -> !member.isHidden())
                            .peek(member -> System.out.println("==== prod member " + member.name))
                            .map(Member::getGeneratedMember))
                    .flatMap(genMember -> genMember.getProdConstraints().stream())
                    .forEach(prodGasRequiredBySupportedMembers::add);
            final Set<Ga> prodGasNotRequiredBySupportedMembers = new TreeSet<>();
            new GeneratedMember(basedir.toPath().resolve("generated-platform-project/quarkus-universe/bom/pom.xml"), charset)
                    .getProdConstraints().stream()
                    .filter(gav -> !prodGasRequiredBySupportedMembers.contains(gav))
                    .forEach(prodGasNotRequiredBySupportedMembers::add);

            members.stream()
                    .filter(Member::isHidden)
                    .forEach(member -> {

                        new PomTransformer(member.getGeneratedBomPath(), charset, simpleElementWhitespace)
                            .transform(removeSuffix(prodGasNotRequiredBySupportedMembers, PROD_VERSION_PATTERN));

                        final List<Dependency> depsToReplace = member.getGeneratedMember().getConstraints().stream()
                                .filter(dep -> prodGasNotRequiredBySupportedMembers.contains(toGa(dep)))
                                .collect(Collectors.toList());

                        if (!depsToReplace.isEmpty()) {


                            /* Remove any existing children */
                            ContainerElement dependencyManagement = member.getNode()
                                    .getOrAddChildContainerElement("dependencyManagement");
                            final List<ContainerElement> oldChildren = dependencyManagement.childElementsStream()
                                    .collect(Collectors.toList());
                            oldChildren.forEach(ch -> ch.remove(true, true));

                            depsToReplace.forEach(dep -> {
                                if (dep.getExclusions() != null && !dep.getExclusions().isEmpty()) {
                                    final ContainerElement dependencySpec = dependencyManagement
                                            .addChildContainerElement("dependencySpec");
                                    dependencySpec.addChildTextElement("artifact", toNonProdGav(dep).toString());
                                    final ContainerElement exclusions = dependencySpec
                                            .addChildContainerElement("exclusions");
                                    dep.getExclusions()
                                            .forEach(excl -> exclusions.addChildTextElement("exclusion",
                                                    excl.getGroupId() + ":" + excl.getArtifactId()));
                                } else {
                                    dependencyManagement.addChildTextElement("dependency", toNonProdGav(dep).toString());
                                }
                            });
                        }

                    });

        };
    }

    public static Transformation removeSuffix(Collection<Ga> gas, Pattern pattern) {
        return (Document document, TransformationContext context) -> {
            final ContainerElement profileParent = context.getProfileParent(null).get();
            final ContainerElement dependencyManagementDeps = profileParent
                    .getChildContainerElement("dependencyManagement").orElseThrow(
                            () -> new IllegalStateException("dependencyManagement not found in " + context.getPomXmlPath()))
                    .getChildContainerElement("dependencies").orElseThrow(
                            () -> new IllegalStateException(
                                    "dependencyManagement/dependencies not found in "
                                            + context.getPomXmlPath()));

            for (ContainerElement dep : dependencyManagementDeps.childElements()) {
                final NodeGavtcs gav = dep.asGavtcs();
                if (gas.contains(gav.toGa())) {
                    dep.setVersion(pattern.matcher(gav.getVersion()).replaceFirst(""));
                }
            }
        };
    }

    static Ga toGa(Dependency dep) {
        return new Ga(dep.getGroupId(), dep.getArtifactId());
    }

    static Gav toNonProdGav(Dependency dep) {
        return new Gav(dep.getGroupId(), dep.getArtifactId(), PROD_VERSION_PATTERN.matcher(dep.getVersion()).replaceAll(""));
    }

    static class GeneratedMember {
        private final Set<Ga> prodConstraints;
        private final Model model;

        public GeneratedMember(Path bomPath, Charset charset) {
            this.model = CqCommonUtils.readPom(bomPath, charset);
            this.prodConstraints = model.getDependencyManagement()
                    .getDependencies()
                    .stream()
                    .filter(dep -> dep.getVersion().contains("redhat-"))
                    .map(FixPlatformConfigMojo::toGa)
                    .collect(Collectors.toCollection(TreeSet::new));
        }

        public Set<Ga> getProdConstraints() {
            return prodConstraints;
        }

        public List<Dependency> getConstraints() {
            return model.getDependencyManagement().getDependencies();
        }

    }

    static class Member {
        private final ContainerElement node;
        private final boolean hidden;
        private final String key;
        private GeneratedMember generatedMember;
        private final String name;
        private final Path basePath;
        private final Charset charset;

        public Member(Map<String, Member> mainContextMembers, ContainerElement node, Path basePath, Charset charset) {
            this.basePath = basePath;
            this.charset = charset;
            this.node = node;
            this.hidden = node.getChildContainerElement("hidden")
                    .map(el -> el.getNode())
                    .filter(n -> n != null)
                    .map(Element::getTextContent)
                    .map(Boolean::parseBoolean).orElse(false);
            this.name = node.getChildContainerElement("name").get().getNode().getTextContent();

            Member parent = mainContextMembers != null ? mainContextMembers.get(name) : null;
            this.key = parent != null ? parent.key : node.getChildContainerElement("release")
                    .orElseThrow(
                            () -> new IllegalStateException(
                                    "No <release> element under member config having name '" + name + "'"))
                    .getChildContainerElement("next")
                    .orElseThrow(
                            () -> new IllegalStateException("No <next> element under member config having name '" + name + "'"))
                    .getNode()
                    .getTextContent().split(":")[1].replace("-bom", "").replace("quarkus-hazelcast-client",
                            "quarkus-hazelcast");
        }

        public boolean isHidden() {
            return hidden;
        }

        public ContainerElement getNode() {
            return node;
        }

        public String getKey() {
            return key;
        }

        public Path getGeneratedBomPath() {
            return basePath.resolve("generated-platform-project/" + key + "/bom/pom.xml");
        }

        public GeneratedMember getGeneratedMember() {
            if (this.generatedMember == null) {
                this.generatedMember = new GeneratedMember(getGeneratedBomPath(), charset);
            }
            return generatedMember;
        }

    }

}
