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
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.maven.execution.MavenSession;
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
import org.l2x6.cq.common.BannedDependencyResource;
import org.l2x6.cq.common.CqCommonUtils;
import org.l2x6.cq.common.FlattenBomTask;
import org.l2x6.cq.common.OnFailure;
import org.l2x6.pom.tuner.PomTransformer.SimpleElementWhitespace;
import org.l2x6.pom.tuner.model.Gav;
import org.l2x6.pom.tuner.model.GavPattern;
import org.l2x6.pom.tuner.model.GavSet;
import org.l2x6.pom.tuner.model.GavSet.UnionGavSet.Builder;

/**
 * Flattens the dependency management section of the current pom.xml file.
 *
 * @since 2.24.0
 */
@Mojo(name = "flatten-bom", threadSafe = true, requiresProject = true)
public class FlattenBomMojo extends AbstractMojo {

    /**
     * The Maven project.
     *
     * @since 2.24.0
     */
    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    /**
     * Directory where the changes should be performed. Default is the current directory of the current Java process.
     *
     * @since 2.24.0
     */
    @Parameter(property = "cq.basedir", defaultValue = "${project.basedir}")
    File basedir;

    /**
     * Encoding to read and write files in the current source tree
     *
     * @since 2.24.0
     */
    @Parameter(defaultValue = "${project.build.sourceEncoding}", required = true, property = "project.build.sourceEncoding")
    String encoding;
    Charset charset;

    /**
     * The root directory of the Camel Quarkus source tree.
     *
     * @since 2.24.0
     */
    @Parameter(defaultValue = "${maven.multiModuleProjectDirectory}", readonly = true)
    File multiModuleProjectDirectory;
    Path rootModuleDirectory;

    /**
     * Skip the execution of this mojo.
     *
     * @since 2.24.0
     */
    @Parameter(property = "cq.flatten-bom.skip", defaultValue = "false")
    boolean skip;

    /**
     * A list of {@link GavPattern}s to match against the GAVs of BOMs in which the given BOM entry is defined.
     * The entries satisfying this criterion will be excluded from the resulting flattened BOM.
     * <p>
     * We will typically want to exclude entries defined in {@code io.quarkus:quarkus-bom}. To do so, we would have to
     * set the following:
     *
     * <pre>
     * {@code
     * <originExcludes>
     *   <originExclude>io.quarkus:quarkus-bom</originExclude>
     * </originExcludes>
     * }
     * </pre>
     *
     * @since 2.24.0
     */
    @Parameter(property = "cq.originExcludes")
    List<String> originExcludes;

    /**
     * A list of GAV patterns to select a set of entries from the original non-flattened BOM. These initial artifacts
     * will be resolved, and their transitive dependencies will serve as a filter for keeping entries from the full
     * flattened BOM.
     *
     * @since 2.24.0
     */
    @Parameter(property = "cq.resolutionEntryPointIncludes")
    List<String> resolutionEntryPointIncludes;

    /**
     * A list of GAV patterns whose matching entries will be removed from the initial GAV set selected by
     * {@link #resolutionEntryPointIncludes}.
     *
     * @since 2.24.0
     */
    @Parameter(property = "cq.resolutionEntryPointExcludes")
    List<String> resolutionEntryPointExcludes;

    /**
     * As list of GAV patterns whose origin will be logged. Useful when searching on which BOM entry some specific
     * exclusion needs to be placed.
     *
     * @since 2.24.0
     */
    @Parameter(property = "cq.resolutionSuspects")
    List<String> resolutionSuspects;

    /**
     * Where to store the non-reduced flattened BOM. An absolute path or a path relative to <code>${basedir}</code>.
     * Useful as a base for comparisons with {@link #flattenedReducedVerbosePomFile}.
     *
     * @since 2.24.0
     */
    @Parameter(defaultValue = FlattenBomTask.DEFAULT_FLATTENED_FULL_POM_FILE, property = "cq.flattenedFullPomFile")
    File flattenedFullPomFile;

    /**
     * Where to store the reduced flattened BOM. An absolute path or a path relative to <code>${basedir}</code>.
     *
     * @since 2.24.0
     */
    @Parameter(defaultValue = FlattenBomTask.DEFAULT_FLATTENED_REDUCED_POM_FILE, property = "cq.flattenedReducedPomFile")
    File flattenedReducedPomFile;

    /**
     * Where to store the reduced flattened BOM with comments about the origin of individual entries. An absolute path
     * or a path relative to <code>${basedir}</code>.
     *
     * @since 2.24.0
     */
    @Parameter(defaultValue = FlattenBomTask.DEFAULT_FLATTENED_REDUCED_VERBOSE_POM_FILE, property = "cq.flattenedReducedVerbosePomFile")
    File flattenedReducedVerbosePomFile;

    /**
     * A list of GAV patterns to select a set of entries that must be present in the resulting flattened BOM.
     * The universe against which these patterns will be resolved is the set if {@link #resolutionEntryPointIncludes}
     * (minus {@link #resolutionEntryPointIncludes}) and all their transitive dependencies. This is handy to make sure
     * that e.g. some groups are managed completely.
     *
     * @since 3.2.0
     */
    @Parameter(property = "cq.requiredBomEntryIncludes", defaultValue = "org.apache.camel")
    List<String> requiredBomEntryIncludes;

    /**
     * A list of GAV patterns to select a set of entries that may not be present in the resulting flattened BOM.
     * See {@link #requiredBomEntryIncludes}.
     *
     * @since 3.2.0
     */
    @Parameter(property = "cq.requiredBomEntryExcludes")
    List<String> requiredBomEntryExcludes;

    /**
     * What should happen when the checks performed by this plugin fail. Possible values: {@code WARN}, {@code FAIL},
     * {@code IGNORE}.
     *
     * @since 2.24.0
     */
    @Parameter(property = "cq.onCheckFailure", defaultValue = "FAIL")
    OnFailure onCheckFailure;

    /**
     * Add exclusions to the specified artifacts coming from third party imported BOMs in the flattened BOM.
     * An example:
     *
     * <pre>
     * {@code
     * <addExclusion>
     *     <gavPattern>org.apache.kafka:connect-runtime</gavPattern>
     *     <exclusions>javax.activation:activation,javax.servlet:javax.servlet-api,log4j:log4j</exclusions>
     * </addExclusion>
     * }
     * </pre>
     *
     * @since      2.24.0
     * @deprecated use the more general {@link #bomEntryTransformations}
     */
    @Deprecated
    @Parameter
    List<FlattenBomTask.BomEntryTransformation> addExclusions;

    /**
     * Add exclusions to the specified artifacts coming from third party imported BOMs in the flattened BOM,
     * or perform some version transformations.
     *
     * An example:
     *
     * <pre>
     * {@code
     * <bomEntryTransformations>
     * <bomEntryTransformation>
     *     <gavPattern>org.apache.kafka:connect-runtime</gavPattern>
     *     <addExclusions>javax.activation:activation,javax.servlet:javax.servlet-api,log4j:log4j</addExclusions>
     * </bomEntryTransformation>
     * <bomEntryTransformation>
     *     <gavPattern>org.apache.kafka:connect-runtime</gavPattern>
     *     <versionReplacement>(1.2).3/$1.4</versionReplacement>
     * </bomEntryTransformation>
     * </bomEntryTransformations>
     * }
     * </pre>
     *
     * @since 2.28.0
     */
    @Parameter
    List<FlattenBomTask.BomEntryTransformation> bomEntryTransformations;

    /**
     * If {@code true}, assume there are no relevant changes in the source tree and just install the selected flattened
     * POM file flavor; otherwise recompute all the BOM filtering and install the potentially changed file.
     *
     * @since 2.25.0
     */
    @Parameter(defaultValue = "false", property = "quickly")
    boolean quickly;

    /**
     * Which flavor of flattened BOM should be installed, useful for testing and debugging purposes. Possible values:
     * <ul>
     * <li>{@code FULL} - see {@link #flattenedFullPomFile}
     * <li>{@code REDUCED} (default) - see {@link #flattenedReducedPomFile}
     * <li>{@code REDUCED_VERBOSE} - see {@link #flattenedReducedVerbosePomFile}
     * <li>{@code ORIGINAL} - the original non-flattened BOM gets installed; {@link #flattenedFullPomFile},
     * {@link #flattenedReducedPomFile} and {@link #flattenedReducedVerbosePomFile} are still written but none of them
     * is installed,
     *
     * @since 2.25.0
     */
    @Parameter(defaultValue = "REDUCED", property = "cq.flatten-bom.installFlavor")
    FlattenBomTask.InstallFlavor installFlavor;

    /**
     * If {@code true} performs any possible edits to fix issues found by consistency checks. The mojo will still fail
     * if any fix is performed.
     *
     * @since 2.25.0
     */
    @Parameter(property = "cq.flatten-bom.format", defaultValue = "false")
    boolean format;

    /**
     * How to format simple XML elements ({@code <elem/>}) - with or without space before the slash.
     *
     * @since 2.25.0
     */
    @Parameter(property = "cq.simpleElementWhitespace", defaultValue = "EMPTY")
    SimpleElementWhitespace simpleElementWhitespace;

    /**
     * A list of {@link BannedDependencyResource}s. Example:
     *
     * <pre>
     * {@code
     *    <bannedDependencyResources>
     *        <bannedDependencyResource>
     *            <path>classpath:enforcer-rules/quarkus-banned-dependencies.xml</path>
     *            <xPathFindExcludes>//*[local-name() = 'exclude']/text()</xPathFindExcludes>
     *        </bannedDependencyReource>
     *        <bannedDependencyResource>
     *            <path>../../pom.xml</path>
     *            <xPathFindExcludese>//*[local-name() = 'bannedDependencies']/*[local-name() = 'excludes']/*[local-name() = 'exclude']/text()</xPathFindExcludes>
     *        </bannedDependencyReource>
     *    </bannedDependencyResources>
     * }
     * </pre>
     *
     * @since 3.3.0
     */
    @Parameter
    List<BannedDependencyResource> bannedDependencyResources;

    /**
     * A list of {@code groupId:artifactid:version} tripples whose managed dependencies should be honored when resolving
     * transitives of the current BOM. Example:
     *
     * <pre>
     * {@code
     *    <additionalBoms>
     *        <additionalBom>io.quarkus:quarkus-bom:${quarkus.version}</additionalBom>
     *    </additionalBoms>
     * }
     * </pre>
     *
     * @since 3.5.0
     */
    @Parameter
    List<String> additionalBoms;

    @Component
    RepositorySystem repoSystem;

    @Parameter(defaultValue = "${repositorySystemSession}", readonly = true, required = true)
    RepositorySystemSession repoSession;

    @Parameter(defaultValue = "${project.remoteProjectRepositories}", readonly = true, required = true)
    List<RemoteRepository> repositories;

    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    MavenSession session;

    @Parameter(defaultValue = "${settings.localRepository}", readonly = true)
    String localRepository;
    Path localRepositoryPath;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("Skipping as requested by the user");
            return;
        }
        charset = Charset.forName(encoding);
        localRepositoryPath = Paths.get(localRepository);
        rootModuleDirectory = multiModuleProjectDirectory.toPath().toAbsolutePath().normalize();
        final Path fullPomPath = basedir.toPath().resolve(flattenedFullPomFile.toPath());
        final Path reducedVerbosePamPath = basedir.toPath().resolve(flattenedReducedVerbosePomFile.toPath());
        final Path reducedPomPath = basedir.toPath().resolve(flattenedReducedPomFile.toPath());
        if (bomEntryTransformations == null) {
            bomEntryTransformations = new ArrayList<>();
        }
        if (addExclusions != null) {
            bomEntryTransformations.addAll(addExclusions);
        }

        final Builder bannedDeps = GavSet.unionBuilder().defaultResult(GavSet.excludeAll());
        if (bannedDependencyResources != null) {
            bannedDependencyResources.stream()
                    .map(resource -> resource.getBannedSet(charset))
                    .forEach(bannedDeps::union);
        }

        new FlattenBomTask(
                resolutionEntryPointIncludes,
                resolutionEntryPointExcludes,
                resolutionSuspects,
                originExcludes,
                bomEntryTransformations,
                requiredBomEntryIncludes,
                requiredBomEntryExcludes,
                onCheckFailure,
                project,
                rootModuleDirectory,
                fullPomPath,
                reducedVerbosePamPath,
                reducedPomPath,
                charset,
                getLog(),
                repositories,
                repoSystem,
                repoSession,
                CqCommonUtils.getProfiles(session),
                format,
                simpleElementWhitespace,
                installFlavor,
                quickly,
                bannedDeps.build(),
                localRepositoryPath,
                additionalBoms == null ? Collections.emptyList()
                        : additionalBoms.stream().map(Gav::of).collect(Collectors.toList()))
                .execute();

    }

}
