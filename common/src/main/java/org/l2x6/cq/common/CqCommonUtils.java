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
import java.nio.file.Files;
import java.nio.file.Path;
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

}
