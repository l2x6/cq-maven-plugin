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
package org.l2x6.cq.maven.doc;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.l2x6.cq.common.CqCommonUtils;
import org.l2x6.pom.tuner.ExpressionEvaluator;
import org.l2x6.pom.tuner.MavenSourceTree;
import org.l2x6.pom.tuner.MavenSourceTree.ActiveProfiles;
import org.l2x6.pom.tuner.model.Dependency;
import org.l2x6.pom.tuner.model.Expression;
import org.l2x6.pom.tuner.model.Ga;
import org.l2x6.pom.tuner.model.Module;

/**
 * Performs the following tasks:
 * <ul>
 * <li>Deletes extension pages whose extensions do not exist anymore
 * <li>Synchronizes nav.adoc with the reality
 * <ul>
 * Intended primarily for Quarkiverse CXF. Note that there is a similar plugin in Camel Quarkus.
 *
 * @since 3.4.0
 */
@Mojo(name = "update-docs", threadSafe = true)
public class UpdateDocsMojo extends AbstractDocGeneratorMojo {

    private static final Pattern ADOC_ENDING_PATTERN = Pattern.compile("\\.adoc$");

    /**
     * The path to Antora navigation file.
     */
    @Parameter(defaultValue = "${maven.multiModuleProjectDirectory}/docs/modules/ROOT/nav.adoc")
    File navFile;

    /**
     * The extensions reference index file
     */
    @Parameter(defaultValue = "${maven.multiModuleProjectDirectory}/docs/modules/ROOT/pages/reference/index.adoc")
    File referenceIndexFile;

    /**
     * If {@code true}, the this mojo is not executed; otherwise it is executed.
     *
     * @since 3.4.0
     */
    @Parameter(defaultValue = "false", property = "cq.update-docs.skip")
    boolean skip = false;

    /**
     * Execute goal.
     *
     * @throws MojoExecutionException execution of the main class or one of the
     *                                threads it generated failed.
     * @throws MojoFailureException   something bad happened...
     */
    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {

        if (skip) {
            getLog().info("Skipping per user request");
            return;
        }

        final Path docsBasePath = docsBaseDir.toPath();

        final Set<String> artifactIds = new HashSet<>();
        final StringBuilder extLinks = new StringBuilder();
        final StringBuilder standards = new StringBuilder();

        final MavenSourceTree tree = MavenSourceTree.of(getMultiModuleProjectDirectoryPath().resolve("pom.xml"), getCharset(),
                Dependency::isVirtual);
        final ExpressionEvaluator eval = tree.getExpressionEvaluator(ActiveProfiles.of());
        tree.getModulesByPath().values().stream()
                .map(module -> new Ga(
                        eval.evaluate(module.getGav().getGroupId()),
                        eval.evaluate(module.getGav().getArtifactId())))
                .filter(ga -> ga.getArtifactId().endsWith("-deployment"))
                .map(deploymentGa -> tree.getModulesByGa()
                        .get(new Ga(deploymentGa.getGroupId(),
                                deploymentGa.getArtifactId().substring(0,
                                        deploymentGa.getArtifactId().length() - "-deployment".length()))))
                .filter(this::hasUpdateDocPageExecution)
                .forEach(runtimeModule -> {
                    final String artifactId = runtimeModule.getGav().getArtifactId().asConstant();
                    artifactIds.add(artifactId);
                    final Expression expr = runtimeModule.getProfiles().get(0).getProperties().get("cq.name");
                    final String name = expr != null ? eval.evaluate(expr) : CqCommonUtils.getNameBase(runtimeModule.getName());
                    extLinks.append("** xref:reference/extensions/" + artifactId + ".adoc[" + name + "]\n");
                    final Path standardsFile = getMultiModuleProjectDirectoryPath().resolve(runtimeModule.getPomPath())
                            .getParent().resolve("src/main/doc/standards.adoc");
                    if (Files.isRegularFile(standardsFile)) {
                        standards.append("\n| xref:reference/extensions/" + artifactId + ".adoc[" + name + "] +\n`"
                                + artifactId + "`\n|");
                        try (Stream<String> lines = Files.lines(standardsFile, getCharset())) {
                            standards.append(lines
                                    .filter(line -> line.startsWith("* "))
                                    .filter(line -> line.indexOf(']') >= 0)
                                    .map(line -> line.substring("* ".length(), line.indexOf(']') + 1))
                                    .collect(Collectors.joining(", ")))
                                    .append('\n');
                        } catch (IOException e) {
                            throw new RuntimeException("Could not read " + standardsFile);
                        }
                    }

                });

        replace(navFile.toPath(), "extensions", extLinks.toString());
        replace(referenceIndexFile.toPath(), "standards", standards.toString());

        final Path docsExtensionsDir = docsBasePath.resolve("modules/ROOT/pages/reference/extensions");
        try (Stream<Path> docPages = Files.list(docsExtensionsDir)) {
            docPages
                    .filter(docPagePath -> !artifactIds
                            .contains(ADOC_ENDING_PATTERN.matcher(docPagePath.getFileName().toString()).replaceAll("")))
                    .forEach(docPagePath -> {
                        try {
                            Files.delete(docPagePath);
                        } catch (IOException e) {
                            throw new RuntimeException("Could not delete " + docPagePath, e);
                        }
                    });
        } catch (IOException e) {
            throw new RuntimeException("Could not list " + docsExtensionsDir, e);
        }
    }

    void replace(Path path, String replacementKey, String value) {
        try {
            final String oldDocument = new String(Files.readAllBytes(path), getCharset());
            final String newDocument = replace(oldDocument, path, replacementKey, value);
            if (!oldDocument.equals(newDocument)) {
                try {
                    Files.write(path, newDocument.getBytes(getCharset()));
                } catch (IOException e) {
                    throw new RuntimeException("Could not write to " + path, e);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Could not read from " + path, e);
        }
    }

    static String replace(String document, Path documentPath, String replacementKey, String value) {
        final Pattern pat = Pattern.compile("(" + Pattern.quote("// " + replacementKey + ": START\n") + ")(.*)("
                + Pattern.quote("// " + replacementKey + ": END\n") + ")", Pattern.DOTALL);

        final Matcher m = pat.matcher(document);

        final StringBuffer sb = new StringBuffer(document.length());
        if (m.find()) {
            m.appendReplacement(sb, "$1" + Matcher.quoteReplacement(value) + "$3");
        } else {
            throw new IllegalStateException("Could not find " + pat.pattern() + " in " + documentPath + ":\n\n" + document);
        }
        m.appendTail(sb);
        return sb.toString();
    }

    boolean hasUpdateDocPageExecution(Module module) {
        final Path pomPath = getMultiModuleProjectDirectoryPath().resolve(module.getPomPath());
        try {
            final String pomContent = new String(Files.readAllBytes(pomPath), getCharset());
            return pomContent.contains("<goal>" + UpdateDocPageMojo.UPDATE_DOC_PAGE + "</goal>");
        } catch (IOException e) {
            throw new RuntimeException("Could not read " + pomPath);
        }
    }
}
