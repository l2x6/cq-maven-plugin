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

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.apache.maven.execution.MavenSession;
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
import org.eclipse.aether.collection.DependencyCollectionException;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.graph.DependencyVisitor;
import org.eclipse.aether.repository.RemoteRepository;
import org.l2x6.cq.common.CqCommonUtils;
import org.l2x6.pom.tuner.MavenSourceTree;
import org.l2x6.pom.tuner.MavenSourceTree.ActiveProfiles;
import org.l2x6.pom.tuner.model.Dependency;
import org.l2x6.pom.tuner.model.Gav;
import org.l2x6.pom.tuner.model.GavPattern;
import org.l2x6.pom.tuner.model.Gavtcs;
import org.l2x6.pom.tuner.model.Glob;
import org.l2x6.pom.tuner.model.Module;
import org.l2x6.pom.tuner.model.Profile;

/**
 * List the transitive runtime dependencies of all supported extensions.
 *
 * @since 2.16.0
 */
@Mojo(name = "find-dependency", threadSafe = true, requiresProject = true, inheritByDefault = false)
public class FindDependencyMojo extends AbstractMojo {
    /**
     * The basedir
     *
     * @since 2.16.0
     */
    @Parameter(property = "cq.basedir", defaultValue = "${project.basedir}")
    File basedir;

    /**
     * Encoding to read and write files in the current source tree
     *
     * @since 2.16.0
     */
    @Parameter(defaultValue = "utf-8", required = true, property = "cq.encoding")
    String encoding;

    Charset charset;

    /**
     * The basedir
     *
     * @since 2.16.0
     */
    @Parameter(property = "cq.version", defaultValue = "${project.version}")
    String version;

    /**
     * Skip the execution of this mojo.
     *
     * @since 2.16.0
     */
    @Parameter(property = "cq.find-dependency.skip", defaultValue = "false")
    boolean skip;

    /**
     * @since 2.16.0
     */
    @Parameter(defaultValue = "${project.remoteProjectRepositories}", readonly = true, required = true)
    List<RemoteRepository> repositories;

    /**
     * @since 2.16.0
     */
    @Parameter(defaultValue = "${settings.localRepository}", readonly = true)
    String localRepository;

    /**
     * Print out the dependency path from an imaginary application depending on all CQ extensions to any GAV matching
     * the
     * given {@link #gavPattern}.
     *
     * @since 2.16.0
     */
    @Parameter(property = "cq.gavPattern")
    String gavPattern;

    /**
     * Print out the dependency path from an imaginary application depending on all CQ extensions to any GAV matching
     * the given {@link #gavPattern} AND containing a resource whose slash-separated fully qualified name matches
     * the given {@link #resourcePattern}.
     * <p>
     * Examples:
     * <ul>
     * <li><code>-Dcq.resourcePattern=&#42;&#42;/FileBackedOutputStream.class</code>
     * <li><code>-Dcq.resourcePattern=org/acme/*</code>
     * </ul>
     *
     * @since 4.4.4
     */
    @Parameter(property = "cq.resourcePattern")
    String resourcePattern;

    /**
     *
     *
     * @since 2.16.0
     */
    @Parameter(property = "cq.rootsSourceType")
    RootsSourceType rootsSourceType;

    public static enum RootsSourceType {
        TREE, PLATFORM_BOMS
    }

    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    protected MavenSession session;

    @Component
    private RepositorySystem repoSystem;

    @Parameter(defaultValue = "${repositorySystemSession}", readonly = true, required = true)
    private RepositorySystemSession repoSession;

    /** {@inheritDoc} */
    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("Skipping as requested by the user");
            return;
        }
        charset = Charset.forName(encoding);
        if (rootsSourceType == null) {
            rootsSourceType = RootsSourceType.TREE;
        }
        final GavPattern gavPatternMatcher = gavPattern != null ? GavPattern.of(gavPattern) : null;
        final Glob resourceRegExPattern = resourcePattern == null ? null : new Glob(resourcePattern);
        final Path localRepositoryPath = Paths.get(localRepository);

        final Set<Gav> roots = listRoots();
        final Results results = new Results(roots);
        roots
                .forEach(extension -> {
                    final org.eclipse.aether.artifact.Artifact rootArtifact = new DefaultArtifact(
                            extension.getGroupId(),
                            extension.getArtifactId(),
                            null,
                            "jar",
                            extension.getVersion());

                    CollectRequest request = new CollectRequest();
                    request.setRepositories(repositories);
                    request.setRoot(new org.eclipse.aether.graph.Dependency(rootArtifact, null));

                    try {
                        final DependencyNode rootNode = repoSystem.collectDependencies(repoSession, request).getRoot();
                        rootNode.accept(new DependencyVisitor() {
                            private final Deque<Gavtcs> stack = new ArrayDeque<>();

                            @Override
                            public boolean visitLeave(DependencyNode node) {
                                stack.pop();
                                return true;
                            }

                            @Override
                            public boolean visitEnter(DependencyNode node) {
                                final Artifact a = node.getArtifact();
                                final Gavtcs gav = new Gavtcs(a.getGroupId(), a.getArtifactId(), a.getVersion(),
                                        a.getExtension(), a.getClassifier(), null);
                                stack.push(gav);

                                if (gavPattern != null
                                        && gavPatternMatcher.matches(a.getGroupId(), a.getArtifactId(), a.getVersion())) {

                                    String resourcePath = null;
                                    if (resourceRegExPattern != null) {
                                        final Path jarPath = CqCommonUtils.resolveJar(localRepositoryPath, a.getGroupId(),
                                                a.getArtifactId(), a.getVersion(), repositories, repoSystem, repoSession);
                                        resourcePath = findResource(jarPath, resourceRegExPattern);
                                    }
                                    results.add(stack, resourcePath);
                                }
                                return true;
                            }
                        });
                    } catch (DependencyCollectionException e) {
                        throw new RuntimeException("Could not resolve " + extension, e);
                    }

                });
        if (results.results().isEmpty()) {
            getLog().info("No transitive depdendencies found matching " + gavPattern);
        } else {
            getLog().info(
                    "\n\nTransitive dependencies matching '" + gavPattern + "':\n\n"
                            + results.results().stream()
                                    .map(Result::toString)
                                    .collect(Collectors.joining("\n\n"))
                            + "\n\n.");
        }
    }

    static String findResource(Path jarPath, Glob classPattern) {
        if (classPattern == null) {
            return null;
        }
        try (ZipFile zipFile = new ZipFile(jarPath.toFile())) {
            Enumeration<? extends ZipEntry> zipEntries = zipFile.entries();
            while (zipEntries.hasMoreElements()) {
                final String fileName = zipEntries.nextElement().getName();
                if (classPattern.matches(fileName)) {
                    return "\n           ^ " + fileName;
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Could not list entries in " + jarPath, e);
        }
        return null;
    }

    public Set<Gav> listRoots() {
        final Predicate<Profile> profiles = ActiveProfiles.of(
                session.getCurrentProject().getActiveProfiles().stream()
                        .map(org.apache.maven.model.Profile::getId)
                        .toArray(String[]::new));

        switch (rootsSourceType) {
        case TREE:
            final MavenSourceTree tree = MavenSourceTree.of(basedir.toPath().resolve("pom.xml"), charset,
                    Dependency::isVirtual, profiles);
            final Set<Gav> result = tree.getModulesByGa().entrySet().stream()
                    .filter(en -> !en.getValue().getPackaging().equals("pom"))
                    .map(Map.Entry::getKey)
                    .map(ga -> new Gav(ga.getGroupId(), ga.getArtifactId(), version))
                    .collect(Collectors.toCollection(TreeSet::new));
            return Collections.unmodifiableSet(result);
        case PLATFORM_BOMS:
            final Path membersDir = basedir.toPath().resolve("generated-platform-project");
            final Set<Gav> allDeployments = new TreeSet<>();
            try (Stream<Path> members = Files.list(membersDir)) {
                members.filter(p -> !p.getFileName().toString().equals("quarkus-universe"))
                        .map(p -> p.resolve("bom/pom.xml"))
                        .filter(Files::isRegularFile)
                        .forEach(bomPomXml -> {
                            Module module = new Module.Builder(bomPomXml.getParent(), bomPomXml, charset, dep -> false).build();
                            module.getProfiles().get(0).getDependencyManagement().stream()
                                    .filter(dep -> dep.getArtifactId().asConstant().endsWith("-deployment"))
                                    .map(ga -> new Gav(ga.getGroupId().asConstant(), ga.getArtifactId().asConstant(),
                                            ga.getVersion().asConstant()))
                                    .forEach(allDeployments::add);
                        });
            } catch (IOException e) {
                throw new RuntimeException("Could not list " + membersDir, e);
            }
            return Collections.unmodifiableSet(allDeployments);
        default:
            throw new IllegalStateException("Unexpected " + RootsSourceType.class.getSimpleName() + ": " + rootsSourceType);
        }

    }

    static class Results {
        final Set<Result> results = new TreeSet<>();
        final Set<Gav> roots;

        Results(Set<Gav> roots) {
            this.roots = roots;
        }

        void add(Deque<Gavtcs> stack, String matchingResourcePath) {
            results.add(Result.of(roots, stack, matchingResourcePath));
        }

        public Set<Result> results() {
            return Collections.unmodifiableSet(results);
        }
    }

    static class Result implements Comparable<Result> {
        static Comparator<Gavtcs> GAVTCS_COMPARATOR = Gavtcs.groupFirstComparator();
        static Comparator<List<Gavtcs>> PATH_COMPARATOR = (a, b) -> {
            int size = Math.min(a.size(), b.size());
            for (int i = 0; i < size; i++) {
                int cmp = GAVTCS_COMPARATOR.compare(a.get(i), b.get(i));
                if (cmp != 0)
                    return cmp;
            }
            return Integer.compare(a.size(), b.size());
        };
        private static final Comparator<String> SAFE_STRING_COMPARATOR = (a, b) -> a == b
                ? 0
                : (a != null ? a.compareTo(b) : -1);
        static Comparator<Result> RESULT_COMPARATOR = Comparator.comparing(Result::getPath, PATH_COMPARATOR)
                .thenComparing(Result::getMatchingResourcePath, SAFE_STRING_COMPARATOR);

        final List<Gavtcs> path;
        final String matchingResourcePath;

        Result(List<Gavtcs> path, String matchingResourcePath) {
            this.path = path;
            this.matchingResourcePath = matchingResourcePath;
        }

        static Result of(Set<Gav> roots, Deque<Gavtcs> stack, String matchingResourcePath) {
            final Iterator<Gavtcs> it = stack.descendingIterator();
            final List<Gavtcs> result = new ArrayList<>(stack.size());
            while (it.hasNext()) {
                final Gavtcs g = it.next();
                if (roots.contains(new Gav(g.getGroupId(), g.getArtifactId(), g.getVersion()))) {
                    if (result.size() > 0) {
                        result.set(0, g);
                    } else {
                        result.add(g);
                    }
                } else {
                    result.add(g);
                    break;
                }
            }
            while (it.hasNext()) {
                result.add(it.next());
            }
            return new Result(Collections.unmodifiableList(result), matchingResourcePath);
        }

        @Override
        public int compareTo(Result o) {
            return RESULT_COMPARATOR.compare(this, o);
        }

        @Override
        public String toString() {
            return path.stream()
                    .map(Gavtcs::toString)
                    .collect(Collectors.joining("\n    -> "))
                    + (matchingResourcePath != null ? matchingResourcePath : "");
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + ((matchingResourcePath == null) ? 0 : matchingResourcePath.hashCode());
            result = prime * result + ((path == null) ? 0 : path.hashCode());
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            Result other = (Result) obj;
            if (matchingResourcePath == null) {
                if (other.matchingResourcePath != null)
                    return false;
            } else if (!matchingResourcePath.equals(other.matchingResourcePath))
                return false;
            if (path == null) {
                if (other.path != null)
                    return false;
            } else if (!path.equals(other.path))
                return false;
            return true;
        }

        public List<Gavtcs> getPath() {
            return path;
        }

        public String getMatchingResourcePath() {
            return matchingResourcePath;
        }

    }

}
