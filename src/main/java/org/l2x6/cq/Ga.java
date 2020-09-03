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

import java.util.Comparator;

public class Ga implements Comparable<Ga> {
    private static final Comparator<Ga> comparator = Comparator
            .comparing(Ga::getGroupId)
            .thenComparing(Ga::getArtifactId);

    private static final Ga EXCELUDE_ALL = new Ga("*", "*");

    private final String groupId;
    private final String artifactId;

    public static Ga excludeAll() {
        return EXCELUDE_ALL;
    }

    public static Ga of(String groupId, String artifactId) {
        if ("*".equals(groupId) && "*".equals(artifactId)) {
            return EXCELUDE_ALL;
        }
        return new Ga(groupId, artifactId);
    }

    public Ga(String groupId, String artifactId) {
        this.groupId = groupId;
        this.artifactId = artifactId;
    }

    @Override
    public int compareTo(Ga other) {
        return comparator.compare(this, other);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        Ga other = (Ga) obj;
        if (artifactId == null) {
            if (other.artifactId != null)
                return false;
        } else if (!artifactId.equals(other.artifactId))
            return false;
        if (groupId == null) {
            if (other.groupId != null)
                return false;
        } else if (!groupId.equals(other.groupId))
            return false;
        return true;
    }

    public String getArtifactId() {
        return artifactId;
    }

    public String getGroupId() {
        return groupId;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((artifactId == null) ? 0 : artifactId.hashCode());
        result = prime * result + ((groupId == null) ? 0 : groupId.hashCode());
        return result;
    }

    @Override
    public String toString() {
        return groupId + ":" + artifactId;
    }
}
