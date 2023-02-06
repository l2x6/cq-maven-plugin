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
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
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
import org.l2x6.pom.tuner.MavenSourceTree;
import org.l2x6.pom.tuner.model.Dependency;
import org.l2x6.pom.tuner.model.Gav;
import org.l2x6.pom.tuner.model.GavPattern;
import org.l2x6.pom.tuner.model.Gavtcs;
import org.l2x6.pom.tuner.model.Module;

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
     *
     *
     * @since 2.16.0
     */
    @Parameter(property = "cq.rootsSourceType")
    RootsSourceType rootsSourceType;

    public static enum RootsSourceType {
        TREE, PLATFORM_BOMS
    }

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

        listRoots()
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
                                    getLog().warn("Found "
                                            + StreamSupport
                                                    .stream(((Iterable<Gavtcs>) (() -> stack.descendingIterator()))
                                                            .spliterator(), false)
                                                    .map(Gavtcs::toString)
                                                    .collect(Collectors.joining("\n        -> ")));
                                }
                                return true;
                            }
                        });
                    } catch (DependencyCollectionException e) {
                        throw new RuntimeException("Could not resolve " + extension, e);
                    }

                });

    }

    public Stream<Gav> listRoots() {
        switch (rootsSourceType) {
        case TREE:
            final MavenSourceTree tree = MavenSourceTree.of(basedir.toPath().resolve("pom.xml"), charset,
                    Dependency::isVirtual);
            return tree.getModulesByGa().entrySet().stream()
                    .filter(en -> !en.getValue().getPackaging().equals("pom"))
                    .map(Map.Entry::getKey)
                    .map(ga -> new Gav(ga.getGroupId(), ga.getArtifactId(), version));
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
            return allDeployments.stream();
        default:
            throw new IllegalStateException("Unexpected " + RootsSourceType.class.getSimpleName() + ": " + rootsSourceType);
        }

    }

}
