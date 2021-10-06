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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import org.apache.maven.model.Model;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.repository.RemoteRepository;
import org.l2x6.cq.common.CqCommonUtils;
import org.l2x6.pom.tuner.PomTransformer;
import org.l2x6.pom.tuner.PomTransformer.SimpleElementWhitespace;
import org.l2x6.pom.tuner.PomTransformer.Transformation;

/**
 * Synchronizes the properties in an example project with the properties in Camel Quarkus
 *
 * @since 0.21.0
 */
@Mojo(name = "sync-example-properties", requiresProject = true)
public class SyncExamplePropertiesMojo extends AbstractMojo {

    /**
     * Directory where the changes should be performed. Default is the current directory of the current Java process.
     *
     * @since 0.21.0
     */
    @Parameter(property = "cq.basedir", defaultValue = "${project.basedir}")
    File basedir;
    private Path basePath;

    /**
     * Encoding to read and write files in the current source tree
     *
     * @since 0.21.0
     */
    @Parameter(defaultValue = CqUtils.DEFAULT_ENCODING, required = true, property = "cq.encoding")
    String encoding;
    Charset charset;

    /**
     * The Camel Quarkus version to sync with
     *
     * @since 0.22.0
     */
    @Parameter(property = "camel-quarkus.version")
    String cqVersion;

    @Parameter(defaultValue = "${settings.localRepository}", readonly = true)
    String localRepository;

    /**
     * How to format simple XML elements ({@code <elem/>}) - with or without space before the slash.
     *
     * @since 0.38.0
     */
    @Parameter(property = "cq.simpleElementWhitespace", defaultValue = "EMPTY")
    SimpleElementWhitespace simpleElementWhitespace;

    @Parameter(defaultValue = "${project.remoteProjectRepositories}", readonly = true, required = true)
    List<RemoteRepository> repositories;

    @Component
    private RepositorySystem repoSystem;

    @Parameter(defaultValue = "${repositorySystemSession}", readonly = true, required = true)
    private RepositorySystemSession repoSession;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        basePath = basedir.toPath().toAbsolutePath().normalize();
        charset = Charset.forName(encoding);
        final Path localRepositoryPath = Paths.get(localRepository);

        final Path pomXmlPath = basePath.resolve("pom.xml");
        final Model exampleModel = CqCommonUtils.readPom(pomXmlPath, charset);
        final Properties exampleProps = exampleModel.getProperties();
        if (cqVersion == null) {
            cqVersion = exampleProps.getProperty("camel-quarkus.version");
        }

        final Path cqPomPath = CqCommonUtils.resolveArtifact(localRepositoryPath, "org.apache.camel.quarkus", "camel-quarkus",
                cqVersion,
                "pom", repositories, repoSystem, repoSession);
        final Model cqModel = CqCommonUtils.readPom(cqPomPath, charset);
        final Properties cqProps = cqModel.getProperties();
        cqProps.put("camel-quarkus.version", cqVersion);

        final Map<String, String> changeProps = new LinkedHashMap<>();
        for (Entry<Object, Object> exampleProp : exampleProps.entrySet()) {
            final String key = (String) exampleProp.getKey();
            final String cqVal = (String) cqProps.get(key);
            if (cqVal != null) {
                if (!cqVal.equals(exampleProp.getValue())) {
                    getLog().info("Updating property " + key + " " + exampleProp.getValue() + " -> " + cqVal);
                    changeProps.put(key, cqVal);
                }
            }
        }

        if (!changeProps.isEmpty()) {
            final List<Transformation> transformations = new ArrayList<PomTransformer.Transformation>(changeProps.size());
            for (Entry<String, String> prop : changeProps.entrySet()) {
                transformations.add(Transformation.addOrSetProperty(prop.getKey(), prop.getValue()));
            }
            new PomTransformer(pomXmlPath, charset, simpleElementWhitespace).transform(transformations);
        }

    }
}
