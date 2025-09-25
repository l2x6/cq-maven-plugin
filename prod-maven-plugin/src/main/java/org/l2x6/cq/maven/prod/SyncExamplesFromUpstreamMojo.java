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

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.commons.io.FileUtils;
import org.apache.maven.RepositoryUtils;
import org.apache.maven.model.Model;
import org.apache.maven.model.Profile;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.DependencyFilter;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactDescriptorException;
import org.eclipse.aether.resolution.ArtifactDescriptorRequest;
import org.eclipse.aether.resolution.ArtifactDescriptorResult;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.resolution.DependencyResult;
import org.eclipse.aether.util.artifact.JavaScopes;
import org.eclipse.aether.util.filter.DependencyFilterUtils;
import org.l2x6.cq.common.CqCommonUtils;
import org.l2x6.pom.tuner.MavenSourceTree;
import org.l2x6.pom.tuner.PomTransformer;
import org.l2x6.pom.tuner.PomTransformer.ContainerElement;
import org.l2x6.pom.tuner.PomTransformer.TransformationContext;
import org.w3c.dom.Document;

/**
 * Sync Camel Quarkus Example projects from an upstream GitHub branch to a local destination directory.
 *
 * @since 4.11.0
 */
@Mojo(name = "sync-examples-from-upstream", threadSafe = true, requiresProject = false)
public class SyncExamplesFromUpstreamMojo extends AbstractMojo {
    private static final String EXPRESSION_REGEX = "\\$\\{([^}]+)}";
    private static final Pattern EXPRESSION = Pattern.compile(EXPRESSION_REGEX);
    private static final String GITHUB_BASE_URL = "https://github.com/apache/camel-quarkus-examples/archive/refs/heads/%s.zip";
    private static final String MRRC_GA_URL = "https://maven.repository.redhat.com/ga/";
    private static final String MRRC_EARLYACCESS_URL = "https://maven.repository.redhat.com/earlyaccess/all/";
    private static final Set<String> ALLOWED_UNPRODUCTIZED_DEPENDENCIES = Set.of(
            "com.ibm.mq:com.ibm.mq.jakarta.client",
            "io.quarkus:quarkus-jdbc-h2",
            "io.quarkus:quarkus-flyway",
            "io.strimzi:kafka-oauth-client",
            "org.flywaydb:flyway-mysql");

    @Component
    RepositorySystem repositorySystem;

    @Parameter(defaultValue = "${project.remoteProjectRepositories}", readonly = true, required = true)
    List<RemoteRepository> repositories;

    @Parameter(defaultValue = "${repositorySystemSession}", readonly = true, required = true)
    private RepositorySystemSession session;

    /**
     * The groupId of the Quarkus Platform Quarkus BOM
     *
     * @since 4.11.0
     */
    @Parameter(property = "cq.quarkus.platform.group-id", defaultValue = "com.redhat.quarkus.platform")
    String quarkusPlatformGroupId;

    /**
     * The artifactId of the Quarkus Platform Quarkus BOM
     *
     * @since 4.11.0
     */
    @Parameter(property = "cq.quarkus.platform.artifact-id", defaultValue = "quarkus-bom")
    String quarkusPlatformArtifactId;

    /**
     * The version of the Quarkus Platform Quarkus BOM
     *
     * @since 4.11.0
     */
    @Parameter(property = "cq.quarkus.platform.version", required = true)
    String quarkusPlatformVersion;

    /**
     * The Maven groupId of the Quarkus Platform Camel Quarkus BOM
     *
     * @since 4.11.0
     */
    @Parameter(property = "cq.camel-quarkus.platform.group-id")
    String camelQuarkusPlatformGroupId = "${quarkus.platform.group-id}";

    /**
     * The Maven artifactId of the Quarkus Platform Camel Quarkus BOM
     *
     * @since 4.11.0
     */
    @Parameter(property = "cq.camel-quarkus.platform.artifact-id", defaultValue = "quarkus-camel-bom")
    String camelQuarkusPlatformArtifactId;

    /**
     * The Maven version of the Quarkus Platform Camel Quarkus BOM
     *
     * @since 4.11.0
     */
    @Parameter(property = "cq.camel-quarkus.platform.version")
    String camelQuarkusPlatformVersion = "${quarkus.platform.version}";

    /**
     * Whether to ignore sync criteria (E.g. productized dependencies) and force syncing of projects
     *
     * @since 4.11.0
     */
    @Parameter(property = "cq.force", defaultValue = "false")
    boolean isForce;

    /**
     * Whether to only perform dependency analysis on projects and not sync them
     *
     * @since 4.11.0
     */
    @Parameter(property = "cq.analyzeOnly", defaultValue = "false")
    boolean isAnalyzeOnly;

    /**
     * Whether to use a cached download of the GitHub target branch zip archive
     *
     * @since 4.11.0
     */
    @Parameter(property = "cq.useCache", defaultValue = "true")
    boolean isUseCache;

    /**
     * The path to the directory where projects should be synced from. When set, this overrides the default behavior where
     * the sources to sync from are downloaded from GitHub
     *
     * @since 4.11.0
     */
    @Parameter(property = "cq.syncFromDir")
    File syncFromDir;

    /**
     * The path to the directory where projects should be synced to
     *
     * @since 4.11.0
     */
    @Parameter(property = "cq.syncToDir", defaultValue = "${user.dir}")
    File syncToDir;

    /**
     * The path to the temporary directory where this mojo performs its work
     *
     * @since 4.11.0
     */
    @Parameter(property = "cq.tmpDir", defaultValue = "${java.io.tmpdir}")
    File tmpDir;

    /**
     * Comma separated list of directory names for example projects to include. When not specified, all discovered projects
     * are included
     *
     * @since 4.11.0
     */
    @Parameter(property = "cq.projectIncludes")
    Set<String> projectIncludes = new HashSet<>();

    /**
     * Comma separated list of directory names for example projects to exclude
     *
     * @since 4.11.0
     */
    @Parameter(property = "cq.projectExcludes")
    Set<String> projectExcludes = new HashSet<>();

    /**
     * Comma separated list of groupId:artifactId keys for project dependencies that should be ignored when considering
     * whether they are productized or not
     *
     * @since 4.11.0
     */
    @Parameter(property = "cq.ignoredDependencies")
    Set<String> ignoredDependencies;

    List<SourceTransformer> preSyncTransformers = new ArrayList<>();
    List<SourceTransformer> postSyncTransformers = new ArrayList<>();

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        // Configure a common set of dependencies to always be ignored
        ignoredDependencies.addAll(ALLOWED_UNPRODUCTIZED_DEPENDENCIES);

        // Configure source transformations
        setUpSourceTransformations();

        try {
            if (syncFromDir == null) {
                // Download the community example projects as a GitHub source archive for the target branch
                downloadGitHubBranchArchive();
                // Unzip archive contents
                extractZipFile();
            }

            // Analyze example project dependencies and sync content
            Map<String, Set<GAV>> dependencies = analyzeDependencies();
            syncProjects(dependencies);
        } catch (Exception e) {
            throw new MojoExecutionException(e);
        }
    }

    void downloadGitHubBranchArchive() throws MojoExecutionException {
        URL downloadUrl = getGitHubBranchArchiveURL();
        Path downloadDestination = getArchivePath();
        if (isUseCache && Files.exists(downloadDestination)) {
            getLog().info("Using cached download of %s".formatted(downloadUrl));
            return;
        }

        getLog().info("Downloading %s to %s".formatted(downloadUrl, downloadDestination));
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) downloadUrl.openConnection();
            connection.setRequestMethod("GET");
            connection.connect();

            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new MojoExecutionException("Failed to download upstream branch sources from: " + downloadUrl);
            }

            try (InputStream inputStream = new BufferedInputStream(connection.getInputStream());
                    FileOutputStream fileOutputStream = new FileOutputStream(downloadDestination.toFile())) {

                byte[] buffer = new byte[1024];
                int bytesRead;
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    fileOutputStream.write(buffer, 0, bytesRead);
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    void extractZipFile() throws Exception {
        Path zipFile = getArchivePath();
        Path parent = zipFile.getParent();
        Path extractedDir = parent.resolve(zipFile.getFileName().toString().replace(".zip", ""));

        if (Files.exists(extractedDir)) {
            FileUtils.deleteDirectory(extractedDir.toFile());
        }

        getLog().info("Extracting " + zipFile);
        try (ZipInputStream zipInputStream = new ZipInputStream(new BufferedInputStream(Files.newInputStream(zipFile)))) {
            ZipEntry entry;
            while ((entry = zipInputStream.getNextEntry()) != null) {
                String fileName = entry.getName();
                Path filePath = parent.resolve(fileName);
                if (entry.isDirectory()) {
                    Files.createDirectories(filePath);
                } else {
                    Files.createDirectories(filePath.getParent());
                    byte[] buffer = new byte[1024];
                    int bytesRead;
                    try (FileOutputStream fos = new FileOutputStream(filePath.toFile())) {
                        while ((bytesRead = zipInputStream.read(buffer)) != -1) {
                            fos.write(buffer, 0, bytesRead);
                        }
                    }
                }
                zipInputStream.closeEntry();
            }
        }
    }

    Map<String, Set<GAV>> analyzeDependencies() throws Exception {
        Path downloadDestination = getExtractedArchivePath();
        Map<String, Set<GAV>> projectDependencies = new TreeMap<>();

        getLog().info("⚙️ Analyzing example projects. This may take some time if dependencies are not yet cached...");
        Files.walkFileTree(downloadDestination, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                // Perform any custom pre-processing on specific files
                preSyncTransformers.stream()
                        .filter(sourceTransformer -> sourceTransformer.canApply(file))
                        .forEach(sourceTransformer -> sourceTransformer.apply(file));

                if (attrs.isRegularFile() && file.getFileName().toString().equals("pom.xml")) {
                    Path exampleProjectDirectory = file.getParent();
                    String exampleProjectName = exampleProjectDirectory.getFileName().toString();

                    if ((!projectExcludes.contains(exampleProjectName))
                            && (projectIncludes.isEmpty() || projectIncludes.contains(exampleProjectName))) {
                        try {
                            getLog().info("✨ Analyzing example project " + exampleProjectName);
                            Set<GAV> dependencies = getDependencies(file);

                            // Handle multi-module projects
                            Path parentPomXml = exampleProjectDirectory.getParent().resolve("pom.xml");
                            if (Files.exists(parentPomXml)) {
                                exampleProjectName = parentPomXml.getParent().getFileName() + "/" + exampleProjectName;
                            }

                            projectDependencies.put(exampleProjectName, dependencies);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    } else {
                        getLog().info("❌ Skipping excluded project %s".formatted(exampleProjectName));
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });

        return projectDependencies;
    }

    void syncProjects(Map<String, Set<GAV>> projectDependencies) throws Exception {
        if (isAnalyzeOnly) {
            getLog().info("⚠️ The analyzeOnly option is enabled. Syncing projects will be disabled");
        }

        Path downloadDestination = getExtractedArchivePath();
        for (Map.Entry<String, Set<GAV>> entry : projectDependencies.entrySet()) {
            Set<GAV> unproductizedDeps = entry.getValue()
                    .stream()
                    .filter(GAV::isUnProductized)
                    .collect(Collectors.toUnmodifiableSet());
            if (unproductizedDeps.isEmpty() || isForce) {
                String message = "all runtime dependencies are productized";
                if (isForce) {
                    message = "the force option is true";
                }

                getLog().info("✅ Syncing project %s as %s".formatted(entry.getKey(), message));
                entry.getValue().forEach(gav -> getLog().info("    " + gav.toString()));

                if (isAnalyzeOnly) {
                    continue;
                }

                Path projectToSync = downloadDestination.resolve(entry.getKey());
                Files.walk(projectToSync).forEach(source -> {
                    try {
                        Path destination = syncToDir.toPath().resolve(downloadDestination.relativize(source));
                        if (Files.isDirectory(destination) && Files.exists(destination)) {
                            return;
                        }

                        if (!FileUtils.contentEquals(source.toFile(), destination.toFile()) || isForce) {
                            getLog().debug("Syncing file %s to %s".formatted(source, destination));
                            Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
                        } else {
                            getLog().debug("Not syncing file %s to %s. Content already matches".formatted(source, destination));
                        }

                        // Perform any custom post-processing on specific files
                        postSyncTransformers.stream()
                                .filter(sourceTransformer -> sourceTransformer.canApply(destination))
                                .forEach(sourceTransformer -> sourceTransformer.apply(destination));
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
            } else {
                getLog().info("❌ Skipping sync of project %s as not all runtime dependencies are productized"
                        .formatted(entry.getKey()));
                unproductizedDeps.forEach(gav -> getLog().info("    " + gav.toString()));
            }
        }
    }

    Set<GAV> getDependencies(Path pomXml) throws Exception {
        Model model = CqCommonUtils.readPom(pomXml, StandardCharsets.UTF_8);

        if (model.getGroupId() != null && !model.getGroupId().equals("org.apache.camel.quarkus.examples")) {
            getLog().warn("Skipping sync of %s it does not appear to be a camel-quarkus example project"
                    .formatted(pomXml.getParent().getFileName()));
        }

        if (model.getDependencies() == null || model.getDependencies().isEmpty()) {
            return Collections.emptySet();
        }

        Properties properties = new Properties();
        properties.putAll(model.getProperties());

        List<org.apache.maven.model.Dependency> managedDependencies = new ArrayList<>();
        List<org.apache.maven.model.Dependency> dependencies = new ArrayList<>();

        model.getDependencyManagement()
                .getDependencies()
                .stream()
                .filter(dependency -> dependency.getScope() == null || !dependency.getScope().equals("test"))
                .forEach(managedDependencies::add);

        model.getDependencies()
                .stream()
                .filter(dependency -> dependency.getScope() == null || !dependency.getScope().equals("test"))
                .forEach(dependencies::add);

        for (Profile profile : model.getProfiles()) {
            if (profile.getDependencyManagement() != null) {
                profile.getDependencyManagement()
                        .getDependencies()
                        .stream()
                        .filter(dependency -> dependency.getScope() == null || !dependency.getScope().equals("test"))
                        .forEach(managedDependencies::add);
            }
            if (profile.getDependencies() != null) {
                profile.getDependencies()
                        .stream()
                        .filter(dependency -> dependency.getScope() == null || !dependency.getScope().equals("test"))
                        .forEach(dependencies::add);
            }
            if (profile.getProperties() != null) {
                properties.putAll(profile.getProperties());
            }
        }

        CollectRequest collectRequest = new CollectRequest();
        collectRequest.setRepositories(repositories);

        managedDependencies.forEach(dep -> {
            String groupId = resolveGavElement(dep.getGroupId(), properties);
            String artifactId = resolveGavElement(dep.getArtifactId(), properties);
            String version = resolveGavElement(dep.getVersion(), properties);

            Artifact artifact = new DefaultArtifact(
                    groupId + ":" + artifactId + ":pom:" + version);
            ArtifactDescriptorRequest descriptorRequest = new ArtifactDescriptorRequest();
            descriptorRequest.setArtifact(artifact);
            descriptorRequest.setRepositories(repositories);
            try {
                ArtifactDescriptorResult descriptorResult = repositorySystem.readArtifactDescriptor(session,
                        descriptorRequest);
                descriptorResult.getManagedDependencies().forEach(collectRequest::addManagedDependency);
                collectRequest.addManagedDependency(RepositoryUtils.toDependency(dep, session.getArtifactTypeRegistry()));
            } catch (ArtifactDescriptorException e) {
                throw new RuntimeException(e);
            }
        });

        dependencies.forEach(dep -> {
            Optional<Dependency> match = collectRequest.getManagedDependencies()
                    .stream().filter(dependency -> {
                        Artifact artifact = dependency.getArtifact();
                        return artifact.getGroupId().equals(dep.getGroupId())
                                && artifact.getArtifactId().equals(dep.getArtifactId());
                    })
                    .findFirst();

            if (match.isPresent()) {
                collectRequest.addDependency(match.get());
            } else {
                dep.setGroupId(resolveGavElement(dep.getGroupId(), properties));
                dep.setArtifactId(resolveGavElement(dep.getArtifactId(), properties));
                if (dep.getVersion() != null) {
                    String version = resolveGavElement(dep.getVersion(), properties);
                    dep.setVersion(version);
                    collectRequest.addDependency(RepositoryUtils.toDependency(dep, session.getArtifactTypeRegistry()));
                } else {
                    getLog().info(
                            "⚠️ Unable to find a valid version for %s:%s in project %s".formatted(dep.getGroupId(),
                                    dep.getArtifactId(),
                                    model.getArtifactId()));
                }
            }
        });

        DependencyFilter classpathFilter = DependencyFilterUtils.classpathFilter(JavaScopes.COMPILE);
        DependencyRequest dependencyRequest = new DependencyRequest(collectRequest, classpathFilter);
        DependencyResult result = repositorySystem.resolveDependencies(session, dependencyRequest);
        List<DependencyNode> children = result.getRoot().getChildren();
        Set<GAV> projectDependencies = new HashSet<>();
        for (DependencyNode node : children) {
            Artifact artifact = node.getArtifact();
            boolean isIgnored = ignoredDependencies.contains(artifact.getGroupId() + ":" + artifact.getArtifactId());
            projectDependencies.add(new GAV(artifact, isIgnored));
        }
        return projectDependencies;
    }

    String resolveGavElement(String value, Properties properties) {
        if (value.contains("${")) {
            Matcher matcher = EXPRESSION.matcher(value);
            if (matcher.find()) {
                return resolveGavElement(properties.getProperty(matcher.group(1)), properties);
            }
        }
        return value;
    }

    void setUpSourceTransformations() {
        setUpPreSyncSourceTransformations();
        setUpPostSyncSourceTransformations();
    }

    /**
     * Source transformations that should be applied before dependency analysis is run and before the example project is
     * synced to its destination.
     */
    void setUpPreSyncSourceTransformations() {
        // pom.xml transforms. Anything that might impact the result of dependency analysis should be done here.
        preSyncTransformers.add(new SourceTransformer() {
            @Override
            public boolean canApply(Path source) {
                return source.toFile().getName().equals("pom.xml");
            }

            @Override
            public void apply(Path source) {

                /* Set versions in the top pom.xml and in submodules if there are any */
                MavenSourceTree t = MavenSourceTree.of(source, StandardCharsets.UTF_8);
                t.setVersions(getCamelQuarkusExamplesVersion(), p -> true, PomTransformer.SimpleElementWhitespace.SPACE);

                PomTransformer pomTransformer = new PomTransformer(source, StandardCharsets.UTF_8,
                        PomTransformer.SimpleElementWhitespace.SPACE);

                pomTransformer.transform(new PomTransformer.Transformation() {
                    @Override
                    public void perform(Document document, TransformationContext context) {
                        // Update BOM version properties
                        ContainerElement properties = context.getOrAddContainerElement("properties");
                        properties.addOrSetChildTextElement("quarkus.platform.group-id", quarkusPlatformGroupId);
                        properties.addOrSetChildTextElement("quarkus.platform.artifact-id", quarkusPlatformArtifactId);
                        properties.addOrSetChildTextElement("quarkus.platform.version", quarkusPlatformVersion);
                        properties.addOrSetChildTextElement("camel-quarkus.platform.group-id", camelQuarkusPlatformGroupId);
                        properties.addOrSetChildTextElement("camel-quarkus.platform.artifact-id",
                                camelQuarkusPlatformArtifactId);
                        properties.addOrSetChildTextElement("camel-quarkus.platform.version", camelQuarkusPlatformVersion);

                        // Add MRRC repositories
                        ContainerElement repositories = context.getOrAddContainerElements("repositories");
                        addRepository(repositories, "redhat-ga-repository", MRRC_GA_URL, false);
                        addRepository(repositories, "redhat-earlyaccess-repository", MRRC_EARLYACCESS_URL, false);

                        ContainerElement pluginRepositories = context.getOrAddContainerElements("pluginRepositories");
                        addRepository(pluginRepositories, "redhat-ga-repository", MRRC_GA_URL, true);
                        addRepository(pluginRepositories, "redhat-earlyaccess-repository", MRRC_EARLYACCESS_URL, true);

                        // Remove kubernetes profile
                        context.getOrAddProfileParent("kubernetes").remove(true, true);

                        // Remove explicit version for quarkus-artemis-jms since these dependencies are in the productized platform BOMs
                        if (camelQuarkusPlatformArtifactId.equals("quarkus-camel-bom")) {
                            context.getDependencies()
                                    .stream()
                                    .filter(dependency -> dependency.getArtifactId().equals("quarkus-artemis-jms"))
                                    .findFirst()
                                    .ifPresent(quarkusArtemis -> {
                                        quarkusArtemis.getNode()
                                                .getChildContainerElement("version")
                                                .ifPresent(containerElement -> containerElement.remove(true, true));
                                    });
                        }
                    }
                });
            }

            private void addRepository(ContainerElement parent, String id, String url, boolean isPluginRepository) {
                String tagName = isPluginRepository ? "pluginRepository" : "repository";
                ContainerElement repository = parent.addChildContainerElement(tagName);
                repository.addChildTextElement("id", id);
                repository.addChildTextElement("url", url);
                ContainerElement repositoryReleases = repository.getOrAddChildContainerElement("releases");
                repositoryReleases.addChildTextElement("enabled", "true");
                ContainerElement repositorySnapshots = repository.getOrAddChildContainerElement("snapshots");
                repositorySnapshots.addChildTextElement("enabled", "false");
            }
        });
    }

    /**
     * Source transformations to apply after the example project is synced to its destination.
     */
    void setUpPostSyncSourceTransformations() {
        // Remove Kubernetes manifests
        postSyncTransformers.add(new SourceTransformer() {
            @Override
            public boolean canApply(Path source) {
                return source.getParent().getFileName().toString().equals("kubernetes")
                        && source.getFileName().toString().equals("kubernetes.yml");
            }

            @Override
            public void apply(Path source) {
                try {
                    Files.deleteIfExists(source);
                } catch (IOException e) {
                    getLog().error("Failed deleting Kubernetes manifest", e);
                }
            }
        });

        // README updates
        postSyncTransformers.add(new SourceTransformer() {
            static final String HEADING_REGEX = "(?s)Deploying to Kubernetes.*?(?=Deploying to OpenShift)";

            @Override
            public boolean canApply(Path source) {
                return source.getFileName().toString().equals("README.adoc");
            }

            @Override
            public void apply(Path source) {
                try {
                    String content = Files.readString(source);

                    // Remove Kubernetes deployment instructions and leave OpenShift docs
                    String updatedContent = content.replaceFirst(HEADING_REGEX, "");
                    if (!content.equals(updatedContent)) {
                        Files.writeString(source, updatedContent);
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        });
    }

    String getCamelQuarkusExamplesVersion() {
        String cqPlatformVersion = resolveCamelQuarkusPlatformVersion();
        String[] versionParts = cqPlatformVersion.split("\\.");
        if (versionParts.length < 3) {
            throw new IllegalArgumentException("Invalid Camel Quarkus platform version: " + cqPlatformVersion);
        }
        return String.join(".", versionParts[0], versionParts[1], "0.redhat-00001");
    }

    String getGitHubDownloadBaseUrl() {
        return GITHUB_BASE_URL;
    }

    Path getArchivePath() {
        return tmpDir.toPath().resolve("camel-quarkus-examples-%s.zip".formatted(getUpstreamBranchName()));
    }

    URL getGitHubBranchArchiveURL() {
        String url = getGitHubDownloadBaseUrl().formatted(getUpstreamBranchName());
        try {
            return new URL(url);
        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        }
    }

    String getUpstreamBranchName() {
        String[] versionParts = resolveCamelQuarkusPlatformVersion().split("\\.");
        return "%s.%s.x".formatted(versionParts[0], versionParts[1]);
    }

    String resolveCamelQuarkusPlatformVersion() {
        if (camelQuarkusPlatformVersion.equals("${quarkus.platform.version}")) {
            return quarkusPlatformVersion;
        }
        return camelQuarkusPlatformVersion;
    }

    Path getExtractedArchivePath() {
        if (syncFromDir != null) {
            return syncFromDir.toPath();
        }
        return tmpDir.toPath().resolve("camel-quarkus-examples-%s".formatted(getUpstreamBranchName()));
    }

    static final class GAV {
        private final Artifact artifact;
        private final boolean ignored;

        public GAV(Artifact artifact, boolean ignored) {
            this.artifact = artifact;
            this.ignored = ignored;
        }

        public String getGroupId() {
            return artifact.getGroupId();
        }

        public String getArtifactId() {
            return artifact.getArtifactId();
        }

        public String getVersion() {
            return artifact.getVersion();
        }

        public boolean isUnProductized() {
            return !ignored && !getVersion().contains("redhat");
        }

        public boolean isIgnored() {
            return ignored;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (o == null || getClass() != o.getClass())
                return false;
            GAV gav = (GAV) o;
            return Objects.equals(artifact, gav.artifact);
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(artifact);
        }

        @Override
        public String toString() {
            if (ignored) {
                return "%s:%s:%s (unproductized but allowed)".formatted(getGroupId(), getArtifactId(), getVersion());
            } else {
                return "%s:%s:%s".formatted(getGroupId(), getArtifactId(), getVersion());
            }
        }
    }

    interface SourceTransformer {
        /**
         * Whether the transformer can run.
         *
         * @param  source The source file to be transformed
         * @return        {@code true} if the source file can be transformed. {@code false} if transformations should not be
         *                applied.
         */
        boolean canApply(Path source);

        /**
         * Applies transformations to the given file.
         *
         * @param source The path to the source file to transform
         */
        void apply(Path source);
    }
}
