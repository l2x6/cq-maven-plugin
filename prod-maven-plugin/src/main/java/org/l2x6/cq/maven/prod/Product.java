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
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.apache.maven.shared.utils.io.DirectoryScanner;
import org.l2x6.cq.common.BannedDependencyResource;
import org.l2x6.pom.tuner.model.Ga;
import org.l2x6.pom.tuner.model.GavSet;
import org.l2x6.pom.tuner.model.GavSet.UnionGavSet.Builder;

/**
 * A representation of data stored in {@code product/src/main/resources/camel-quarkus-product-source.json}.
 */
public class Product {

    public static Product read(Path absProdJson, Charset charset, String version, Path docReferenceDir,
            Path multiModuleProjectDirectory) {
        final String[] parts = version.split("\\.");
        final String majorMinorVersion = parts[0] + "." + parts[1];

        try (Reader r = Files.newBufferedReader(absProdJson, charset)) {
            @SuppressWarnings("unchecked")
            final Map<String, Object> json = new Gson().fromJson(r, Map.class);
            final String groupId = (String) json.getOrDefault("groupId", "org.apache.camel.quarkus");
            final String prodGuideUrlTemplate = (String) json.get("guideUrlTemplate");
            @SuppressWarnings("unchecked")
            final Map<String, Object> extensions = (Map<String, Object>) json.get("extensions");
            final Map<Ga, Product.Extension> extensionsMap = new TreeMap<>();
            final Set<Ga> excludeTests = new TreeSet<Ga>();
            final Map<Ga, Set<Ga>> allowedMixedTests = new TreeMap<>();
            for (Entry<String, Object> en : extensions.entrySet()) {

                final String artifactId = en.getKey();
                final Ga extensionGa = new Ga(groupId, artifactId);
                Map<String, Object> extensionEntry = (Map<String, Object>) en.getValue();
                extensionsMap.put(extensionGa,
                        new Extension(extensionGa, ModeSupportStatus.valueOf((String) extensionEntry.get("jvm")),
                                ModeSupportStatus.valueOf((String) extensionEntry.get("native"))));

                @SuppressWarnings("unchecked")
                final List<String> allowedMixedTestsList = (List<String>) extensionEntry.get("allowedMixedTests");
                if (allowedMixedTestsList != null) {
                    final Set<Ga> moduleAllowedMixedTests = allowedMixedTestsList.stream()
                            .map(a -> new Ga(groupId, a))
                            .collect(Collectors.toCollection(TreeSet::new));
                    allowedMixedTests.put(extensionGa, moduleAllowedMixedTests);
                }
            }
            @SuppressWarnings("unchecked")
            final List<String> additionalProductizedArtifacts = (List<String>) json
                    .getOrDefault("additionalProductizedArtifacts", Collections.emptyList());
            @SuppressWarnings("unchecked")
            final List<String> excludeTestsList = (List<String>) json.get("excludeTests");
            if (excludeTestsList != null) {
                for (String artifactId : excludeTestsList) {
                    excludeTests.add(new Ga(groupId, artifactId));
                }
            }

            @SuppressWarnings("unchecked")
            final Map<String, String> versionTransformations = (Map<String, String>) json.getOrDefault(
                    "versionTransformations",
                    Collections.emptyMap());
            final List<DirectoryScanner> integrationTests = new ArrayList<>();
            final List<Map<String, Object>> itEntries = (List<Map<String, Object>>) json
                    .getOrDefault("integrationTests", Collections.emptyList());
            for (Map<String, Object> itEntry : itEntries) {
                final DirectoryScanner ds = new DirectoryScanner();
                ds.setBasedir(multiModuleProjectDirectory.resolve((String) itEntry.getOrDefault("basedir", ".")).normalize()
                        .toFile());
                ds.setIncludes(
                        ((List<String>) itEntry.getOrDefault("includes", Collections.emptyList())).toArray(new String[0]));
                ds.setExcludes(
                        ((List<String>) itEntry.getOrDefault("excludes", Collections.emptyList())).toArray(new String[0]));
                integrationTests.add(ds);
            }

            final Path requiredProductizedCamelArtifacts = multiModuleProjectDirectory.resolve((String) json
                    .getOrDefault("requiredProductizedCamelArtifacts",
                            ProdExcludesMojo.DEFAULT_REQUIRED_PRODUCTIZED_CAMEL_ARTIFACTS_TXT));
            final Path productizedCamelQuarkusArtifacts = multiModuleProjectDirectory.resolve((String) json
                    .getOrDefault("productizedCamelQuarkusArtifacts",
                            ProdExcludesMojo.DEFAULT_PRODUCTIZED_CAMEL_QUARKUS_ARTIFACTS_TXT));
            final int availableCiNodes = (Integer) json
                    .getOrDefault("availableCiNodes", Integer.valueOf(ProdExcludesMojo.DEFAULT_AVAILABLE_CI_NODES));
            final Path jenkinsfile = multiModuleProjectDirectory.resolve((String) json
                    .getOrDefault("jenkinsfile", ProdExcludesMojo.DEFAULT_JENKINSFILE));
            final Path jenkinsfileStageTemplate = multiModuleProjectDirectory.resolve((String) json
                    .getOrDefault("jenkinsfileStageTemplate", ProdExcludesMojo.DEFAULT_JENKINSFILE_STAGE_TEMPLATE));
            final Path productizedDependenciesFile = multiModuleProjectDirectory.resolve((String) json
                    .getOrDefault("productizedDependenciesFile", ProdExcludesMojo.DEFAULT_PRODUCTIZED_DEPENDENCIES_FILE));
            final Path allDependenciesFile = multiModuleProjectDirectory.resolve((String) json
                    .getOrDefault("allDependenciesFile", ProdExcludesMojo.DEFAULT_ALL_DEPENDENCIES_FILE));
            final Path nonProductizedDependenciesFile = multiModuleProjectDirectory.resolve((String) json
                    .getOrDefault("nonProductizedDependenciesFile",
                            ProdExcludesMojo.DEFAULT_NON_PRODUCTIZED_DEPENDENCIES_FILE));
            @SuppressWarnings("unchecked")
            final Map<String, String> additionalExtensionDependencies = (Map<String, String>) json
                    .getOrDefault("additionalExtensionDependencies", Collections.emptyMap());

            final TreeMap<String, GavSet> additionalDependenciesMap = new TreeMap<>();
            if (additionalExtensionDependencies != null) {
                for (Entry<String, String> en : additionalExtensionDependencies.entrySet()) {
                    additionalDependenciesMap.put(en.getKey(), GavSet.builder().includes(en.getValue()).build());
                }
            }

            final Builder bannedDeps = GavSet.unionBuilder().defaultResult(GavSet.excludeAll());
            @SuppressWarnings("unchecked")
            final List<Map<String, Object>> rawBannedDependencyResources = (List<Map<String, Object>>) json
                    .getOrDefault("bannedDependencyResources", Collections.emptyList());
            for (Map<String, Object> resource : rawBannedDependencyResources) {
                @SuppressWarnings("unchecked")
                BannedDependencyResource bannedDependencyResource = new BannedDependencyResource(
                        (String) resource.get("location"),
                        (String) resource.get("xsltLocation"));
                bannedDeps.union(bannedDependencyResource.getBannedSet(charset));
            }

            final Map<Ga, Ga> transitiveDependencyReplacements = new LinkedHashMap<>();
            ((Map<String, String>) json.getOrDefault("transitiveDependencyReplacements", Collections.emptyMap()))
                    .entrySet()
                    .forEach(en -> transitiveDependencyReplacements.put(Ga.of(en.getKey()), Ga.of(en.getValue())));

            final org.l2x6.pom.tuner.model.GavSet.IncludeExcludeGavSet.Builder ignoredTransitiveDependencies = GavSet
                    .builder();
            ((List<String>) json.getOrDefault("ignoredTransitiveDependencies",
                    Collections.emptyList())).forEach(ignoredTransitiveDependencies::include);

            return new Product(
                    Collections.unmodifiableMap(extensionsMap),
                    groupId,
                    prodGuideUrlTemplate,
                    majorMinorVersion,
                    docReferenceDir,
                    Collections.unmodifiableMap(versionTransformations),
                    Collections.unmodifiableList(additionalProductizedArtifacts),
                    Collections.unmodifiableSet(excludeTests),
                    allowedMixedTests,
                    Collections.unmodifiableList(integrationTests),
                    requiredProductizedCamelArtifacts,
                    productizedCamelQuarkusArtifacts,
                    availableCiNodes,
                    jenkinsfile,
                    jenkinsfileStageTemplate,
                    productizedDependenciesFile,
                    allDependenciesFile,
                    nonProductizedDependenciesFile,
                    Collections.unmodifiableMap(additionalDependenciesMap),
                    bannedDeps.build(),
                    Collections.unmodifiableMap(transitiveDependencyReplacements),
                    ignoredTransitiveDependencies.build());
        } catch (IOException e) {
            throw new RuntimeException("Could not read " + absProdJson, e);
        }
    }

    private final Map<Ga, Product.Extension> extensions;
    private final String groupId;
    private final String prodGuideUrlTemplate;
    private final String productMajorMinorVersion;
    private final Path docReferenceDir;
    private final Map<String, String> versionTransformations;
    private final List<String> additionalProductizedArtifacts;
    private final Set<Ga> excludeTests;
    private final Map<Ga, Set<Ga>> allowedMixedTests;
    private final List<DirectoryScanner> integrationTests;
    private final Path requiredProductizedCamelArtifacts;
    private final Path productizedCamelQuarkusArtifacts;
    private final int availableCiNodes;
    private final Path jenkinsfile;
    private final Path jenkinsfileStageTemplate;
    private final Path productizedDependenciesFile;
    private final Path allDependenciesFile;
    private final Path nonProductizedDependenciesFile;
    private final Map<String, GavSet> additionalExtensionDependencies;
    private final GavSet bannedDependencies;
    private final Map<Ga, Ga> transitiveDependencyReplacements;
    private final GavSet ignoredTransitiveDependencies;

    public Product(
            Map<Ga, Product.Extension> extensions,
            String groupId,
            String prodGuideUrlTemplate,
            String majorVersion,
            Path docReferenceDir,
            Map<String, String> versionTransformations, List<String> additionalProductizedArtifacts, Set<Ga> excludeTests,
            Map<Ga, Set<Ga>> allowedMixedTests, List<DirectoryScanner> integrationTests,
            Path requiredProductizedCamelArtifacts,
            Path productizedCamelQuarkusArtifacts,
            int availableCiNodes,
            Path jenkinsfile,
            Path jenkinsfileStageTemplate,
            Path productizedDependenciesFile,
            Path allDependenciesFile,
            Path nonProductizedDependenciesFile,
            Map<String, GavSet> additionalExtensionDependencies,
            GavSet bannedDependencies,
            Map<Ga, Ga> transitiveDependencyReplacements,
            GavSet ignoredTransitiveDependencies) {
        this.extensions = extensions;
        this.groupId = groupId;
        this.prodGuideUrlTemplate = prodGuideUrlTemplate;
        this.productMajorMinorVersion = majorVersion;
        this.docReferenceDir = docReferenceDir;
        this.versionTransformations = versionTransformations;
        this.additionalProductizedArtifacts = additionalProductizedArtifacts;
        this.excludeTests = excludeTests;
        this.allowedMixedTests = allowedMixedTests;
        this.integrationTests = integrationTests;
        this.requiredProductizedCamelArtifacts = requiredProductizedCamelArtifacts;
        this.productizedCamelQuarkusArtifacts = productizedCamelQuarkusArtifacts;
        this.availableCiNodes = availableCiNodes;
        this.jenkinsfile = jenkinsfile;
        this.jenkinsfileStageTemplate = jenkinsfileStageTemplate;
        this.productizedDependenciesFile = productizedDependenciesFile;
        this.allDependenciesFile = allDependenciesFile;
        this.nonProductizedDependenciesFile = nonProductizedDependenciesFile;
        this.additionalExtensionDependencies = additionalExtensionDependencies;
        this.bannedDependencies = bannedDependencies;
        this.transitiveDependencyReplacements = transitiveDependencyReplacements;
        this.ignoredTransitiveDependencies = ignoredTransitiveDependencies;
    }

    /**
     * @return a {@link Map} of {@link Extension}s defining a product
     */
    public Map<Ga, Product.Extension> getProductExtensions() {
        return extensions;
    }

    /**
     * @return a {@link SortedSet} of {@link Ga}s representing modules required by the product. This is the
     *         "initial" set - i.e. any possible transitive dependencies are yet to be resolved.
     */
    public static SortedSet<Ga> getInitialProductizedModules(Product... products) {
        SortedSet<Ga> result = new TreeSet<>();
        for (Product product : products) {
            if (product != null) {
                for (Ga ga : product.extensions.keySet()) {
                    result.add(ga);
                    result.add(new Ga(product.groupId, ga.getArtifactId() + "-deployment"));
                }
                if (product.additionalProductizedArtifacts != null) {
                    for (String maybeGa : product.additionalProductizedArtifacts) {
                        /* groupId is optional so that we stay backwards compatible */
                        final int colonPos = maybeGa.indexOf(':');
                        if (colonPos >= 0) {
                            result.add(new Ga(maybeGa.substring(0, colonPos), maybeGa.substring(colonPos + 1)));
                        } else {
                            result.add(new Ga(product.groupId, maybeGa));
                        }
                    }
                }
            }
        }
        return Collections.unmodifiableSortedSet(result);
    }

    /**
     * @param  ga the {@link Ga} to look the doc page URL for
     * @return    an URL to a doc page of the given extension, either a product doc page or a community page or the
     *            default
     *            {@value ProdExcludesMojo#defaultCommunityGuide}
     */
    public String getExtensionDocPageUrl(Ga ga) {
        final Product.Extension ext = extensions.get(ga);

        if (ext != null && ext.hasProductDocumentationPage()) {
            return ProdExcludesMojo.guideUrl(productMajorMinorVersion, ga, prodGuideUrlTemplate);
        }
        final String artifactIdBase = ga.getArtifactId().replace("camel-quarkus-", "");
        if (Files.isRegularFile(docReferenceDir.resolve(artifactIdBase + ".adoc"))) {
            return ProdExcludesMojo.guideUrl(productMajorMinorVersion, ga, ProdExcludesMojo.communityGuideUrlTemplate);
        } else {
            return ProdExcludesMojo.defaultCommunityGuide;
        }
    }

    /**
     * @return see {@code product/README.adoc} of the current product branch
     */
    public Set<Ga> getExcludeTests() {
        return excludeTests;
    }

    /**
     * @return see {@code product/README.adoc} of the current product branch
     */
    public Map<Ga, Set<Ga>> getAllowedMixedTests() {
        return allowedMixedTests;
    }

    /**
     * @return see {@code product/README.adoc} of the current product branch
     */
    public Map<Ga, Ga> getTransitiveDependencyReplacements() {
        return transitiveDependencyReplacements;
    }

    /**
     * @return see {@code product/README.adoc} of the current product branch
     */
    public GavSet getIgnoredTransitiveDependencies() {
        return ignoredTransitiveDependencies;
    }

    public List<DirectoryScanner> getIntegrationTests() {
        return integrationTests;
    }

    public Path getRequiredProductizedCamelArtifacts() {
        return requiredProductizedCamelArtifacts;
    }

    public Path getProductizedCamelQuarkusArtifacts() {
        return productizedCamelQuarkusArtifacts;
    }

    public int getAvailableCiNodes() {
        return availableCiNodes;
    }

    public Path getJenkinsfile() {
        return jenkinsfile;
    }

    public Path getJenkinsfileStageTemplate() {
        return jenkinsfileStageTemplate;
    }

    public Path getProductizedDependenciesFile() {
        return productizedDependenciesFile;
    }

    public Path getAllDependenciesFile() {
        return allDependenciesFile;
    }

    public Path getNonProductizedDependenciesFile() {
        return nonProductizedDependenciesFile;
    }

    public Map<String, GavSet> getAdditionalExtensionDependencies() {
        return additionalExtensionDependencies;
    }

    public GavSet getBannedDependencies() {
        return bannedDependencies;
    }

    public Map<String, String> getVersionTransformations() {
        return versionTransformations;
    }

    /**
     * A representation of a product extension as defined in {@code camel-quarkus-product-source.json}
     */
    public static class Extension {
        private final Ga ga;
        private final ModeSupportStatus jvmSupportStatus;
        private final ModeSupportStatus nativeSupportStatus;

        public Extension(Ga ga, ModeSupportStatus jvmSupportStatus, ModeSupportStatus nativeSupportStatus) {
            this.ga = ga;
            this.jvmSupportStatus = jvmSupportStatus;
            this.nativeSupportStatus = nativeSupportStatus;
        }

        public boolean hasProductDocumentationPage() {
            return jvmSupportStatus.hasProductDocumentationPage() || nativeSupportStatus.hasProductDocumentationPage();
        }

        public Ga getGa() {
            return ga;
        }

        /**
         * @return support level string usable Quarkus Platform metadata, such as
         *         <code>"redhat-support" : [ "tech-preview" ]</code>
         */
        public String redhatSupportLevel() {
            if (nativeSupportStatus == jvmSupportStatus) {
                return nativeSupportStatus.quarkusSupportLevel;
            }
            if (jvmSupportStatus == ModeSupportStatus.supported && nativeSupportStatus == ModeSupportStatus.techPreview) {
                return "supported-in-jvm";
            }
            throw new IllegalStateException(
                    "Cannot merge native support level " + nativeSupportStatus + " with JVM supportlevel " + jvmSupportStatus);
        }

    }

    public enum ModeSupportStatus {
        community(null),
        devSupport("dev-support"),
        techPreview("tech-preview"),
        supported("supported");

        private ModeSupportStatus(String quarkusSupportLevel) {
            this.quarkusSupportLevel = quarkusSupportLevel;
        }

        private final String quarkusSupportLevel;

        public boolean hasProductDocumentationPage() {
            switch (this) {
            case devSupport:
            case techPreview:
            case supported:
                return true;
            case community:
                return false;
            default:
                throw new IllegalStateException("Unexpected " + ModeSupportStatus.class.getSimpleName() + "." + name());
            }
        }

    }

}
