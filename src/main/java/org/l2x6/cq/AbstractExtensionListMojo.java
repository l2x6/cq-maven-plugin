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

import java.io.File;
import java.util.List;
import java.util.Set;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugins.annotations.Parameter;

public abstract class AbstractExtensionListMojo extends AbstractMojo {

    /**
     * List of directories that contain extensions
     *
     * @since 0.1.0
     */
    @Parameter(property = "cq.extensionDirectories", required = true)
    protected List<File> extensionDirectories;

    /**
     * A set of artifactIdBases that are nor extensions and should not be processed by this mojo.
     *
     * @since 0.1.0
     */
    @Parameter(property = "cq.skipArtifactIdBases")
    protected Set<String> skipArtifactIdBases;

    final CqCatalog catalog = new CqCatalog();


}
