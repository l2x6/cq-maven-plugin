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
package org.l2x6.cq.common;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.maven.model.Model;

public class PomModelCache {

    private final Map<String, Model> items = new HashMap<>();
    private final Path localRepositoryPath;
    private final List<String> remoteRepositories;

    public PomModelCache(Path localRepositoryPath, List<String> remoteRepositories) {
        this.localRepositoryPath = localRepositoryPath;
        this.remoteRepositories = remoteRepositories;
    }

    public Model get(String groupId, String artifactId, String version) {
        final String key = groupId + ":" + artifactId + ":" + version;
        return items.computeIfAbsent(key, k -> {
            final Path cqPomPath = CqCommonUtils.copyArtifact(localRepositoryPath, groupId, artifactId, version,
                    "pom", remoteRepositories);
            final Model model = CqCommonUtils.readPom(cqPomPath, StandardCharsets.UTF_8);
            return model;
        });
    }

}
