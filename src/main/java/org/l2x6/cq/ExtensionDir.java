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

public class ExtensionDir {
    public ExtensionDir() {
    }

    public ExtensionDir(String path, String artifactIdPrefix) {
        super();
        this.path = path;
        this.artifactIdPrefix = artifactIdPrefix;
    }

    private String path;
    private String artifactIdPrefix;

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getArtifactIdPrefix() {
        return artifactIdPrefix;
    }

    public void setArtifactIdPrefix(String artifactIdPrefix) {
        this.artifactIdPrefix = artifactIdPrefix;
    }
}