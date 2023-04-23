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
package org.l2x6.cq.maven.doc.processor;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Represents an AsciiDoc file with the added capability to check and manipulate its content.
 */
public class AsciiDocFile {

    private final Path path;
    private final String cqExtension;
    private final Charset charset;
    private String content;

    public AsciiDocFile(Path path, String cqExtension, Charset charset) {
        this.path = path;
        this.cqExtension = cqExtension;
        this.charset = charset;
        this.content = load();
    }

    public String getContent() {
        return content;
    }

    public Path getPath() {
        return path;
    }

    public String getCqExtension() {
        return cqExtension;
    }

    public void append(String contentToAppend) {
        content += contentToAppend;
    }

    public boolean endsWith(String suffix) {
        return content.endsWith(suffix);
    }

    public void replace(String oldString, String newString) {
        content = content.replace(oldString, newString);
    }

    private String load() {
        try {
            return Files.readString(path, charset);
        } catch (IOException e) {
            throw new RuntimeException("Could not read " + path, e);
        }
    }
}
