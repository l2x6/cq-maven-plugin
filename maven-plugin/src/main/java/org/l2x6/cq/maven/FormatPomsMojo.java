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
import java.io.IOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.maven.model.Model;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.shared.model.fileset.FileSet;
import org.apache.maven.shared.model.fileset.util.FileSetManager;
import org.apache.maven.shared.utils.io.DirectoryScanner;
import org.l2x6.cq.common.CqCommonUtils;
import org.l2x6.cq.maven.PomTransformer.Transformation;
import org.l2x6.cq.maven.PomTransformer.TransformationContext;
import org.w3c.dom.Document;

/**
 * Formats the {@code pom.xml} files in the source tree.
 *
 * @since 0.1.0
 */
@Mojo(name = "format", requiresProject = true, inheritByDefault = false)
public class FormatPomsMojo extends AbstractMojo {
    public static final String VIRTUAL_DEPS_INITIAL_COMMENT = " The following dependencies guarantee that this module is built after them. You can update them by running `mvn process-resources -Pformat -N` from the source tree root directory ";
    public static final String CQ_SORT_MODULES_PATHS = "extensions/pom.xml,integration-tests/pom.xml";
    public static final String CQ_SORT_DEPENDENCY_MANAGEMENT_PATHS = "poms/bom/pom.xml,poms/bom-deployment/pom.xml";
    public static final String CQ_UPDATE_VIRTUAL_DEPENDENCIES_DIRS = "examples,integration-tests";

    /**
     * Directory where the changes should be performed. Default is the current directory of the current Java process.
     *
     * @since 0.0.1
     */
    @Parameter(property = "cq.basedir", defaultValue = "${project.basedir}")
    File basedir;

    /**
     * A list of {@code pom.xml} file paths relative to the current module's {@code baseDir} in which the
     * {@code <module>} elements should be sorted.
     *
     * @since 0.0.1
     */
    @Parameter(property = "cq.sortModulesPaths", defaultValue = CQ_SORT_MODULES_PATHS)
    List<String> sortModulesPaths;

    /**
     * A list of {@code pom.xml} file paths relative to the current module's {@code baseDir} in which the
     * {@code <dependencyManagement>} entries should be sorted.
     *
     * @since 0.0.1
     */
    @Parameter(property = "cq.sortDependencyManagementPaths", defaultValue = CQ_SORT_DEPENDENCY_MANAGEMENT_PATHS)
    List<String> sortDependencyManagementPaths;

    /**
     * A list of {@link DirectoryScanner}s selecting {@code pom.xml} files in which virtual dependencies should be
     * updated. After running this mojo, the selected {@code pom.xml} files will depend on artifacts with type
     * {@code pom} and scope {@code test} of runtime extension modules available as dependencies in the given
     * {@code pom.xml}.
     *
     * @since 0.0.1
     */
    @Parameter
    List<DirectoryScanner> updateVirtualDependencies;

    /**
     * A list of directory paths relative to the current module's {@code baseDir} containing Maven modules in which
     * virtual dependencies should be updated. After running this mojo, the selected {@code pom.xml} files will depend
     * on artifacts with type {@code pom} and scope {@code test} of all runtime extension modules available in the
     * current source tree.
     *
     * @since 0.18.0
     */
    @Parameter
    List<DirectoryScanner> updateVirtualDependenciesAllExtensions;

    /**
     * A list of directory paths relative to the current module's {@code baseDir} containing Quarkus extensions.
     *
     * @since 0.0.1
     */
    @Parameter(property = "cq.extensionDirs")
    List<ExtensionDir> extensionDirs;

    /**
     * A set of artifactIdBases that are nor extensions and should not be processed by this mojo.
     *
     * @since 0.18.0
     */
    @Parameter(property = "cq.skipArtifactIdBases")
    Set<String> skipArtifactIdBases;
    Set<String> skipArtifactIds;

    /**
     * Skip the execution of this mojo.
     *
     * @since 0.0.1
     */
    @Parameter(property = "cq.format.skip", defaultValue = "false")
    boolean skip;

    /**
     * Encoding to read and write files in the current source tree
     *
     * @since 0.18.0
     */
    @Parameter(defaultValue = CqUtils.DEFAULT_ENCODING, required = true, property = "cq.encoding")
    String encoding;
    Charset charset;

    /**
     * A FileSet to select `application.properties` files that should be removed if empty.
     *
     * @since 0.19.0
     */
    @Parameter(property = "cq.removeEmptyApplicationProperties")
    FileSet removeEmptyApplicationProperties;

    /**
     * A list of {@link PomSet}s
     *
     * <pre>
     *
     * </pre>
     *
     * @since 0.29.0
     */
    @Parameter
    List<PomSet> mergePoms;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("Skipping as requested by the user");
            return;
        }
        charset = Charset.forName(encoding);
        final Path basePath = basedir.toPath();

        if (extensionDirs == null || extensionDirs.isEmpty()) {
            extensionDirs = PomSorter.CQ_EXTENSIONS_DIRECTORIES;
        }
        skipArtifactIds = skipArtifactIdBases != null
                ? skipArtifactIdBases.stream().map(base -> "camel-quarkus-" + base).collect(Collectors.toSet())
                : Collections.emptySet();

        PomSorter.sortDependencyManagement(basePath, sortDependencyManagementPaths);
        PomSorter.sortModules(basePath, sortModulesPaths);

        if (mergePoms != null && !mergePoms.isEmpty()) {
            for (PomSet pomSet : mergePoms) {

                final FileSetManager fileSetManager = new FileSetManager();
                FileSet sourcePoms = pomSet.getSourcePoms();
                final Path dir = Paths.get(sourcePoms.getDirectory());
                final String[] includedFiles = fileSetManager.getIncludedFiles(sourcePoms);
                final Set<Gavtcs> allDeps = new TreeSet<>(Gavtcs.scopeAndTypeFirstComparator());

                final List<Transformation> transformers = new ArrayList<>();

                for (String includedFile : includedFiles) {
                    final Path pomPath = dir.resolve(includedFile);
                    Model pom = CqCommonUtils.readPom(pomPath, charset);
                    pom.getDependencies().stream()
                            .map(Gavtcs::of)
                            .filter(gavtcs -> !gavtcs.isVirtual())
                            .forEach(allDeps::add);
                }

                Iterator<Gavtcs> it = allDeps.iterator();
                while (it.hasNext()) {
                    final Gavtcs gavtcs = it.next();
                    if ("test".equals(gavtcs.getScope())
                            && "software.amazon.awssdk".equals(gavtcs.getGroupId())
                            && allDeps.stream()
                                    .anyMatch(gav -> "org.apache.camel.quarkus".equals(gav.getGroupId())
                                            && ("camel-quarkus-aws2-" + gavtcs.getArtifactId()).equals(gav.getArtifactId()))) {
                        it.remove();
                    }
                }

                transformers.add(Transformation.removeDependency(true, true, dep -> allDeps.contains(dep)));
                allDeps.stream()
                        .map(gavtcs -> Transformation.addDependencyIfNeeded(gavtcs, Gavtcs.scopeAndTypeFirstComparator()))
                        .forEach(transformers::add);

                final Path destPath = Paths.get(pomSet.getDestinationPom());
                new PomTransformer(destPath, charset).transform(transformers);

            }
        }

        final Set<Gavtcs> allExtensions = PomSorter.findExtensionArtifactIds(basePath, extensionDirs, skipArtifactIds).stream()
                .map(artifactId -> new Gavtcs("org.apache.camel.quarkus", artifactId, null))
                .collect(Collectors.toSet());
        for (DirectoryScanner scanner : updateVirtualDependencies) {
            scanner.scan();
            final Path base = scanner.getBasedir().toPath();
            for (String pomXmlRelPath : scanner.getIncludedFiles()) {
                final Path pomXmlPath = base.resolve(pomXmlRelPath);
                new PomTransformer(pomXmlPath, charset)
                        .transform(
                                Transformation.updateMappedDependencies(
                                        Gavtcs::isVirtualDeployment,
                                        Gavtcs.deploymentVitualMapper(gavtcs -> allExtensions.contains(gavtcs)),
                                        Gavtcs.scopeAndTypeFirstComparator(),
                                        VIRTUAL_DEPS_INITIAL_COMMENT),
                                Transformation.keepFirst(virtualDepsCommentXPath(), true),
                                Transformation.removeProperty(true, true, "mvnd.builder.rule"),
                                Transformation.removeContainerElementIfEmpty(true, true, true, "properties"));
            }
        }

        updateVirtualDependenciesAllExtensions(updateVirtualDependenciesAllExtensions, allExtensions, charset);

        if (removeEmptyApplicationProperties != null) {
            final FileSetManager fileSetManager = new FileSetManager();
            final Path dir = Paths.get(removeEmptyApplicationProperties.getDirectory());
            final String[] includedFiles = fileSetManager.getIncludedFiles(removeEmptyApplicationProperties);
            for (String includedFile : includedFiles) {
                final Path propsFilePath = dir.resolve(includedFile);
                if (Files.isRegularFile(propsFilePath) && CqCommonUtils.isEmptyPropertiesFile(propsFilePath)) {
                    try {
                        Files.delete(propsFilePath);
                    } catch (IOException e) {
                        throw new RuntimeException("Could not remove " + propsFilePath, e);
                    }
                }
            }
        }

    }

    public static String virtualDepsCommentXPath() {
        return "//comment()[contains(.,'" + FormatPomsMojo.VIRTUAL_DEPS_INITIAL_COMMENT + "')]";
    }

    public static void updateVirtualDependenciesAllExtensions(List<DirectoryScanner> updateVirtualDependenciesAllExtensions,
            final Set<Gavtcs> allExtensions, Charset charset) {
        if (updateVirtualDependenciesAllExtensions != null) {
            final Set<Gavtcs> allVirtualExtensions = allExtensions.stream()
                    .map(gavtcs -> gavtcs.toVirtual())
                    .collect(Collectors.toSet());
            for (DirectoryScanner scanner : updateVirtualDependenciesAllExtensions) {
                scanner.scan();
                final Path base = scanner.getBasedir().toPath();
                for (String pomXmlRelPath : scanner.getIncludedFiles()) {
                    final Path pomXmlPath = base.resolve(pomXmlRelPath);
                    new PomTransformer(pomXmlPath, charset)
                            .transform(
                                    Transformation.updateDependencySubset(
                                            gavtcs -> gavtcs.isVirtual(),
                                            allVirtualExtensions,
                                            Gavtcs.scopeAndTypeFirstComparator(),
                                            VIRTUAL_DEPS_INITIAL_COMMENT),
                                    Transformation.removeProperty(true, true, "mvnd.builder.rule"),
                                    Transformation.removeContainerElementIfEmpty(true, true, true, "properties"));
                }
            }
        }
    }

    public static class PomSet {
        private FileSet sourcePoms;
        private String destinationPom;

        public FileSet getSourcePoms() {
            return sourcePoms;
        }

        public void setSourcePoms(FileSet sourcePoms) {
            this.sourcePoms = sourcePoms;
        }

        public String getDestinationPom() {
            return destinationPom;
        }

        public void setDestinationPom(String destinationPom) {
            this.destinationPom = destinationPom;
        }
    }
}
