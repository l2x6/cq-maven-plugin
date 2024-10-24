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
package org.l2x6.cq.common;

import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateException;
import freemarker.template.TemplateExceptionHandler;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystemException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.camel.tooling.model.Kind;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.lifecycle.internal.MojoDescriptorCreator;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.plugin.InvalidPluginDescriptorException;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.MojoNotFoundException;
import org.apache.maven.plugin.PluginDescriptorParsingException;
import org.apache.maven.plugin.PluginNotFoundException;
import org.apache.maven.plugin.PluginParameterExpressionEvaluator;
import org.apache.maven.plugin.PluginResolutionException;
import org.apache.maven.plugin.descriptor.MojoDescriptor;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugin.prefix.NoPluginFoundForPrefixException;
import org.apache.maven.plugin.version.PluginVersionResolutionException;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuilder;
import org.apache.maven.project.ProjectBuildingException;
import org.apache.maven.project.ProjectBuildingRequest;
import org.assertj.core.util.diff.Delta;
import org.codehaus.plexus.component.configurator.expression.ExpressionEvaluationException;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;
import org.l2x6.cq.common.sync.SyncExpression;
import org.l2x6.cq.common.sync.SyncExpressions;
import org.l2x6.cq.common.sync.SyncExpressions.Builder;
import org.l2x6.pom.tuner.MavenSourceTree.ActiveProfiles;
import org.l2x6.pom.tuner.PomTransformer;
import org.l2x6.pom.tuner.PomTransformer.ContainerElement;
import org.l2x6.pom.tuner.PomTransformer.SimpleElementWhitespace;
import org.l2x6.pom.tuner.PomTransformer.Transformation;
import org.l2x6.pom.tuner.PomTransformer.TransformationContext;
import org.l2x6.pom.tuner.PomTunerUtils;
import org.l2x6.pom.tuner.model.Ga;
import org.l2x6.pom.tuner.model.Gavtcs;
import org.l2x6.pom.tuner.model.Profile;
import org.w3c.dom.Comment;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import static java.util.stream.Collectors.joining;

public class CqCommonUtils {

    public static final String VIRTUAL_DEPS_INITIAL_COMMENT = " The following dependencies guarantee that this module is built after them. You can update them by running `mvn process-resources -Pformat -N` from the source tree root directory ";
    /** The number of attempts to try when creating a new directory */
    private static final int CREATE_RETRY_COUNT = 256;
    private static final long DELETE_RETRY_MILLIS = 5000L;
    private static final boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");

    private static final Pattern SIMPLE_XML_ELEMENT_PATTERN = Pattern.compile("\\s+/>");

    /** Module name delimiters used throughout Quarkus ecosystem */
    private static final List<String> MAVEN_MODULE_NAME_DELIMITERS = List.of(
            Pattern.quote(" :: "), Pattern.quote(" : "), Pattern.quote(" - "));

    private CqCommonUtils() {
    }

    public static Path resolveJar(Path localRepository, String groupId, String artifactId, String version,
            List<RemoteRepository> remoteRepositories, RepositorySystem repoSystem, RepositorySystemSession repoSession) {
        return resolveArtifact(localRepository, groupId, artifactId, version, "jar", remoteRepositories, repoSystem,
                repoSession);
    }

    public static Path resolveArtifact(Path localRepository, String groupId, String artifactId, String version, String type,
            List<RemoteRepository> repositories, RepositorySystem repoSystem, RepositorySystemSession repoSession) {
        final String relativeJarPath = groupId.replace('.', '/') + "/" + artifactId + "/" + version + "/" + artifactId + "-"
                + version + "." + type;
        final Path localPath = localRepository.resolve(relativeJarPath);
        if (Files.exists(localPath)) {
            return localPath;
        }
        final org.eclipse.aether.artifact.Artifact aetherArtifact = new DefaultArtifact(
                groupId,
                artifactId,
                null,
                type,
                version);

        final ArtifactRequest req = new ArtifactRequest().setRepositories(repositories).setArtifact(aetherArtifact);
        ArtifactResult resolutionResult;
        try {
            resolutionResult = repoSystem.resolveArtifact(repoSession, req);
        } catch (ArtifactResolutionException e) {
            throw new RuntimeException("Artifact " + aetherArtifact + " could not be resolved.", e);
        }
        return resolutionResult.getArtifact().getFile().toPath();

    }

    public static Path installArtifact(Path source, Path localRepository, String groupId, String artifactId, String version,
            String type) {
        final String relativeJarPath = groupId.replace('.', '/') + "/" + artifactId + "/" + version + "/" + artifactId + "-"
                + version + "." + type;
        final Path localPath = localRepository.resolve(relativeJarPath);
        try {
            Files.createDirectories(localPath.getParent());
            Files.copy(source, localPath, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new RuntimeException("Could not copy " + source + " to " + localPath, e);
        }
        return localPath;
    }

    public static Path copyArtifact(Path localRepository, String groupId, String artifactId, String version, String type,
            List<String> remoteRepositories) {
        final String relativeJarPath = groupId.replace('.', '/') + "/" + artifactId + "/" + version + "/" + artifactId + "-"
                + version + "." + type;
        final Path localPath = localRepository.resolve(relativeJarPath);
        final boolean localExists = Files.exists(localPath);
        Path result;
        try {
            result = Files.createTempFile(null, localPath.getFileName().toString());
            try (InputStream in = (localExists ? Files.newInputStream(localPath)
                    : openFirst(remoteRepositories, relativeJarPath));
                    OutputStream out = Files.newOutputStream(result)) {
                final byte[] buf = new byte[4096];
                int len;
                while ((len = in.read(buf)) >= 0) {
                    out.write(buf, 0, len);
                }
            } catch (IOException e) {
                throw new RuntimeException("Could not copy " + (localExists ? localPath : relativeJarPath) + " to " + result,
                        e);
            }
        } catch (IOException e) {
            throw new RuntimeException("Could not create temp file", e);
        }
        return result;
    }

    public static InputStream openFirst(List<String> remoteRepositories, String relativePath)
            throws IOException, MalformedURLException {
        for (String repo : remoteRepositories) {
            try {
                final String uri = repo.endsWith("/") ? (repo + relativePath) : repo + "/" + relativePath;
                return new URL(uri).openStream();
            } catch (IOException e) {
                // continue
            }
        }
        throw new RuntimeException("Could not get " + relativePath + " from any of "
                + remoteRepositories.stream().map(r -> r + relativePath).collect(Collectors.joining(", ")));
    }

    public static boolean isEmptyPropertiesFile(Path file) {
        final Properties props = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            props.load(in);
        } catch (IOException e) {
            throw new RuntimeException("Could not read " + file, e);
        }
        return props.isEmpty();
    }

    public static Model readPom(final Path path, Charset charset) {
        try (Reader r = Files.newBufferedReader(path, charset)) {
            return new MavenXpp3Reader().read(r);
        } catch (XmlPullParserException | IOException e) {
            throw new RuntimeException("Could not parse " + path, e);
        }
    }

    public static String humanPlural(Kind kind) {
        switch (kind) {
        case eip:
            return "EIPs";
        default:
            return firstCap(kind.name()) + "s";
        }
    }

    public static String firstCap(String in) {
        if (in == null) {
            return null;
        }
        if (in.isEmpty()) {
            return in;
        }
        final StringBuilder sb = new StringBuilder(in.length());
        sb.append(Character.toUpperCase(in.charAt(0)));
        if (in.length() > 1) {
            sb.append(in.substring(1));
        }
        return sb.toString();
    }

    public static void updateVirtualDependencies(Charset charset, SimpleElementWhitespace simpleElementWhitespace,
            final Set<Gavtcs> allVirtualExtensions, final Path pomXmlPath) {
        new PomTransformer(pomXmlPath, charset, simpleElementWhitespace)
                .transform(
                        Transformation.updateDependencySubset(
                                gavtcs -> gavtcs.isVirtual(),
                                allVirtualExtensions,
                                Gavtcs.scopeAndTypeFirstComparator(),
                                VIRTUAL_DEPS_INITIAL_COMMENT),
                        Transformation.removeProperty(true, true, "mvnd.builder.rule"),
                        Transformation.removeContainerElementIfEmpty(true, true, true, "properties"));
    }

    public static String virtualDepsCommentXPath() {
        return "//comment()[contains(.,'" + VIRTUAL_DEPS_INITIAL_COMMENT + "')]";
    }

    /**
     * Deletes a file or directory recursively if it exists.
     *
     * @param directory the directory to delete
     */
    public static void deleteDirectory(Path directory) {
        if (Files.exists(directory)) {
            try {
                Files.walkFileTree(directory, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                        if (exc == null) {
                            Files.delete(dir);
                            return FileVisitResult.CONTINUE;
                        } else {
                            // directory iteration failed; propagate exception
                            throw exc;
                        }
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        if (isWindows) {
                            final long deadline = System.currentTimeMillis() + DELETE_RETRY_MILLIS;
                            FileSystemException lastException = null;
                            do {
                                try {
                                    Files.delete(file);
                                    return FileVisitResult.CONTINUE;
                                } catch (FileSystemException e) {
                                    lastException = e;
                                }
                            } while (System.currentTimeMillis() < deadline);
                            throw new IOException(String.format("Could not delete file [%s] after retrying for %d ms", file,
                                    DELETE_RETRY_MILLIS), lastException);
                        } else {
                            Files.delete(file);
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                        // try to delete the file anyway, even if its attributes
                        // could not be read, since delete-only access is
                        // theoretically possible
                        Files.delete(file);
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException e) {
                throw new RuntimeException("Could not delete " + directory, e);
            }
        }
    }

    /**
     * Makes sure that the given directory exists. Tries creating {@link #CREATE_RETRY_COUNT} times.
     *
     * @param dir the directory {@link Path} to check
     */
    public static void ensureDirectoryExists(Path dir) {
        Throwable toThrow = null;
        for (int i = 0; i < CREATE_RETRY_COUNT; i++) {
            try {
                Files.createDirectories(dir);
                if (Files.exists(dir)) {
                    return;
                }
            } catch (AccessDeniedException e) {
                toThrow = e;
                /* Workaround for https://bugs.openjdk.java.net/browse/JDK-8029608 */
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e1) {
                    Thread.currentThread().interrupt();
                    toThrow = e1;
                }
            } catch (IOException e) {
                toThrow = e;
            }
        }
        if (toThrow != null) {
            throw new RuntimeException(String.format("Could not create directory [%s]", dir), toThrow);
        } else {
            throw new RuntimeException(
                    String.format("Could not create directory [%s] attempting [%d] times", dir, CREATE_RETRY_COUNT));
        }

    }

    /**
     * If the given directory does not exist, creates it using {@link #ensureDirectoryExists(Path)}. Otherwise
     * recursively deletes all subpaths in the given directory.
     *
     * @param dir the directory to check
     */
    public static void ensureDirectoryExistsAndEmpty(Path dir) {
        if (Files.exists(dir)) {
            try (DirectoryStream<Path> subPaths = Files.newDirectoryStream(dir)) {
                for (Path subPath : subPaths) {
                    if (Files.isDirectory(subPath)) {
                        deleteDirectory(subPath);
                    } else {
                        Files.delete(subPath);
                    }
                }
            } catch (IOException e) {
                throw new RuntimeException("Could not process " + dir, e);
            }
        } else {
            ensureDirectoryExists(dir);
        }
    }

    public static List<Delta<String>> compareFiles(Path actual, Path expected, Charset charset) {
        List<String> actualLines;
        try {
            actualLines = Files.readAllLines(actual, charset);
        } catch (IOException e) {
            throw new RuntimeException("Could not read " + actual);
        }
        List<String> expectedLines;
        try {
            expectedLines = Files.readAllLines(expected, charset);
        } catch (IOException e) {
            throw new RuntimeException("Could not read " + expected);
        }
        if (actual.getFileName().toString().endsWith(".xml") || expected.getFileName().toString().endsWith(".xml")) {
            /* normalize XML */
            normalizeXML(actualLines);
            normalizeXML(expectedLines);
        }
        return org.assertj.core.util.diff.DiffUtils.diff(actualLines, expectedLines).getDeltas();
    }

    static List<String> normalizeXML(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            lines.set(i, SIMPLE_XML_ELEMENT_PATTERN.matcher(line).replaceAll("/>"));
        }
        return lines;
    }

    public static void visitPoms(Path src, Consumer<Path> pomConsumer, final Predicate<Path> additionalFiles) {
        Set<Path> paths = new TreeSet<>();
        try {
            Files.walkFileTree(src, new SimpleFileVisitor<Path>() {

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                        throws IOException {
                    final String dirName = dir.getFileName().toString();
                    if ((dirName.equals("target"))
                            && Files.isRegularFile(dir.getParent().resolve("pom.xml"))) {
                        return FileVisitResult.SKIP_SUBTREE;
                    } else {
                        return FileVisitResult.CONTINUE;
                    }
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    final String fileName = file.getFileName().toString();
                    if (fileName.equals("pom.xml") || additionalFiles.test(file)) {
                        paths.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new RuntimeException("Could not visit pom.xml files under " + src, e);
        }
        paths.stream()
                .forEach(pomConsumer);
    }

    public static Path copyPoms(Path src, Path dest, Predicate<Path> additionalFiles) {
        ensureDirectoryExistsAndEmpty(dest);
        visitPoms(
                src,
                file -> {
                    final Path destPath = dest.resolve(src.relativize(file));
                    try {
                        Files.createDirectories(destPath.getParent());
                        Files.copy(file, destPath, StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException e) {
                        throw new RuntimeException("Could not copy " + file + " to " + destPath, e);
                    }
                },
                additionalFiles);
        return dest;
    }

    public static void assertPomsMatch(Path src, Path dest, Set<String> activeRelativePomPaths, Predicate<Path> additionalFiles,
            Charset charset, Path basedir, Path referenceFile, OnFailure onCheckFailure, Consumer<String> warn,
            String fqFixMojo) {
        visitPoms(
                src,
                file -> {
                    final Path relPomPath = src.relativize(file);
                    final String unixPath = PomTunerUtils.toUnixPath(relPomPath.toString());
                    if (!unixPath.endsWith("/pom.xml") || activeRelativePomPaths.contains(unixPath)) {
                        final Path destPath = dest.resolve(relPomPath);

                        List<Delta<String>> diffs = CqCommonUtils.compareFiles(file, destPath, charset);
                        if (!diffs.isEmpty()) {
                            String msg = "File [" + PomTunerUtils.toUnixPath(basedir.relativize(destPath).toString())
                                    + "] is not in sync with "
                                    + PomTunerUtils.toUnixPath(basedir.relativize(referenceFile).toString()) + ":\n\n    "
                                    + diffs.stream().map(Delta::toString).collect(joining("\n    "))
                                    + "\n\n Consider running mvn " + fqFixMojo + " -N\n\n";
                            switch (onCheckFailure) {
                            case FAIL:
                                throw new RuntimeException(msg);
                            case WARN:
                                warn.accept(msg);
                                break;
                            case IGNORE:
                                break;
                            default:
                                throw new IllegalStateException("Unexpected " + OnFailure.class + " value " + onCheckFailure);
                            }
                        }
                    }
                },
                additionalFiles);
    }

    /**
     * @param  gas the universe to filter from
     * @return     a {@link Stream} of runtime extension {@link Ga}s
     */
    public static Stream<Ga> filterExtensions(Stream<Ga> gas) {
        return gas
                .filter(ga -> ga.getArtifactId().endsWith("-deployment"))
                .map(ga -> new Ga(ga.getGroupId(),
                        ga.getArtifactId().substring(0, ga.getArtifactId().length() - "-deployment".length())));
    }

    public static void syncVersions(Path pomXml, MojoDescriptorCreator mojoDescriptorCreator, MavenSession session,
            MavenProject project, Charset charset, SimpleElementWhitespace simpleElementWhitespace,
            Path localRepositoryPath, Log log, Map<String, String> versionTransformations,
            List<RemoteRepository> repositories,
            RepositorySystemSession repoSession,
            RepositorySystem repoSystem) {
        try {
            new PomTransformer(pomXml, charset, simpleElementWhitespace)
                    .transform(
                            new UpdateVersionsTransformation(
                                    new PomModelCache(localRepositoryPath, repositories, repoSystem, repoSession,
                                            project.getModel()),
                                    session,
                                    mojoDescriptorCreator,
                                    log,
                                    versionTransformations));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static class UpdateVersionsTransformation implements Transformation {

        private final PomModelCache pomModels;
        private final MavenSession session;
        private final Log log;
        private final Map<String, String> versionTransformations;
        private final MojoDescriptorCreator mojoDescriptorCreator;

        public UpdateVersionsTransformation(PomModelCache pomModels, MavenSession session,
                MojoDescriptorCreator mojoDescriptorCreator, Log log,
                Map<String, String> versionTransformations) {
            this.pomModels = pomModels;
            this.session = session;
            this.mojoDescriptorCreator = mojoDescriptorCreator;
            this.log = log;
            this.versionTransformations = versionTransformations;
        }

        @Override
        public void perform(Document document, TransformationContext context) {
            context.getContainerElement("project", "properties").ifPresent(props -> {
                final Builder expressionsBuilder = SyncExpressions.builder();
                for (ContainerElement prop : props.childElements()) {
                    Comment nextComment = prop.nextSiblingCommentNode();
                    if (nextComment != null) {
                        final String commentText = nextComment.getNodeValue();
                        SyncExpression.parse(prop.getNode(), commentText)
                                .ifPresent(expr -> expressionsBuilder.expression(expr));
                    }
                }
                final SyncExpressions expressions = expressionsBuilder.build();
                final Function<String, String> mavenExpressionEvaluator = expr -> {
                    try {
                        final MojoDescriptor mojoDescriptor = mojoDescriptorCreator.getMojoDescriptor("help:evaluate", session,
                                session.getCurrentProject());
                        return (String) new PluginParameterExpressionEvaluator(
                                session,
                                new MojoExecution(mojoDescriptor))
                                .evaluate(expr, String.class);
                    } catch (ExpressionEvaluationException | PluginNotFoundException | PluginResolutionException
                            | PluginDescriptorParsingException | MojoNotFoundException | NoPluginFoundForPrefixException
                            | InvalidPluginDescriptorException | PluginVersionResolutionException e) {
                        throw new RuntimeException("Could not evaluate " + expr, e);
                    }
                };
                expressions.evaluate(mavenExpressionEvaluator, pomModels,
                        (SyncExpression syncExpression, String newValue) -> {
                            final Element propertyNode = syncExpression.getPropertyNode();
                            final String propertyName = propertyNode.getLocalName();

                            final StringWriter out = new StringWriter();
                            final String transformedValue;
                            final String versionTransformation = versionTransformations.get(propertyName);
                            if (versionTransformation != null) {
                                final Configuration templateCfg = new Configuration(Configuration.VERSION_2_3_28);
                                templateCfg.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);

                                try {
                                    final Template t = new Template(
                                            versionTransformation,
                                            new StringReader(versionTransformation),
                                            templateCfg);
                                    final Map<String, Object> model = Collections.singletonMap("version", newValue);
                                    t.process(model, out);
                                    transformedValue = out.toString();
                                } catch (IOException e) {
                                    throw new RuntimeException("Could not parse " + versionTransformation, e);
                                } catch (TemplateException e) {
                                    throw new RuntimeException("Could not process " + versionTransformation, e);
                                }
                            } else {
                                transformedValue = newValue;
                            }

                            final String oldValue = propertyNode.getTextContent();
                            if (oldValue.equals(transformedValue)) {
                                log.info(" ✓ " + propertyName + ": " + oldValue);
                            } else {
                                log.info(" 🚀 " + propertyName + ": " + oldValue + " -> " + transformedValue);
                            }

                            propertyNode.setTextContent(transformedValue);
                            session.getCurrentProject().getProperties().setProperty(propertyName, transformedValue);
                        });

            });
        }

    }

    public static Predicate<Profile> getProfiles(MavenSession session) {
        final Predicate<Profile> profiles = ActiveProfiles.of(
                session.getCurrentProject().getActiveProfiles().stream()
                        .map(org.apache.maven.model.Profile::getId)
                        .toArray(String[]::new));
        return profiles;
    }

    public static String getNameBase(final String name) {
        String[] nameParts = MAVEN_MODULE_NAME_DELIMITERS.stream()
                .map(delim -> name.split(delim))
                .filter(parts -> parts.length >= 2)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Could not split Maven module name '" + name
                        + "' using any of delimiters " + MAVEN_MODULE_NAME_DELIMITERS + " expecting 3 parts"));
        return nameParts[1];
    }

    public static Model resolveEffectiveModel(Path pomFile, ProjectBuilder mavenProjectBuilder, MavenSession session) {
        ProjectBuildingRequest pbr = new DefaultProjectBuildingRequest(session.getProjectBuildingRequest());
        pbr.setProcessPlugins(false);

        try {
            return mavenProjectBuilder.build(pomFile.toFile(), pbr).getProject().getModel();
        } catch (ProjectBuildingException e) {
            throw new RuntimeException("Failed to create model for " + pomFile, e);
        }
    }

}
