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
package org.l2x6.cq;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/**
 * Formats the {@code pom.xml} files in the source tree.
 *
 * @since 0.1.0
 */
@Mojo(name = "format", requiresProject = true, inheritByDefault = false)
public class FormatPomsMojo extends AbstractMojo {
    public static final String CQ_SORT_MODULES_PATHS = "extensions/pom.xml,integration-tests/pom.xml";
    public static final String CQ_SORT_DEPENDENCY_MANAGEMENT_PATHS = "poms/bom/pom.xml,poms/bom-deployment/pom.xml";
    public static final String CQ_UPDATE_MVND_RULES_DIRS = "examples,integration-tests";
    public static final String CQ_UPDATE_MVND_RULES_ALL_EXTENSIONS_DIRS = "catalog";

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
     * A list of directory paths relative to the current module's {@code baseDir} containing Maven modules in which
     * {@code <mvnd.builder.rule>} should be updated. The rule will contain deployment modules corresponding to the
     * runtime modules available as dependencies in the given module.
     *
     * @since 0.0.1
     */
    @Parameter(property = "cq.updateMvndRuleDirs", defaultValue = CQ_UPDATE_MVND_RULES_DIRS)
    List<String> updateMvndRuleDirs;

    /**
     * A list of directory paths relative to the current module's {@code baseDir} containing Maven modules in which
     * {@code <mvnd.builder.rule>} should be updated. The rule will contain all runtime modules available in the current
     * source tree.
     *
     * @since 0.14.0
     */
    @Parameter(property = "cq.updateMvndRuleAllExtensionsDirs", defaultValue = CQ_UPDATE_MVND_RULES_ALL_EXTENSIONS_DIRS)
    List<String> updateMvndRuleAllExtensionsDirs;

    /**
     * A list of directory paths relative to the current module's {@code baseDir} containing Quarkus extensions.
     *
     * @since 0.0.1
     */
    @Parameter(property = "cq.extensionDirs")
    List<ExtensionDir> extensionDirs;

    /**
     * Skip the execution of this mojo.
     *
     * @since 0.0.1
     */
    @Parameter(property = "cq.format.skip", defaultValue = "false")
    boolean skip;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("Skipping as requested by the user");
            return;
        }
        final Path basePath = basedir.toPath();

        if (extensionDirs == null || extensionDirs.isEmpty()) {
            extensionDirs = PomSorter.CQ_EXTENSIONS_DIRECTORIES;
        }

        PomSorter.sortDependencyManagement(basePath, sortDependencyManagementPaths);
        PomSorter.sortModules(basePath, sortModulesPaths);
        final Set<String> extensionArtifactIds = PomSorter.findExtensionArtifactIds(basePath, extensionDirs);
        PomSorter.updateMvndRules(basePath, updateMvndRuleDirs, extensionArtifactIds);

        if (updateMvndRuleAllExtensionsDirs != null) {
            updateMvndRuleAllExtensionsDirs.stream()
                .map(p -> basePath.resolve(p).resolve("pom.xml"))
                .forEach(pomXmlPath -> {
                    PomSorter.setMvndRule(basePath, pomXmlPath, extensionArtifactIds, true);
                });
        }
    }
}
