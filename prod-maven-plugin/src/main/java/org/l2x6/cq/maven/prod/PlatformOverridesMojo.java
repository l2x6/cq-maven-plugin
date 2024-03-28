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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.l2x6.cq.maven.prod.Product.Extension;
import org.l2x6.pom.tuner.model.Ga;

/**
 * Generate {@code extensions-support-overrides.json} file from a {@code -source.json} file. The file contains metadata
 * overrides for RHBQ Platform, such as which extensions are supported in the product.
 *
 * @since 4.6.0
 */
@Mojo(name = "platform-overrides", threadSafe = true, requiresProject = false, inheritByDefault = false)
public class PlatformOverridesMojo extends AbstractMojo {

    /**
     * The basedir
     *
     * @since 4.6.0
     */
    @Parameter(property = "cq.basedir", defaultValue = "${project.basedir}")
    File basedir;

    /**
     * Encoding to read and write files in the current source tree
     *
     * @since 4.6.0
     */
    @Parameter(defaultValue = "utf-8", required = true, property = "cq.encoding")
    String encoding;
    Charset charset;

    /**
     * The version for the placeholder in the guide link template.
     *
     * @since 4.6.0
     */
    @Parameter(required = true, property = "cq.documentedProductVersion")
    String documentedProductVersion;

    /**
     * A path to the product definition file such as {@code src/main/resources/camel-quarkus-product-source.json} or
     * {@code src/main/resources/quarkus-cxf-product-source.json}
     *
     * @since 4.6.0
     */
    @Parameter(required = true, property = "cq.productJson")
    File productJson;

    /**
     * Where to write the resulting overrides file. The default is {@code src/main/generated/<project>-product.json}
     * where {@code <project>} is extracted from {@link #productJson} file name.
     *
     * @since 4.6.0
     */
    @Parameter(property = "cq.overridesFile")
    File overridesFile;

    /**
     * If {@code true} the {@code metadata/guide} will be set in the override file; otherwise the {@code guide}
     * attribute will not be present in override file.
     *
     * @since 4.6.0
     */
    @Parameter(defaultValue = "true", property = "cq.overrideGuide")
    boolean overrideGuide;

    /**
     * Skip the execution of this mojo.
     *
     * @since 4.6.0
     */
    @Parameter(property = "cq.cxf-overrides.skip", defaultValue = "false")
    boolean skip;

    /** {@inheritDoc} */
    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("Skipping as requested by the user");
            return;
        }
        charset = Charset.forName(encoding);

        final Path absProdJson = basedir.toPath().resolve(productJson.toPath());
        final Path basePath = basedir.toPath();
        final Product product = Product.read(absProdJson, charset, documentedProductVersion, basePath, basePath);

        final Path overridesPath;
        if (overridesFile == null) {
            overridesPath = autodetectOverridesFile(absProdJson);
            getLog().info("Autotetected overridesFile " + overridesPath);
        } else {
            overridesPath = overridesFile.toPath();
        }
        if (!Files.isDirectory(overridesPath.getParent())) {
            try {
                Files.createDirectories(overridesPath.getParent());
            } catch (IOException e) {
                throw new RuntimeException("Could not create " + overridesPath.getParent(), e);
            }
        }

        Map<String, Object> model = new LinkedHashMap<>();
        List<Map<String, Object>> extensions = new ArrayList<>();
        model.put("extensions", extensions);
        for (Extension ext : product.getProductExtensions().values()) {
            final String redhatSupportLevel = ext.redhatSupportLevel();
            if (redhatSupportLevel != null) {
                final Map<String, Object> extModel = new LinkedHashMap<>();

                final Ga ga = ext.getGa();
                extModel.put("group-id", ga.getGroupId());
                extModel.put("artifact-id", ga.getArtifactId());

                final Map<String, Object> md = new LinkedHashMap<>();
                md.put("redhat-support", Arrays.asList(redhatSupportLevel));

                if (overrideGuide) {
                    md.put("guide", product.getExtensionDocPageUrl(ga));
                }

                if (redhatSupportLevel.equals("supported-in-jvm")) {
                    extModel.put("unlisted", "false");
                }

                extModel.put("metadata", md);

                extensions.add(extModel);
            }
        }

        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        try {
            Files.write(overridesPath, gson.toJson(model).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new RuntimeException("Could not write to " + overridesPath, e);
        }
    }

    private Path autodetectOverridesFile(Path absProdJson) {
        return absProdJson.getParent().getParent().resolve("generated")
                .resolve(absProdJson.getFileName().toString().replace("-source", ""));
    }

}
