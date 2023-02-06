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

import freemarker.template.Configuration;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.TransformerFactoryConfigurationError;
import javax.xml.transform.dom.DOMResult;
import javax.xml.transform.stream.StreamSource;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.l2x6.pom.tuner.PomTransformer;
import org.l2x6.pom.tuner.PomTransformer.SimpleElementWhitespace;
import org.l2x6.pom.tuner.PomTransformer.Transformation;
import org.l2x6.pom.tuner.PomTransformer.TransformationContext;
import org.w3c.dom.Document;
import org.w3c.dom.DocumentFragment;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Promotes an extension identified by {@link #artifactIdBase} from JVM-only to JVM+native state.
 */
@Mojo(name = "promote", requiresProject = true, inheritByDefault = false)
public class PromoteExtensionMojo extends AbstractMojo {

    /**
     * The unique part of the {@link #artifactId} of the extension to promote.
     *
     * @since 0.10.0
     */
    @Parameter(property = "cq.artifactIdBase", required = true)
    String artifactIdBase;

    /**
     * Encoding to read and write files in the current source tree
     *
     * @since 0.10.0
     */
    @Parameter(defaultValue = CqUtils.DEFAULT_ENCODING, required = true, property = "cq.encoding")
    String encoding;

    /**
     * The root directory of the Camel Quarkus source tree.
     *
     * @since 0.10.0
     */
    @Parameter(defaultValue = "${maven.multiModuleProjectDirectory}", readonly = true)
    File multiModuleProjectDirectory;

    /**
     * The directory where the extension should be moved, relative to {@link #multiModuleProjectDirectory}.
     *
     * @since 0.10.0
     */
    @Parameter(property = "cq.extensionsDir", defaultValue = "extensions")
    String extensionsDir;

    /**
     * URI prefix to use when looking up FreeMarker templates when generating various source files.
     *
     * @since 0.10.0
     */
    @Parameter(defaultValue = CqUtils.DEFAULT_TEMPLATES_URI_BASE, required = true, property = "cq.templatesUriBase")
    String templatesUriBase;

    /**
     * The Camel Quarkus version, expected to be something like 1.2.0-SNAPSHOT.
     */
    @Parameter(defaultValue = "${project.version}", required = true, readonly = true)
    String camelQuarkusVersion;

    /**
     * How to format simple XML elements ({@code <elem/>}) - with or without space before the slash.
     *
     * @since 0.38.0
     */
    @Parameter(property = "cq.simpleElementWhitespace", defaultValue = "EMPTY")
    SimpleElementWhitespace simpleElementWhitespace;

    private final static Pattern RELATIVE_PATH_PATTERN = Pattern.compile("[ \t\r\n]*<relativePath>([^<]+)</relativePath>");
    private final static Pattern NAME_PATTERN = Pattern.compile("<name>Camel Quarkus :: ([^<]+) :: Integration Test</name>");
    private final static Pattern ARTIFACT_ID_PATTERN = Pattern
            .compile("<artifactId>camel-quarkus-([^<]+)-integration-test</artifactId>");

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        final Charset charset = Charset.forName(encoding);
        final Path sourceRootPath = multiModuleProjectDirectory.toPath().toAbsolutePath().normalize();
        final Path extensionsPath = sourceRootPath.resolve(extensionsDir);
        if (!Files.isDirectory(extensionsPath)) {
            throw new RuntimeException("The parameter 'extensionsPath' does not point to a directory: " + extensionsPath);
        }
        final Path destParentDir = extensionsPath.resolve(artifactIdBase);
        final Path destParentPomPath = destParentDir.resolve("pom.xml");
        if (Files.isRegularFile(destParentPomPath)) {
            throw new RuntimeException("The destination pom.xml file exists. Nothing to do? : " + destParentPomPath);
        }

        final Path srcParentDir = sourceRootPath.resolve("extensions-jvm/" + artifactIdBase);
        final Path jvmTestsDir = sourceRootPath.resolve("integration-tests-jvm");
        if (!Files.exists(srcParentDir)) {
            throw new RuntimeException(
                    "The directory of the extension to promote does not exist. Maybe a typo in the artifactIdBase parameter? "
                            + srcParentDir);
        }

        /* Move the test */
        final Path srcItestDir = jvmTestsDir.resolve(artifactIdBase);
        final Path destItestDir = sourceRootPath.resolve("integration-tests/" + artifactIdBase);
        try {
            Files.move(srcItestDir, destItestDir, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new RuntimeException("Could not move '" + srcItestDir + "' to '" + destItestDir + "'", e);
        }

        /* Remove the test module from the extension parent */
        final Path srcParentPomPath = jvmTestsDir.resolve("pom.xml");
        new PomTransformer(srcParentPomPath, charset, simpleElementWhitespace)
                .transform(Transformation.removeModule(true, true, artifactIdBase));

        /* Adjust the names in the test POM */
        adjustTestPom(artifactIdBase, destItestDir.resolve("pom.xml"), charset, templatesUriBase, simpleElementWhitespace);

        /* Add the test module to its new parent module */
        final Path integrationTestsPomPath = sourceRootPath.resolve("integration-tests/pom.xml");
        new PomTransformer(integrationTestsPomPath, charset, simpleElementWhitespace)
                .transform(Transformation.addModule(artifactIdBase));
        PomSorter.sortModules(integrationTestsPomPath);

        /* Move the extension */
        try {
            Files.move(srcParentDir, destParentDir, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new RuntimeException("Could not move '" + srcParentDir + "' to '" + destParentDir + "'", e);
        }

        /* Remove the extension module from the extensions-jvm POM */
        final Path extensionsJvmPomPath = sourceRootPath.resolve("extensions-jvm/pom.xml");
        new PomTransformer(extensionsJvmPomPath, charset, simpleElementWhitespace)
                .transform(Transformation.removeModule(false, true, artifactIdBase));

        /* Add the extension module to its new parent module */
        final Path destExtensionsPomPath = extensionsPath.resolve("pom.xml");
        new PomTransformer(destExtensionsPomPath, charset, simpleElementWhitespace)
                .transform(Transformation.addModule(artifactIdBase));
        PomSorter.sortModules(destExtensionsPomPath);

        /* Set the camel.quarkus.nativeSince property in the runtime POM */
        final Path runtimePomPath = destParentDir.resolve("runtime/pom.xml");
        final String camelQuarkusNativeSinceVersion = camelQuarkusVersion.replaceAll("-SNAPSHOT", "");
        Transformation addNativeSinceProperty = Transformation.addProperty("camel.quarkus.nativeSince",
                camelQuarkusNativeSinceVersion);
        new PomTransformer(runtimePomPath, charset, simpleElementWhitespace).transform(addNativeSinceProperty);

        // Remove the warning build step from
        // extensions/${EXT}/deployment/src/main/java/org/apache/camel/quarkus/component/${EXT}/deployment/${EXT}Processor.java:
        final String javaPackage = CqUtils.getJavaPackage("org.apache.camel.quarkus", CreateExtensionMojo.CQ_JAVA_PACKAGE_INFIX,
                artifactIdBase);
        final String artifactIdBaseCapCamelCase = CqUtils.toCapCamelCase(artifactIdBase);
        createNativeTest(sourceRootPath, javaPackage, artifactIdBaseCapCamelCase);
        adjustProcessor(extensionsPath, javaPackage, artifactIdBaseCapCamelCase, charset);
    }

    void adjustProcessor(Path extensionsDir, String javaPackage, String artifactIdBaseCapCamelCase, Charset charset) {
        final Path processorPath = extensionsDir.resolve(artifactIdBase + "/deployment/src/main/java/"
                + javaPackage.replace('.', '/') + "/deployment/" + artifactIdBaseCapCamelCase + "Processor.java");
        if (!Files.exists(processorPath)) {
            throw new RuntimeException("Could not find processor to remove the native warning: " + processorPath);
        }
        try {
            String src = new String(Files.readAllBytes(processorPath), charset);
            final Pattern pat = Pattern.compile(
                    "\\s*\\Q/**\\E\\s*\\Q* Remove this \\E[^}]+\\Q}\\E", Pattern.DOTALL);
            src = pat.matcher(src).replaceFirst("");
            Files.write(processorPath, src.getBytes(charset));
        } catch (IOException e) {
            throw new RuntimeException("Could not read or write " + processorPath);
        }
    }

    void createNativeTest(Path sourceRootPath, String javaPackage, String artifactIdBaseCapCamelCase) {

        final Configuration cfg = CqUtils.getTemplateConfig(sourceRootPath, CqUtils.DEFAULT_TEMPLATES_URI_BASE,
                templatesUriBase, encoding);

        final TemplateParams templateParams = TemplateParams.builder()
                .artifactIdBase(artifactIdBase)
                .javaPackageBase(javaPackage)
                .build();
        final Path testClassDir = sourceRootPath
                .resolve("integration-tests/" + artifactIdBase + "/src/test/java/" + templateParams.getJavaPackageBasePath()
                        + "/it");
        CqUtils.evalTemplate(cfg, "IT.java", testClassDir.resolve(artifactIdBaseCapCamelCase + "IT.java"), templateParams,
                m -> getLog().info(m));

    }

    static void adjustTestPom(String baseArtifactId, Path path, Charset charset, String templatesUriBase,
            SimpleElementWhitespace simpleElementWhitespace) {
        /* Add the native profile at the end of integration-tests/${EXT}/pom.xml: */
        final DocumentFragment nativeProfile = loadNativeProfile(charset, templatesUriBase + "/integration-test-pom.xml");
        new PomTransformer(path, charset, simpleElementWhitespace)
                .transform(Transformation.addFragment(nativeProfile, "profiles"));

    }

    static DocumentFragment loadNativeProfile(Charset charset, String uri) {
        final URL url;
        if (uri.startsWith(CqUtils.CLASSPATH_PREFIX)) {
            final String resourcePath = uri.substring(CqUtils.CLASSPATH_PREFIX.length());
            url = PromoteExtensionMojo.class.getResource(resourcePath);
        } else if (uri.startsWith(CqUtils.FILE_PREFIX)) {
            final String path = uri.substring(CqUtils.FILE_PREFIX.length());
            try {
                url = Paths.get(path).toUri().toURL();
            } catch (MalformedURLException e) {
                throw new RuntimeException(e);
            }
        } else {
            throw new IllegalStateException(
                    "Expected an uri starting with " + CqUtils.CLASSPATH_PREFIX + " or " + CqUtils.FILE_PREFIX);
        }
        final StringBuilder sb = new StringBuilder();
        try (Reader r = new InputStreamReader(url.openStream(), charset)) {
            int len;
            char[] buf = new char[1024];
            while ((len = r.read(buf)) >= 0) {
                sb.append(buf, 0, len);
            }
        } catch (IOException e) {
            throw new RuntimeException("Could not read " + uri);
        }
        final String src = sb.toString();
        final Pattern pattern = Pattern.compile("<profiles>.*</profiles>", Pattern.DOTALL);
        final Matcher m = pattern.matcher(src);
        if (m.find()) {

            final String profilesSource = m.group().replace("<profiles>",
                    "<profiles xmlns=\"http://maven.apache.org/POM/4.0.0\">");
            final Document document;
            try {
                final DOMResult domResult = new DOMResult();
                TransformerFactory.newInstance().newTransformer()
                        .transform(new StreamSource(new StringReader(profilesSource)), domResult);
                document = (Document) domResult.getNode();

                Element profiles = document.getDocumentElement();
                final NodeList children = profiles.getChildNodes();
                final DocumentFragment result = document.createDocumentFragment();
                while (children.getLength() > 0) {
                    result.appendChild(children.item(0));
                }
                final Node lastChild = result.getLastChild();
                if (TransformationContext.isWhiteSpaceNode(lastChild)) {
                    result.removeChild(lastChild);
                }
                return result;
            } catch (TransformerException | TransformerFactoryConfigurationError e) {
                throw new RuntimeException(String.format("Could not read DOM from [%s]", profilesSource), e);
            }

        } else {
            throw new IllegalStateException("Could not find " + pattern.pattern() + " in " + uri);
        }
    }

}
