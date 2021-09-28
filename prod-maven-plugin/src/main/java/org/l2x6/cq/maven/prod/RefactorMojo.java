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
package org.l2x6.cq.maven.prod;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.l2x6.pom.tuner.MavenSourceTree;
import org.l2x6.pom.tuner.MavenSourceTree.ActiveProfiles;
import org.l2x6.pom.tuner.PomTransformer;
import org.l2x6.pom.tuner.PomTransformer.SimpleElementWhitespace;
import org.l2x6.pom.tuner.PomTransformer.Transformation;
import org.l2x6.pom.tuner.PomTransformer.TransformationContext;
import org.l2x6.pom.tuner.model.Dependency;
import org.l2x6.pom.tuner.model.Ga;
import org.l2x6.pom.tuner.model.Gavtcs;
import org.l2x6.pom.tuner.model.Module;
import org.l2x6.pom.tuner.model.Profile;
import org.w3c.dom.Document;

/**
 * An ad hoc refactoring.
 */
@Mojo(name = "refactor", threadSafe = true, requiresProject = false, inheritByDefault = false)
public class RefactorMojo extends AbstractMojo {

    @Parameter(property = "cq.basedir", defaultValue = "${project.basedir}")
    File basedir;

    @Parameter(defaultValue = "utf-8", required = true, property = "cq.encoding")
    String encoding;
    Charset charset;

    @Parameter(property = "cq.simpleElementWhitespace", defaultValue = "SPACE")
    SimpleElementWhitespace simpleElementWhitespace;

    @Parameter(defaultValue = "${project.version}", readonly = true)
    String projectVersion;

    static final Pattern NAME_PATTERN = Pattern.compile("^Camel Quarkus :: ([^:]+) :: ([^:]+)$");

    static final Pattern secondLevelPattern(String firstLevel) {
        return Pattern.compile(firstLevel + "/[^/]+/pom.xml");
    }

    /** {@inheritDoc} */
    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        charset = Charset.forName(encoding);

        final Path workDir = basedir.toPath();
        final Path rootPomPath = workDir.resolve("pom.xml");
        final MavenSourceTree tree = MavenSourceTree.of(rootPomPath, charset, Dependency::isVirtual);
        final Predicate<Profile> profiles = ActiveProfiles.of();

        final Path jvmTestsDir = workDir.resolve("integration-tests-jvm");
        try {
            Files.createDirectories(jvmTestsDir);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        final Path anyJvmTestsDir = workDir.resolve("integration-tests-jvm/foo");
        final Path buildParentItPom = workDir.resolve("poms/build-parent-it/pom.xml");
        final Path buildParentPom = workDir.resolve("poms/build-parent/pom.xml");

        final Set<String> jvmTestModules = new TreeSet<>();
        final Gavtcs appBomParam = new Gavtcs("${quarkus.platform.group-id}", "${quarkus.platform.artifact-id}",
                "${quarkus.platform.version}", "pom", null, "import");
        final Gavtcs appBomLiteral = new Gavtcs("org.apache.camel.quarkus", "camel-quarkus-bom",
                "${camel-quarkus.version}", "pom", null, "import");
        final Gavtcs testBom = new Gavtcs("org.apache.camel.quarkus", "camel-quarkus-bom-test", "${camel-quarkus.version}",
                "pom", null, "import");

        Stream.of("catalog")
                .map(base -> new Ga("org.apache.camel.quarkus", "camel-quarkus-" + base))
                .forEach(ga -> {
                    Module m = tree.getModulesByGa().get(ga);
                    new PomTransformer(workDir.resolve(m.getPomPath()), charset, simpleElementWhitespace)
                            .transform(Transformation.addManagedDependency(appBomLiteral));
                });

        tree.getModulesByPath().values().stream()
                .filter(m -> m.getParentGav().getArtifactId().asConstant().equals("camel-quarkus-build-parent-it"))
                .forEach(m -> {
                    new PomTransformer(workDir.resolve(m.getPomPath()), charset, simpleElementWhitespace)
                            .transform(
                                    Transformation.removeManagedDependencies(true, true,
                                            gavtcs -> gavtcs.getArtifactId().startsWith("camel-quarkus-bom")),
                                    Transformation.addManagedDependency(appBomParam),
                                    Transformation.addManagedDependency(testBom));
                });

        tree.getModulesByPath().keySet().stream()
                .filter(path -> secondLevelPattern("extensions-jvm").matcher(path).matches())
                .map(Paths::get)
                .map(workDir::resolve)
                .forEach(jvmParentPom -> {
                    /* Remove the test module from the pom file */
                    new PomTransformer(jvmParentPom, charset, simpleElementWhitespace)
                            .transform(
                                    Transformation.removeModule(true, true, "integration-test"),
                                    Transformation.setParent("camel-quarkus-extensions-jvm", "../pom.xml"));

                    final String artifactIdBase = jvmParentPom.getParent().getFileName().toString();
                    jvmTestModules.add(artifactIdBase);

                    new PomTransformer(
                            jvmParentPom.getParent().resolve("integration-test/pom.xml"),
                            charset,
                            simpleElementWhitespace)
                                    .transform(
                                            Transformation.setParent("camel-quarkus-build-parent-it",
                                                    "../../poms/build-parent-it/pom.xml"),
                                            (Document document, TransformationContext context) -> context
                                                    .getContainerElement("project", "name")
                                                    .ifPresent(name -> {
                                                        final String oldName = name.getNode().getTextContent();
                                                        final String newName = NAME_PATTERN.matcher(oldName)
                                                                .replaceFirst("Camel Quarkus :: Integration Tests :: $1");
                                                        name.getNode().setTextContent(newName);
                                                    }),
                                            Transformation.removeManagedDependencies(true, true,
                                                    gavtcs -> gavtcs.getArtifactId().startsWith("camel-quarkus-bom")),
                                            Transformation.addManagedDependency(appBomParam),
                                            Transformation.addManagedDependency(testBom));

                    /* Move the test dir */
                    try {
                        Files.move(jvmParentPom.getParent().resolve("integration-test"), jvmTestsDir.resolve(artifactIdBase));
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });

        ProdExcludesMojo.initializeMixedTestsPom(jvmTestsDir.resolve("pom.xml"), "camel-quarkus", projectVersion,
                "../pom.xml", "camel-quarkus-integration-tests-jvm", "Integration Tests :: JVM");
        new PomTransformer(jvmTestsDir.resolve("pom.xml"), charset, simpleElementWhitespace)
                .transform(Transformation.addModulesIfNeeded(null, String::compareTo, jvmTestModules));

        for (String dir : Arrays.asList("extensions", "extensions-core", "extensions-support", "extensions-jvm",
                "integration-tests-support")) {
            final Pattern pattern = secondLevelPattern(dir);
            new PomTransformer(workDir.resolve(dir + "/pom.xml"), charset, simpleElementWhitespace)
                    .transform(
                            Transformation.setParent("camel-quarkus-build-parent",
                                    jvmTestsDir.relativize(buildParentPom).toString()),
                            Transformation.addManagedDependency(appBomLiteral));
            tree.getModulesByPath().keySet().stream()
                    .filter(path -> pattern.matcher(path).matches())
                    .map(Paths::get)
                    .map(workDir::resolve)
                    .forEach(moduleParentPom -> {
                        new PomTransformer(moduleParentPom, charset, simpleElementWhitespace)
                                .transform(
                                        Transformation.setParent("camel-quarkus-" + dir, "../pom.xml"),
                                        Transformation.removeManagedDependencies(true, true,
                                                gavtcs -> gavtcs.getArtifactId().startsWith("camel-quarkus-bom")),
                                        Transformation.removeIfEmpty(true, true, "project", "dependencyManagement",
                                                "dependencies"),
                                        Transformation.removeIfEmpty(true, true, "project", "dependencyManagement"));
                    });
        }
        new PomTransformer(workDir.resolve("integration-tests-support/pom.xml"), charset, simpleElementWhitespace)
                .transform(Transformation.addManagedDependency(testBom));

        new PomTransformer(rootPomPath, charset, simpleElementWhitespace)
                .transform(Transformation.addModule("integration-tests-jvm"));

        new PomTransformer(workDir.resolve("poms/bom-test/pom.xml"), charset, simpleElementWhitespace)
                .transform(Transformation.removeManagedDependencies(true, true,
                        gavtcs -> gavtcs.getArtifactId().startsWith("camel-quarkus-bom")));
        new PomTransformer(workDir.resolve("poms/build-parent/pom.xml"), charset, simpleElementWhitespace)
                .transform(
                        (Document document, TransformationContext context) -> context
                                .getContainerElement("project", "dependencyManagement")
                                .ifPresent(deps -> deps.remove(true, true)));
        new PomTransformer(workDir.resolve("poms/build-parent-it/pom.xml"), charset, simpleElementWhitespace)
                .transform(
                        (Document document, TransformationContext context) -> context
                                .getContainerElement("project", "dependencyManagement")
                                .ifPresent(deps -> deps.remove(true, true)));

    }

    void copyDir(final Path src, final Path dest) {
        try {
            Files.walkFileTree(src, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                        throws IOException {
                    Files.createDirectories(dest.resolve(src.relativize(dir)));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Path destFile = dest.resolve(src.relativize(file));
                    Files.copy(file, destFile, StandardCopyOption.REPLACE_EXISTING);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

}
