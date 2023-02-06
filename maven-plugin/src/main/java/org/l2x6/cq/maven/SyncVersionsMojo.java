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
package org.l2x6.cq.maven;

import com.google.gson.Gson;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.lifecycle.internal.MojoDescriptorCreator;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.repository.RemoteRepository;
import org.l2x6.cq.common.CqCommonUtils;
import org.l2x6.pom.tuner.PomTransformer.SimpleElementWhitespace;

/**
 * Synchronizes version properties tagged with <code>@sync</code>.
 *
 * @since 0.36.0
 */
@Mojo(name = "sync-versions", requiresProject = true, inheritByDefault = false)
public class SyncVersionsMojo extends AbstractMojo {

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

    @Component
    private RepositorySystem repoSystem;

    @Parameter(defaultValue = "${repositorySystemSession}", readonly = true, required = true)
    private RepositorySystemSession repoSession;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {

        basePath = basedir.toPath();
        charset = Charset.forName(encoding);
        localRepositoryPath = Paths.get(localRepository);
        Path pomXml = basePath.resolve("pom.xml");

        CqCommonUtils.syncVersions(pomXml, mojoDescriptorCreator, session, project, charset, simpleElementWhitespace,
                localRepositoryPath,
                getLog(), versionTransformations(), repositories, repoSession, repoSystem);

    }

    private Map<String, String> versionTransformations() {
        final Path prodJson = basePath.resolve("product/src/main/resources/camel-quarkus-product-source.json");
        if (Files.isRegularFile(prodJson)) {
            try (Reader r = Files.newBufferedReader(prodJson, charset)) {
                @SuppressWarnings("unchecked")
                final Map<String, Object> json = new Gson().fromJson(r, Map.class);
                return (Map<String, String>) json.getOrDefault("versionTransformations", Collections.emptyMap());
            } catch (IOException e) {
                throw new RuntimeException("Could not read " + prodJson, e);
            }
        }
        return Collections.<String, String> emptyMap();
    }
}
