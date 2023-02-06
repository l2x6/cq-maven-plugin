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

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.function.Predicate;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.l2x6.pom.tuner.MavenSourceTree;
import org.l2x6.pom.tuner.MavenSourceTree.ActiveProfiles;
import org.l2x6.pom.tuner.PomTransformer.SimpleElementWhitespace;
import org.l2x6.pom.tuner.model.Profile;

/**
 * A faster and more advanced alternative to {@code versions:set}.
 *
 * @since 2.5.0
 */
@Mojo(name = "set-versions", requiresProject = true, inheritByDefault = false)
public class SetVersionsMojo extends AbstractMojo {
    /**
     * Directory where the changes should be performed. Default is the current directory of the current Java process.
     *
     * @since 2.5.0
     */
    @Parameter(property = "cq.basedir", defaultValue = "${project.basedir}")
    File basedir;
    protected Path basePath;

    /**
     * The version to set
     *
     * @since 2.5.0
     */
    @Parameter(property = "cq.newVersion", required = true)
    String newVersion;

    /**
     * Encoding to read and write files in the current source tree
     *
     * @since 2.5.0
     */
    @Parameter(defaultValue = CqUtils.DEFAULT_ENCODING, required = true, property = "cq.encoding")
    String encoding;
    Charset charset;

    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    protected MavenSession session;

    /**
     * How to format simple XML elements ({@code <elem/>}) - with or without space before the slash.
     *
     * @since 2.5.0
     */
    @Parameter(property = "cq.simpleElementWhitespace", defaultValue = "EMPTY")
    SimpleElementWhitespace simpleElementWhitespace;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        basePath = basedir.toPath();
        charset = Charset.forName(encoding);

        final Predicate<Profile> profiles = getProfiles();
        MavenSourceTree.of(basePath.resolve("pom.xml"), charset).setVersions(newVersion, profiles, simpleElementWhitespace);
    }

    Predicate<Profile> getProfiles() {
        final Predicate<Profile> profiles = ActiveProfiles.of(
                session.getCurrentProject().getActiveProfiles().stream()
                        .map(org.apache.maven.model.Profile::getId)
                        .toArray(String[]::new));
        return profiles;
    }
}
