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
import java.io.Serializable;
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
import java.util.StringTokenizer;
import java.util.function.Predicate;
import java.util.regex.Pattern;
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

    /**
     * A list of {@code artifactId}s or {@code artifactId} patterns (may contain zero, one or more {@code *} wildcards)
     * to exclude from the set of artifacts that availablitity of which in {@link #remoteRepository} will be checked.
     * <p>
     * Examples: {@code *-docs}
     *
     * @since 4.21.0
     */
    @Parameter(property = "cq.excludeArtifactIdPatterns")
    List<String> excludeArtifactIdPatterns;

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
                    .filter(artifactIdFilter(excludeArtifactIdPatterns))
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

    static Predicate<Path> artifactIdFilter(final List<String> rawArtifactIdPatterns) {
        final Predicate<Path> artifactIdFilter;
        if (rawArtifactIdPatterns == null) {
            artifactIdFilter = path -> true;
        } else {
            final List<GavSegmentPattern> excludeArtifactIdPatterns = rawArtifactIdPatterns.stream()
                    .map(GavSegmentPattern::new)
                    .toList();
            artifactIdFilter = path -> {
                final String artifactId = path.getFileName().toString();
                return excludeArtifactIdPatterns.stream().noneMatch(pattern -> pattern.matches(artifactId));
            };
        }
        return artifactIdFilter;
    }

    static class GavSegmentPattern implements Serializable {
        static final String MULTI_WILDCARD = "*";
        static final char MULTI_WILDCARD_CHAR = '*';
        static final String MATCH_ALL_PATTERN_SOURCE = ".*";

        private final transient Pattern pattern;
        private final String source;

        GavSegmentPattern(String wildcardSource) {
            super();
            final StringBuilder sb = new StringBuilder(wildcardSource.length() + 2);
            final StringTokenizer st = new StringTokenizer(wildcardSource, MULTI_WILDCARD, true);
            while (st.hasMoreTokens()) {
                String token = st.nextToken();
                if (MULTI_WILDCARD.equals(token)) {
                    sb.append(MATCH_ALL_PATTERN_SOURCE);
                } else {
                    sb.append(Pattern.quote(token));
                }
            }
            this.pattern = Pattern.compile(sb.toString());
            this.source = wildcardSource;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            GavSegmentPattern other = (GavSegmentPattern) obj;
            return source.equals(other.source);
        }

        /**
         * @return the wildcard source of the {@link #pattern}
         */
        public String getSource() {
            return source;
        }

        @Override
        public int hashCode() {
            return source.hashCode();
        }

        public boolean matches(String input) {
            return MATCH_ALL_PATTERN_SOURCE.equals(source) || pattern.matcher(input == null ? "" : input).matches();
        }

        @Override
        public String toString() {
            return source;
        }
    }
}
