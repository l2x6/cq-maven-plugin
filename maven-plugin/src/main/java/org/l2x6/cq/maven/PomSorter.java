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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

public class PomSorter {

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
            boolean inComment = false;
            for (String dep : dependenciesArray) {
                dep = dep.trim();
                if (!dep.isEmpty()) {
                    if (dep.startsWith("-->")) {
                        dep = dep.replaceAll("-->[ \n\r\t]+", "");
                        inComment = false;
                    }
                    if (dep.matches("<!--[ \n\r\t]*<dependency>(?s).*")) {
                        inComment = true;
                    } else if (inComment) {
                        dep = "<!--" + dep;
                    }
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

        Matcher matcher = sortSpanPattern.matcher(xmlSource);
        if (!matcher.find()) {
            final Pattern fallbackSortSpanPattern = Pattern.compile("(<modules>)(.*)(\\r?\\n)([ ]*)</modules>", Pattern.DOTALL);
            matcher = fallbackSortSpanPattern.matcher(xmlSource);
            if (!matcher.find()) {
                throw new RuntimeException(
                        "Could not match " + sortSpanPattern + " nor " + fallbackSortSpanPattern + " in " + pomXmlPath);
            }
        }
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

            String[] depArray = new String[deps.values().size()];
            deps.values().toArray(depArray);
            boolean inComment = false;
            for (int i = 0; i < depArray.length; i++) {
                String dep = depArray[i];

                if (dep.matches("<!--[ \n\r\t]*<dependency>(?s).*")) {
                    if (inComment) {
                        // Remove opening comment if already in comment block
                        dep = dep.replace("<!--", "");
                    } else {
                        inComment = true;
                    }
                }

                // Write out dependency
                result.append(eol)
                        .append(indent).append(indent).append(indent).append(dep)
                        .append(eol).append(indent).append(indent).append(indent).append("</dependency>");

                if (inComment) {
                    String nextDep = i < depArray.length - 1 ? depArray[i + 1] : null;
                    // Close the comment when we reach the last dependency or one not commented out.
                    if (nextDep == null || !nextDep.matches("<!--[ \n\r\t]*<dependency>(?s).*")) {
                        result.append("-->");
                        inComment = false;
                    }
                }
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
