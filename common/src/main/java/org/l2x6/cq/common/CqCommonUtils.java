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
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public class CqCommonUtils {

    private CqCommonUtils() {
    }

    public static Path copyJar(Path localRepository, String groupId, String artifactId, String version) {
        return copyArtifact(localRepository, groupId, artifactId, version, "jar");
    }

    public static Path copyArtifact(Path localRepository, String groupId, String artifactId, String version, String type) {
        final String relativeJarPath = groupId.replace('.', '/') + "/" + artifactId + "/" + version + "/" + artifactId + "-"
                + version + "." + type;
        final Path localPath = localRepository.resolve(relativeJarPath);
        final boolean localExists = Files.exists(localPath);
        final String remoteUri = "https://repository.apache.org/content/groups/public/" + relativeJarPath;
        Path result;
        try {
            result = Files.createTempFile(null, localPath.getFileName().toString());
            try (InputStream in = (localExists ? Files.newInputStream(localPath) : new URL(remoteUri).openStream());
                    OutputStream out = Files.newOutputStream(result)) {
                final byte[] buf = new byte[4096];
                int len;
                while ((len = in.read(buf)) >= 0) {
                    out.write(buf, 0, len);
                }
            } catch (IOException e) {
                throw new RuntimeException("Could not copy " + (localExists ? localPath : remoteUri) + " to " + result, e);
            }
        } catch (IOException e) {
            throw new RuntimeException("Could not create temp file", e);
        }
        return result;
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
}
