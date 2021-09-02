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
package org.l2x6.cq.maven;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.lifecycle.internal.MojoDescriptorCreator;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.PluginParameterExpressionEvaluator;
import org.apache.maven.plugin.descriptor.MojoDescriptor;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.component.configurator.expression.ExpressionEvaluationException;
import org.eclipse.aether.repository.RemoteRepository;
import org.l2x6.cq.common.PomModelCache;
import org.l2x6.maven.utils.PomTransformer;
import org.l2x6.maven.utils.PomTransformer.SimpleElementWhitespace;
import org.l2x6.maven.utils.PomTransformer.Transformation;
import org.l2x6.maven.utils.PomTransformer.TransformationContext;
import org.l2x6.maven.utils.PomTransformer.WrappedNode;
import org.w3c.dom.Comment;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Synchronizes version properties tagged with <code>@sync</code>.
 *
 * @since 0.36.0
 */
@Mojo(name = "sync-versions", requiresProject = true, inheritByDefault = false)
public class SyncVersionsMojo extends AbstractMojo {

    private static final Pattern SYNC_INSTRUCTION_PATTERN = Pattern
            .compile(
                    "\\s*@sync (?<groupId>[^:]+):(?<artifactId>[^:]+):(?<version>[^:]+) (?<method>[^:]+):(?<element>[^ ]+)\\s*");

    /**
     * Directory where the changes should be performed. Default is the current directory of the current Java process.
     *
     * @since 0.1.0
     */
    @Parameter(property = "cq.basedir", defaultValue = "${project.basedir}")
    File basedir;
    protected Path basePath;

    /**
     * Encoding to read and write files in the current source tree
     *
     * @since 0.0.1
     */
    @Parameter(defaultValue = CqUtils.DEFAULT_ENCODING, required = true, property = "cq.encoding")
    String encoding;
    Charset charset;

    @Parameter(defaultValue = "${settings.localRepository}", readonly = true)
    String localRepository;
    Path localRepositoryPath;

    @Parameter(defaultValue = "${project}", readonly = true)
    MavenProject project;

    @Parameter(defaultValue = "${project.remoteProjectRepositories}", readonly = true, required = true)
    List<RemoteRepository> repositories;

    @Component
    private MojoDescriptorCreator mojoDescriptorCreator;

    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    protected MavenSession session;

    /**
     * How to format simple XML elements ({@code <elem/>}) - with or without space before the slash.
     *
     * @since 0.38.0
     */
    @Parameter(property = "cq.simpleElementWhitespace", defaultValue = "EMPTY")
    SimpleElementWhitespace simpleElementWhitespace;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {

        basePath = basedir.toPath();
        charset = Charset.forName(encoding);
        localRepositoryPath = Paths.get(localRepository);
        Path pomXml = basePath.resolve("pom.xml");

        try {
            MojoDescriptor mojoDescriptor = mojoDescriptorCreator.getMojoDescriptor("help:evaluate", session, project);
            PluginParameterExpressionEvaluator evaluator = new PluginParameterExpressionEvaluator(session,
                    new MojoExecution(mojoDescriptor));

            final List<String> remoteRepos = repositories.stream()
                    .map(RemoteRepository::getUrl)
                    .map(uri -> uri.endsWith("/") ? uri.substring(0, uri.length() - 1) : uri)
                    .collect(Collectors.toList());
            new PomTransformer(pomXml, charset, simpleElementWhitespace)
                    .transform(new UpdateVersionsTransformation(new PomModelCache(localRepositoryPath, remoteRepos), evaluator,
                            getLog()));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

    }

    static class UpdateVersionsTransformation implements Transformation {

        private final PomModelCache pomModels;
        private final PluginParameterExpressionEvaluator evaluator;
        private final Log log;

        public UpdateVersionsTransformation(PomModelCache pomModels, PluginParameterExpressionEvaluator evaluator, Log log) {
            this.pomModels = pomModels;
            this.evaluator = evaluator;
            this.log = log;
        }

        @Override
        public void perform(Document document, TransformationContext context) {
            context.getContainerElement("project", "properties").ifPresent(props -> {
                for (WrappedNode<Element> prop : props.childElements()) {
                    Comment nextComment = prop.nextSiblingCommentNode();
                    if (nextComment != null) {
                        final String commentText = nextComment.getNodeValue();
                        final Matcher m = SYNC_INSTRUCTION_PATTERN.matcher(commentText);
                        if (m.matches()) {
                            final String groupId = m.group("groupId");
                            final String artifactId = m.group("artifactId");
                            final String rawVersion = m.group("version");
                            final String element = m.group("element");
                            final String method = m.group("method");
                            try {
                                final String resolvedVersion = (String) evaluator.evaluate(rawVersion, String.class);
                                log.debug("Resolved version " + rawVersion + " -> " + resolvedVersion);
                                final Model sourceModel = pomModels.get(groupId, artifactId, resolvedVersion);

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
                                            "Unexpected method " + method + "; expected property or dependency");
                                }

                                final Element propNode = prop.getNode();
                                final String key = propNode.getNodeName();
                                final String oldValue = propNode.getTextContent();
                                if (oldValue.equals(newValue)) {
                                    log.info(" - Property " + key + " up to date");
                                } else {
                                    log.info(" - Property " + key + " updated: " + oldValue + " -> " + newValue);
                                    propNode.setTextContent(newValue);
                                }
                            } catch (ExpressionEvaluationException e) {
                                throw new RuntimeException("Could not resolve " + rawVersion, e);
                            }
                        }
                    }
                }
            });
        }

        private String dependencyVersion(Model model, String element, String groupId, String artifactId,
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
    }
}
