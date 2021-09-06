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

import com.google.gson.Gson;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.l2x6.cq.common.CqCommonUtils;
import org.l2x6.maven.utils.Ga;
import org.l2x6.maven.utils.Gavtcs;
import org.l2x6.maven.utils.MavenSourceTree;
import org.l2x6.maven.utils.MavenSourceTree.ActiveProfiles;
import org.l2x6.maven.utils.MavenSourceTree.Dependency;
import org.l2x6.maven.utils.MavenSourceTree.Module.Profile;
import org.l2x6.maven.utils.PomTransformer;
import org.l2x6.maven.utils.PomTransformer.SimpleElementWhitespace;
import org.l2x6.maven.utils.PomTransformer.Transformation;

/**
 */
@Mojo(name = "prod-excludes", threadSafe = true, requiresProject = false, inheritByDefault = false)
public class ProdExcludesMojo extends AbstractMojo {
    private static final String CAMEL_QUARKUS_PRODUCT_SOURCE_JSON_PATH = "product/src/main/resources/camel-quarkus-product-source.json";
    private static final String MODULE_COMMENT = "disabled by cq-prod-maven-plugin:prod-excludes";
    /**
     * The basedir
     *
     * @since 0.40.0
     */
    @Parameter(property = "cq.basedir", defaultValue = "${project.basedir}")
    File basedir;

    /**
     * Encoding to read and write files in the current source tree
     *
     * @since 0.40.0
     */
    @Parameter(defaultValue = "utf-8", required = true, property = "cq.encoding")
    String encoding;
    Charset charset;

    @Parameter(defaultValue = CAMEL_QUARKUS_PRODUCT_SOURCE_JSON_PATH, required = true, property = "cq.productJson")
    File productJson;

    /**
     * Skip the execution of this mojo.
     *
     * @since 0.40.0
     */
    @Parameter(property = "cq.prod-artifacts.skip", defaultValue = "false")
    boolean skip;

    /**
     * How to format simple XML elements ({@code <elem/>}) - with or without space before the slash.
     *
     * @since 1.1.0
     */
    @Parameter(property = "cq.simpleElementWhitespace", defaultValue = "EMPTY")
    SimpleElementWhitespace simpleElementWhitespace;

    /**
     * Overridden by {@link ProdExcludesCheckMojo}.
     *
     * @return {@code always false}
     */
    protected boolean isChecking() {
        return false;
    }

    /** {@inheritDoc} */
    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("Skipping as requested by the user");
            return;
        }
        charset = Charset.forName(encoding);

        final Path absProdJson = basedir.toPath().resolve(productJson.toPath());
        final Set<Ga> includes = new TreeSet<Ga>();
        final Set<Ga> requiredExtensions = new TreeSet<Ga>();
        try (Reader r = Files.newBufferedReader(absProdJson, charset)) {
            final Map<String, Object> json = new Gson().fromJson(r, Map.class);
            final Map<String, Object> extensions = (Map<String, Object>) json.get("extensions");
            for (String artifactId : extensions.keySet()) {
                final Ga extensionGa = new Ga("org.apache.camel.quarkus", artifactId);
                requiredExtensions.add(extensionGa);
                includes.add(extensionGa);
                includes.add(new Ga("org.apache.camel.quarkus", artifactId + "-deployment"));
            }
            final List<String> additionalProductizedArtifacts = (List<String>) json.get("additionalProductizedArtifacts");
            if (additionalProductizedArtifacts != null) {
                for (String artifactId : additionalProductizedArtifacts) {
                    includes.add(new Ga("org.apache.camel.quarkus", artifactId));
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Could not read " + absProdJson);
        }

        final Path catalogPomPath = basedir.toPath().resolve("catalog/pom.xml");
        if (!isChecking()) {
            /* Remove all virtual deps from the Catalog */
            new PomTransformer(catalogPomPath, charset, simpleElementWhitespace)
                    .transform(Transformation.removeDependency(
                            false,
                            true,
                            gavtcs -> gavtcs.isVirtual()));
        }

        final Path rootPomPath = basedir.toPath().resolve("pom.xml");
        MavenSourceTree tree = MavenSourceTree.of(rootPomPath, charset, Dependency::isVirtual);
        final Predicate<Profile> profiles = ActiveProfiles.of();

        /*
         * If the tree does not contain all required modules, fail or relink all commented modules depending on the
         * current operation mode
         */
        final Set<Ga> availableGas = tree.getModulesByGa().keySet();
        if (!availableGas.containsAll(includes)) {
            if (isChecking()) {
                throw new MojoFailureException("The source tree does not match the content of "
                        + CAMEL_QUARKUS_PRODUCT_SOURCE_JSON_PATH + ". Missing modules:\n - "
                        + includes.stream()
                                .filter(ga -> !availableGas.contains(ga))
                                .map(Ga::getArtifactId)
                                .collect(Collectors.joining("\n - "))
                        + "\n\nConsider running cq-prod:prod-excludes");
            } else {
                tree = tree.relinkModules(charset, simpleElementWhitespace, MODULE_COMMENT);
            }
        }

        final Set<Ga> expandedIncludes = tree.findRequiredModules(includes, profiles);

        getLog().debug("Required modules:");
        expandedIncludes.stream()
                .map(Ga::getArtifactId)
                .sorted()
                .forEach(a -> System.out.println(" - " + a));

        final Set<Ga> excludesSet = tree.complement(expandedIncludes);

        /* Write the excludesSet to .mvn/excludes.txt */
        final String newExcludesTxtContent = excludesSet.stream()
                .sorted()
                .map(ga -> (":" + ga.getArtifactId() + "\n"))
                .collect(Collectors.joining());
        final Path excludesTxt = basedir.toPath().resolve(".mvn/excludes.txt");
        if (isChecking() && !Files.isRegularFile(excludesTxt)) {
            throw new MojoFailureException("The source tree does not match the content of "
                    + CAMEL_QUARKUS_PRODUCT_SOURCE_JSON_PATH
                    + ". .mvn/excludes.txt does not exist.\n\nConsider running cq-prod:prod-excludes");
        }
        final String oldExcludesTxtContent;
        try {
            oldExcludesTxtContent = Files.isRegularFile(excludesTxt)
                    ? new String(Files.readAllBytes(excludesTxt), charset) : "";
        } catch (IOException e) {
            throw new RuntimeException("Could not read " + excludesTxt, e);
        }
        if (!oldExcludesTxtContent.equals(newExcludesTxtContent)) {
            if (isChecking()) {
                throw new MojoFailureException("The source tree does not match the content of "
                        + CAMEL_QUARKUS_PRODUCT_SOURCE_JSON_PATH
                        + ". Consider running cq-prod:prod-excludes. Expected content of .mvn/excludes.txt:\n\n"
                        + newExcludesTxtContent);
            } else {
                try {
                    Files.createDirectories(excludesTxt.getParent());
                } catch (IOException e) {
                    throw new RuntimeException("Could not create directory " + excludesTxt.getParent(), e);
                }

                try {
                    Files.write(excludesTxt, newExcludesTxtContent.getBytes(charset));
                } catch (IOException e) {
                    throw new RuntimeException("Could not write to " + excludesTxt, e);
                }
            }
        }

        tree.unlinkModules(expandedIncludes, profiles, charset, simpleElementWhitespace,
                (Set<String> unlinkModules) -> {
                    if (isChecking() && !unlinkModules.isEmpty()) {
                        throw new RuntimeException("The source tree does not match the content of "
                                + CAMEL_QUARKUS_PRODUCT_SOURCE_JSON_PATH
                                + ". Superfluous modules (the list might be incomplete):\n - "
                                + unlinkModules.stream()
                                        .collect(Collectors.joining("\n - "))
                                + ".\n\nConsider running cq-prod:prod-excludes");
                    }
                    return Transformation.commentModules(unlinkModules, MODULE_COMMENT);
                });

        if (!isChecking()) {
            /* Fix the virtual deps in the Catalog */
            final Set<Gavtcs> allVirtualExtensions = requiredExtensions.stream()
                    .map(ga -> new Gavtcs(ga.getGroupId(), ga.getArtifactId(), null))
                    .map(gavtcs -> gavtcs.toVirtual())
                    .collect(Collectors.toSet());
            CqCommonUtils.updateVirtualDependencies(charset, simpleElementWhitespace, allVirtualExtensions, catalogPomPath);
        }
    }

}
