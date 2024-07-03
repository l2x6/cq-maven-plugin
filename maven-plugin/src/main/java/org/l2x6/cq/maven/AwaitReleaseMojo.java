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
package org.l2x6.cq.maven;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/**
 * Lists the artifacts having the given {@link #groupId} and {@link #version} from the {@link #localRepository} and
 * checks that they are available in the {@link #remoteRepository}. As long as there are unavailable artifacts, the
 * requests are re-tried with the {@link #retrySec} delay.
 *
 * @since 0.40.0
 */
@Mojo(name = "await-release", threadSafe = true, requiresProject = false)
public class AwaitReleaseMojo extends AbstractExtensionListMojo {

    /**
     * The version of Camel Quarkus to await in the remote Maven repo
     *
     * @since 0.40.0
     */
    @Parameter(property = "cq.version", required = true)
    String version;

    /**
     * The remote repository base URI where to check the availability of the artifact
     *
     * @since 0.40.0
     */
    @Parameter(property = "cq.remoteRepository", defaultValue = "https://repo1.maven.org/maven2", required = true)
    String remoteRepository;

    /**
     * A retry delay in seconds
     *
     * @since 0.40.0
     */
    @Parameter(property = "cq.retrySec", defaultValue = "60", required = true)
    int retrySec;

    /**
     * The groupId to check
     *
     * @since 0.40.0
     */
    @Parameter(property = "cq.groupId", defaultValue = "${project.groupId}", required = true)
    String groupId;

    @Parameter(defaultValue = "${settings.localRepository}", readonly = true)
    String localRepository;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        final String groupIdRelPath = groupId.replace(".", "/");
        final Path localBasedir = Paths.get(localRepository).resolve(groupIdRelPath);
        final String remoteBasedir = remoteRepository + "/" + groupIdRelPath;
        final int retryMillis = retrySec * 1000;

        final List<String> remotePaths;
        try (Stream<Path> artifactDirs = Files.list(localBasedir)) {
            remotePaths = artifactDirs
                    .map(p -> p.resolve(version).resolve(p.getFileName().toString() + "-" + version + ".pom"))
                    .filter(Files::isRegularFile)
                    .map(localBasedir::relativize)
                    .map(Path::toString)
                    .peek(relPath -> getLog().info(" - " + relPath))
                    .map(relPath -> remoteBasedir + "/" + relPath)
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        getLog().info("Awaiting " + remotePaths.size() + " artifacts in " + remoteRepository);

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(3000))
                .build();
        int uriCount = remotePaths.size();
        int verifiedUriCount = 0;
        while (!remotePaths.isEmpty()) {

            while (!remotePaths.isEmpty()) {
                int randomIndex = remotePaths.size() == 1 ? 0 : (int) Math.round((Math.random() * (double) remotePaths.size()));
                if (randomIndex >= remotePaths.size()) {
                    continue;
                }
                final String uri = remotePaths.get(randomIndex);
                final HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(uri))
                        .build();
                try {
                    HttpResponse<String> response = httpClient.send(request, BodyHandlers.ofString());
                    final int statusCode = response.statusCode();
                    if (statusCode == 200) {
                        remotePaths.remove(randomIndex);
                        verifiedUriCount++;
                    }
                    getLog().info("" + verifiedUriCount + "/" + uriCount + " Got " + statusCode + " for " + uri);
                    if (statusCode != 200) {
                        /* Do not iterate over the rest of the list once we have got a non-200 */
                        break;
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            }

            if (!remotePaths.isEmpty()) {
                getLog().info("Sleeping " + retrySec + " seconds before the next iteration");
                try {
                    Thread.sleep(retryMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            }
        }
    }

}
