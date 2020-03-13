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
package org.l2x6.cq;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * A script for sorting child modules and dependencyManagement dependencies in pom.xml files.
 * Only elements will be sorted that occur after a comment containing the {@code a..z} marker string.
 */

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.xml.namespace.QName;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.TransformerFactoryConfigurationError;
import javax.xml.transform.dom.DOMResult;
import javax.xml.transform.stream.StreamSource;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

public class PomSorter {

    public static final List<ExtensionDir> CQ_EXTENSIONS_DIRECTORIES = Collections.unmodifiableList(Arrays.asList(
            new ExtensionDir("extensions", "camel-quarkus-"),
            new ExtensionDir("extensions-core", "camel-quarkus-"),
            new ExtensionDir("extensions-jvm", "camel-quarkus-"),
            new ExtensionDir("extensions-support", "camel-quarkus-support-"),
            new ExtensionDir("integration-tests/support", "camel-quarkus-integration-test-support-")
    ));

    private static final Pattern dependenciesPattern = Pattern.compile("([^\n<]*)<dependenc");
    private static final Pattern propsPattern = Pattern.compile("([^\n<]*)</properties>");
    private static final Pattern rulePattern = Pattern.compile("<mvnd.builder.rule>[^<]*</mvnd.builder.rule>");

    public static void sortDependencyManagement(Path baseDir, List<String> pomPaths) {
        for (String pomPath : pomPaths) {
            final Path pomXmlPath = baseDir.resolve(pomPath.trim());
            sortDependencyManagement(pomXmlPath);
        }
    }

    public static void sortDependencyManagement(final Path pomXmlPath) {
        final String xmlSource = read(pomXmlPath);

        final Pattern sortSpanPattern = Pattern
                .compile("(a\\.\\.z[^>]*>)(.*)</dependencies>(\\r?\\n)([ ]*)</dependencyManagement>", Pattern.DOTALL);
        final Pattern groupIdPattern = Pattern.compile("<groupId>([^<]+)</groupId>");

        final Matcher matcher = sortSpanPattern.matcher(xmlSource);
        if (matcher.find()) {
            String dependenciesString = matcher.group(2);
            final String eol = matcher.group(3);
            final String indent = matcher.group(4);

            dependenciesString = dependenciesString.replaceAll("<!--\\$[^>]*\\$-->", "");
            final String[] dependenciesArray = dependenciesString.split("</dependency>");
            /* Sort by adding to a TreeMap */
            final Map<String, Map<String, String>> sortedDeps = new TreeMap<>();
            for (String dep : dependenciesArray) {
                dep = dep.trim();
                if (!dep.isEmpty()) {
                    String key = dep
                            .replaceAll(">[ \n\r\t]+", ">")
                            .replaceAll("[ \n\r\t]+<", "<");
                    final Matcher gMatcher = groupIdPattern.matcher(key);
                    gMatcher.find();
                    final String groupId = gMatcher.group(1);
                    key = key.replaceAll("<[^>]+>", " ").replaceAll(" +", " ");

                    Map<String, String> groupMap = sortedDeps.get(groupId);
                    if (groupMap == null) {
                        groupMap = new TreeMap<String, String>();
                        sortedDeps.put(groupId, groupMap);
                    }
                    groupMap.put(key, dep);
                }
            }
            final StringBuilder result = new StringBuilder(xmlSource);
            result.setLength(matcher.end(1));

            final Appender appender = new Appender(eol, indent, sortedDeps, result);

            appender.appendGroup("org.apache.camel", true);
            appender.appendGroup("org.apache.camel.quarkus", true);

            appender.appendOther();
            appender.result().append(eol).append(indent).append(indent).append(xmlSource.substring(matcher.end(2)));

            write(pomXmlPath, result.toString());
        } else {
            throw new RuntimeException("Could not match " + sortSpanPattern + " in " + pomXmlPath);
        }
    }

    public static void sortModules(Path baseDir, List<String> sortModulesPaths) {
        for (String pomPath : sortModulesPaths) {
            final Path pomXmlPath = baseDir.resolve(pomPath.trim());
            sortModules(pomXmlPath);
        }
    }

    public static void sortModules(final Path pomXmlPath) {
        final String xmlSource = read(pomXmlPath);

        final Pattern sortSpanPattern = Pattern.compile("(a\\.\\.z[^>]*>)(.*)(\\r?\\n)([ ]*)</modules>", Pattern.DOTALL);

        final Matcher matcher = sortSpanPattern.matcher(xmlSource);
        if (matcher.find()) {
            final String modulesString = matcher.group(2);
            final String eol = matcher.group(3);
            final String indent = matcher.group(4);
            final String[] modulesArray = modulesString.split("[\r\n]+ *");
            final Map<String, String> sortedModules = new TreeMap<String, String>();
            for (String module : modulesArray) {
                module = module.trim();
                if (!module.isEmpty()) {
                    String key = module
                            .replaceAll(">[ \n\r\t]+", ">")
                            .replaceAll("[ \n\r\t]+<", "<");
                    key = key.replaceAll("<[^>]+>", "");
                    if (!key.isEmpty()) {
                        sortedModules.put(key, module);
                    }
                }
            }

            final StringBuilder result = new StringBuilder(xmlSource);
            result.setLength(matcher.end(1));
            for (String module : sortedModules.values()) {
                result.append(eol).append(indent).append(indent).append(module);
            }
            result.append(eol).append(indent).append(xmlSource.substring(matcher.end(4)));

            write(pomXmlPath, result.toString());
        } else {
            throw new RuntimeException("Could not match " + sortSpanPattern + " in " + pomXmlPath);
        }
    }

    public static void updateMvndRules(Path baseDir, List<String> updateMvndRuleDirs, Set<String> extensionArtifactIds) {

        for (String updateMvndRuleDir : updateMvndRuleDirs) {
            safeList(baseDir.resolve(updateMvndRuleDir))
                    .filter(p -> Files.isDirectory(p) && !"support".equals(p.getFileName().toString()))
                    .sorted()
                    .map(p -> p.resolve("pom.xml"))
                    .filter(p -> Files.exists(p))
                    .forEach(pomXmlPath -> {
                        updateMvndRules(baseDir, pomXmlPath, extensionArtifactIds);
                    });
        }
    }

    public static void updateMvndRules(Path baseDir, Path pomXmlPath, Set<String> extensionArtifactIds) {
        final XPath xPath = XPathFactory.newInstance().newXPath();
        final Path relativePomPath = baseDir.relativize(pomXmlPath);
        String pomXmlText = read(pomXmlPath);
        final Document pomXmlProject = parse(pomXmlPath);
        /* Policy may disappear at some point */
        final boolean policyExtensionExists = extensionArtifactIds.contains("camel-quarkus-support-policy");

        final List<String> extensionDependencies = evalStream(
                xPath,
                PomTransformer.anyNs("project", "dependencies", "dependency"),
                pomXmlProject)
                        .filter(dep -> "org.apache.camel.quarkus"
                                .equals(eval(xPath, "./" + PomTransformer.anyNs("groupId") + "/text()", dep, String.class)))
                        .map(dep -> eval(xPath, "./" + PomTransformer.anyNs("artifactId") + "/text()", dep, String.class))
                        .filter(artifactId -> extensionArtifactIds.contains(artifactId))
                        .map(artifactId -> artifactId + "-deployment")
                        .collect(Collectors.toList());
        if (policyExtensionExists) {
            extensionDependencies.add("camel-quarkus-support-policy-deployment");
        }

        final String expectedRule = extensionDependencies.stream()
                .sorted()
                .collect(Collectors.joining(","));

        final Matcher depsMatcher = dependenciesPattern.matcher(pomXmlText);
        if (depsMatcher.find()) {
            final String indent = depsMatcher.group(1);
            final int insertionPos = depsMatcher.start();

            final NodeList props = eval(xPath, PomTransformer.anyNs("project", "properties"), pomXmlProject, NodeList.class);
            if (props.getLength() == 0) {
                final String insert = indent + "<properties>\n" +
                        rule(expectedRule, indent) +
                        indent + "</properties>\n\n";
                pomXmlText = new StringBuilder(pomXmlText).insert(insertionPos, insert).toString();
                write(pomXmlPath, pomXmlText);
            } else {
                final NodeList mvndRule = eval(xPath, "." + PomTransformer.anyNs("mvnd.builder.rule"), props.item(0),
                        NodeList.class);
                if (mvndRule.getLength() == 0) {
                    final Matcher propsMatcher = propsPattern.matcher(pomXmlText);
                    if (propsMatcher.find()) {
                        final int insPos = propsMatcher.start();
                        final String insert = rule(expectedRule, indent);
                        pomXmlText = new StringBuilder(pomXmlText).insert(insPos, insert).toString();
                        write(pomXmlPath, pomXmlText);
                    } else {
                        throw new IllegalStateException(
                                "Could not find " + propsPattern.pattern() + " in " + relativePomPath);
                    }
                } else {
                    String actualRule = eval(xPath, "./text()", mvndRule.item(0), String.class);
                    actualRule = Stream.of(actualRule.split(",")).sorted().collect(Collectors.joining(","));
                    if (!expectedRule.equals(actualRule)) {
                        final Matcher ruleMatcher = rulePattern.matcher(pomXmlText);
                        if (ruleMatcher.find()) {
                            final StringBuffer buf = new StringBuffer(pomXmlText.length() + 128);
                            final String replacement = "<mvnd.builder.rule>" + expectedRule
                                    + "</mvnd.builder.rule>";
                            ruleMatcher.appendReplacement(buf, Matcher.quoteReplacement(replacement));
                            ruleMatcher.appendTail(buf);
                            write(pomXmlPath, pomXmlText);
                        } else {
                            throw new IllegalStateException(
                                    "Could not find " + rulePattern.pattern() + " in " + relativePomPath);
                        }
                    }
                }
            }

        } else {
            throw new IllegalStateException(
                    "Could not find " + dependenciesPattern.pattern() + " in " + relativePomPath);
        }
    }

    private static String rule(String expectedRule, String indent) {
        return indent + indent + "<!-- mvnd, a.k.a. Maven Daemon: https://github.com/gnodet/mvnd -->\n"
                +
                indent + indent
                + "<!-- The following rule tells mvnd to build the listed deployment modules before this module. -->\n"
                +
                indent + indent
                + "<!-- This is important because mvnd builds modules in parallel by default. The deployment modules are not -->\n"
                +
                indent + indent
                + "<!-- explicit dependencies of this module in the Maven sense, although they are required by the Quarkus Maven plugin. -->\n"
                +
                indent + indent
                + "<!-- Please update rule whenever you change the dependencies of this module by running -->\n"
                +
                indent + indent
                + "<!--     mvn process-resources -Pformat    from the root directory -->\n" +
                indent + indent + "<mvnd.builder.rule>" + expectedRule + "</mvnd.builder.rule>\n";
    }

    public static Set<String> findExtensionArtifactIds(Path baseDir, List<ExtensionDir> extensionDirs) {
        final Set<String> extensionArtifactIds = new TreeSet<>();
        for (ExtensionDir extDir : extensionDirs) {
            final Path absPath = baseDir.resolve(extDir.getPath());
            try {
                Files.list(absPath)
                        .filter(path -> Files.isDirectory(path)
                                && Files.exists(path.resolve("pom.xml"))
                                && Files.exists(path.resolve("runtime")))
                        .map(dir -> extDir.getArtifactIdPrefix() + dir.getFileName().toString())
                        .forEach(extensionArtifactIds::add);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        return extensionArtifactIds;
    }

    static void write(final Path path, final String content) {
        try {
            Files.write(path, content.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new RuntimeException("Could not write " + path, e);
        }
    }

    static String read(final Path path) {
        try {
            return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Could not read " + path, e);
        }
    }

    static Stream<Path> safeList(Path extensionsDir) {
        try {
            return Files.list(extensionsDir);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    static Document parse(Path pomXmlPath) {
        try (Reader r = Files.newBufferedReader(pomXmlPath, StandardCharsets.UTF_8)) {
            final DOMResult result = new DOMResult();
            TransformerFactory.newInstance().newTransformer().transform(new StreamSource(r), result);
            return (Document) result.getNode();
        } catch (IOException | TransformerException | TransformerFactoryConfigurationError e) {
            throw new RuntimeException("Could not parse " + pomXmlPath, e);
        }
    }

    static Stream<Node> evalStream(XPath xPath, String xPathExpression, Node parent) {
        try {
            final NodeList nodes = (NodeList) xPath.evaluate(xPathExpression, parent, XPathConstants.NODESET);
            final List<Node> result = new ArrayList<>(nodes.getLength());
            for (int i = 0; i < nodes.getLength(); i++) {
                final Node n = nodes.item(i);
                result.add(n);
            }
            return result.stream();
        } catch (XPathExpressionException e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unchecked")
    static <T> T eval(XPath xPath, String xPathExpression, Node parent, Class<T> type) {
        final QName t;
        if (type == NodeList.class) {
            t = XPathConstants.NODESET;
        } else if (type == String.class) {
            t = XPathConstants.STRING;
        } else if (type == Boolean.class) {
            t = XPathConstants.BOOLEAN;
        } else if (type == Node.class) {
            t = XPathConstants.NODE;
        } else {
            throw new IllegalStateException();
        }

        try {
            return (T) xPath.evaluate(xPathExpression, parent, t);
        } catch (XPathExpressionException e) {
            throw new RuntimeException(e);
        }
    }

    static class Appender {
        private final Set<String> processedGroupIds = new HashSet<>();
        private final String eol;
        private final String indent;
        private final Map<String, Map<String, String>> sortedDeps;
        private final StringBuilder result;

        public Appender(String eol, String indent, Map<String, Map<String, String>> sortedDeps, StringBuilder result) {
            this.eol = eol;
            this.indent = indent;
            this.sortedDeps = sortedDeps;
            this.result = result;
        }

        public void comment(String comment) {
            result.append(eol).append(eol)
                    .append(indent).append(indent).append(indent).append("<!--$ " + comment + " $-->");
        }

        public void appendGroup(String groupId, boolean isComment) {
            final Map<String, String> deps = sortedDeps.get(groupId);
            if (deps == null || processedGroupIds.contains(groupId)) {
                return;
            }
            processedGroupIds.add(groupId);
            if (isComment) {
                comment(groupId);
            }
            for (String dep : deps.values()) {
                result.append(eol)
                        .append(indent).append(indent).append(indent).append(dep)
                        .append(eol).append(indent).append(indent).append(indent).append("</dependency>");
            }
        }

        public void appendOther() {
            if (processedGroupIds.size() < sortedDeps.size()) {
                comment("Other third party dependencies");
                for (Entry<String, Map<String, String>> group : sortedDeps.entrySet()) {
                    appendGroup(group.getKey(), false);
                }
            }
        }

        public StringBuilder result() {
            return result;
        }
    }

}
