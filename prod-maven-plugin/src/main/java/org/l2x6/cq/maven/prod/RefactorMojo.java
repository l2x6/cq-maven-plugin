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

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.shared.utils.io.DirectoryScanner;
import org.l2x6.pom.tuner.PomTransformer;
import org.l2x6.pom.tuner.PomTransformer.ContainerElement;
import org.l2x6.pom.tuner.PomTransformer.NodeGavtcs;
import org.l2x6.pom.tuner.PomTransformer.SimpleElementWhitespace;
import org.l2x6.pom.tuner.PomTransformer.TransformationContext;
import org.l2x6.pom.tuner.model.Gavtcs;
import org.w3c.dom.Document;
import org.w3c.dom.DocumentFragment;
import org.w3c.dom.Node;

/**
 * An ad hoc refactoring.
 */
@Mojo(name = "refactor", threadSafe = true, requiresProject = false, inheritByDefault = false)
public class RefactorMojo extends AbstractMojo {

    @Parameter(property = "cq.basedir", defaultValue = "${project.basedir}")
    File basedir;

    @Parameter(defaultValue = "utf-8", required = true, property = "cq.encoding")
    String encoding;
    Charset charset;

    @Parameter(property = "cq.simpleElementWhitespace", defaultValue = "SPACE")
    SimpleElementWhitespace simpleElementWhitespace;

    @Parameter(defaultValue = "${project.version}", readonly = true)
    String projectVersion;

    /**
     * A list of {@link DirectoryScanner}s selecting integration test {@code pom.xml} files.
     *
     * @since 1.4.0
     */
    @Parameter
    List<DirectoryScanner> integrationTests;

    static final Pattern NAME_PATTERN = Pattern.compile("^Camel Quarkus :: ([^:]+) :: ([^:]+)$");
    static final Pattern ARTIFACT_ID_PATTERN = Pattern.compile("^camel-quarkus-(.+?)-integration-test$");

    static final Pattern secondLevelPattern(String firstLevel) {
        return Pattern.compile(firstLevel + "/[^/]+/pom.xml");
    }

    /** {@inheritDoc} */
    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        charset = Charset.forName(encoding);

        for (DirectoryScanner scanner : integrationTests) {
            scanner.scan();
            final Path base = scanner.getBasedir().toPath().toAbsolutePath().normalize();
            for (String scannerPath : scanner.getIncludedFiles()) {
                final Path pomXmlPath = base.resolve(scannerPath);

                new PomTransformer(pomXmlPath, charset, simpleElementWhitespace).transform(
                        (Document document, TransformationContext context) -> {
                            final List<NodeGavtcs> virtualDeps = context.getDependencies().stream()
                                    .filter(Gavtcs::isVirtualDeployment)
                                    .collect(Collectors.toList());

                            if (!virtualDeps.isEmpty()) {

                                final ContainerElement profile = context
                                        .getOrAddContainerElement("profiles")
                                        .addChildContainerElement("profile");
                                profile.addChildTextElement("id", "virtualDependencies", profile.getOrAddLastIndent());
                                profile
                                        .addChildContainerElement("activation")
                                        .addChildContainerElement("property")
                                        .addChildTextElement("name", "!noVirtualDependencies");

                                final ContainerElement newDeps = profile
                                        .addChildContainerElement("dependencies");
                                for (NodeGavtcs dep : virtualDeps) {
                                    final DocumentFragment fragment = dep.getNode()
                                            .getFragment();
                                    context.reIndent(fragment, context.getIndentationString() + context.getIndentationString()
                                            + context.getIndentationString() + context.getIndentationString());
                                    newDeps.addFragment(fragment);
                                }
                            }
                        });
            }
        }
    }

    static class NodePredicate implements Predicate<Node> {
        private boolean virtualMarkerHit = false;

        @Override
        public boolean test(Node prevSibling) {
            return !virtualMarkerHit && (TransformationContext
                    .isWhiteSpaceNode(prevSibling)
                    || (prevSibling.getNodeType() == Node.COMMENT_NODE
                            && !(virtualMarkerHit = prevSibling.getTextContent().contains(
                                    "The following dependencies guarantee that this module is built after them."))));
        }

    }

}
