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
package org.l2x6.cq.common;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
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
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.camel.catalog.Kind;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.l2x6.maven.utils.Gavtcs;
import org.l2x6.maven.utils.PomTransformer;
import org.l2x6.maven.utils.PomTransformer.SimpleElementWhitespace;
import org.l2x6.maven.utils.PomTransformer.Transformation;

public class CqCommonUtils {

    public static final String VIRTUAL_DEPS_INITIAL_COMMENT = " The following dependencies guarantee that this module is built after them. You can update them by running `mvn process-resources -Pformat -N` from the source tree root directory ";
    /** The number of attempts to try when creating a new directory */
    private static final int CREATE_RETRY_COUNT = 256;
    private static final long DELETE_RETRY_MILLIS = 5000L;
    private static final boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");

    private CqCommonUtils() {
    }

    public static Path copyJar(Path localRepository, String groupId, String artifactId, String version,
            List<String> remoteRepositories) {
        return copyArtifact(localRepository, groupId, artifactId, version, "jar", remoteRepositories);
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

}
