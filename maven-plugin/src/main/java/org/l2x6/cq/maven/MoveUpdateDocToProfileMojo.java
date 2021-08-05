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

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.l2x6.cq.maven.PomTransformer.ContainerElement;
import org.l2x6.cq.maven.PomTransformer.SimpleElementWhitespace;
import org.l2x6.cq.maven.PomTransformer.Transformation;
import org.l2x6.cq.maven.PomTransformer.TransformationContext;
import org.w3c.dom.Document;

/**
 * Moves {@code update-extension-doc-page} to full profile.
 */
@Mojo(name = "move-update-doc", requiresProject = true, inheritByDefault = false)
public class MoveUpdateDocToProfileMojo extends AbstractExtensionListMojo {

    /**
     * @since 0.3.0
     */
    @Parameter(defaultValue = "${project.basedir}")
    File baseDir;

    /**
     * Encoding to read and write files in the current source tree
     *
     * @since 0.10.0
     */
    @Parameter(defaultValue = CqUtils.DEFAULT_ENCODING, required = true, property = "cq.encoding")
    String encoding;

    /**
     * How to format simple XML elements ({@code <elem/>}) - with or without space before the slash.
     *
     * @since 0.38.0
     */
    @Parameter(property = "cq.simpleElementWhitespace", defaultValue = "EMPTY")
    SimpleElementWhitespace simpleElementWhitespace;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        final Charset charset = Charset.forName(encoding);
        final Transformation tf = new AddPluginUnderProfileTransformation();
        CqUtils.findExtensions(extensionDirectories.stream().map(File::toPath).sorted(),
                artifactIdBase -> !skipArtifactIdBases.contains(artifactIdBase))
                .forEach(extModule -> {
                    final Path pomPath = extModule.getRuntimePomPath();

                    new PomTransformer(pomPath, charset, simpleElementWhitespace).transform(
                            Transformation.removePlugin(true, true, "org.apache.camel.quarkus", "camel-quarkus-maven-plugin"),
                            tf);
                });

    }

    static class AddPluginUnderProfileTransformation implements Transformation {

        @Override
        public void perform(Document document, TransformationContext context) {
            final ContainerElement profile = context.getOrAddContainerElements("profiles", "profile");
            profile.addChildTextElement("id", "full");
            profile
                    .addChildContainerElement("activation")
                    .addChildContainerElement("property")
                    .addChildTextElement("name", "!quickly");
            final ContainerElement plugin = profile
                    .addChildContainerElement("build")
                    .addChildContainerElement("plugins")
                    .addChildContainerElement("plugin");

            plugin.addChildTextElement("groupId", "org.apache.camel.quarkus");
            plugin.addChildTextElement("artifactId", "camel-quarkus-maven-plugin");
            final ContainerElement execution = plugin
                    .addChildContainerElement("executions")
                    .addChildContainerElement("execution");
            execution.addChildTextElement("id", "update-extension-doc-page");
            execution.addChildContainerElement("goals").addChildTextElement("goal", "update-extension-doc-page");
            execution.addChildTextElement("phase", "process-classes");
        }

    }

}
