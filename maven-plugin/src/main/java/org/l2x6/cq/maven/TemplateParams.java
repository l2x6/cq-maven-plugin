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

import freemarker.template.TemplateMethodModelEx;
import freemarker.template.TemplateModelException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.camel.tooling.model.ArtifactModel;
import org.apache.camel.tooling.model.Kind;
import org.l2x6.cq.common.ExtensionStatus;
import org.l2x6.pom.tuner.model.Gavtcs;

public class TemplateParams {

    private final boolean nativeSupported;
    private final boolean unlisted;
    private final boolean deprecated;
    private final String itestParentRelativePath;
    private final String itestParentVersion;
    private final String itestParentArtifactId;
    private final String itestParentGroupId;
    private final String groupId;
    private final String artifactId;
    private final String artifactIdPrefix;
    private final String artifactIdBase;
    private final String version;
    private final String namePrefix;
    private final String nameBase;
    private final String nameSegmentDelimiter;
    private final String javaPackageBase;
    private final String quarkusVersion;
    private final List<Gavtcs> additionalRuntimeDependencies;
    private final boolean runtimeBomPathSet;
    private final String bomEntryVersion;
    private final String description;
    private final Set<String> configPrefixes;
    private final List<String> keywords;
    private final String guideUrl;
    private final List<String> categories;
    private final Kind kind;
    private final List<ArtifactModel<?>> models;
    private final ExtensionStatus status;
    private final TemplateMethodModelEx toCapCamelCase = new TemplateMethodModelEx() {
        @Override
        public Object exec(List arguments) throws TemplateModelException {
            if (arguments.size() != 1) {
                throw new TemplateModelException("Wrong argument count in toCamelCase()");
            }
            return CqUtils.toCapCamelCase(String.valueOf(arguments.get(0)));
        }
    };
    private final TemplateMethodModelEx toSnakeCase = new TemplateMethodModelEx() {
        @Override
        public Object exec(List arguments) throws TemplateModelException {
            if (arguments.size() != 1) {
                throw new TemplateModelException("Wrong argument count in toCamelCase()");
            }
            return CqUtils.toSnakeCase(String.valueOf(arguments.get(0)));
        }
    };
    private final TemplateMethodModelEx toKebabCase = new TemplateMethodModelEx() {
        @Override
        public Object exec(List arguments) throws TemplateModelException {
            if (arguments.size() != 1) {
                throw new TemplateModelException("Wrong argument count in toKebabCase()");
            }
            return CqUtils.toKebabCase(String.valueOf(arguments.get(0)));
        }
    };

    public static Builder builder() {
        return new Builder();
    }

    private TemplateParams(Builder builder) {
        this.nativeSupported = builder.nativeSupported;
        this.unlisted = builder.unlisted;
        this.deprecated = builder.deprecated;
        this.itestParentRelativePath = builder.itestParentRelativePath;
        this.itestParentVersion = builder.itestParentVersion;
        this.itestParentArtifactId = builder.itestParentArtifactId;
        this.itestParentGroupId = builder.itestParentGroupId;
        this.groupId = builder.groupId;
        this.artifactId = builder.artifactId;
        this.artifactIdPrefix = builder.artifactIdPrefix;
        this.artifactIdBase = builder.artifactIdBase;
        this.version = builder.version;
        this.namePrefix = builder.namePrefix;
        this.nameBase = builder.nameBase;
        this.nameSegmentDelimiter = builder.nameSegmentDelimiter;
        this.javaPackageBase = builder.javaPackageBase;
        this.quarkusVersion = builder.quarkusVersion;
        this.additionalRuntimeDependencies = new ArrayList<>(builder.additionalRuntimeDependencies);
        this.runtimeBomPathSet = builder.runtimeBomPathSet;
        this.bomEntryVersion = builder.bomEntryVersion;
        this.description = builder.description;
        this.keywords = new ArrayList<>(builder.keywords);
        this.guideUrl = builder.guideUrl;
        this.categories = new ArrayList<>(builder.categories);
        this.kind = builder.kind;
        this.status = builder.status != null ? builder.status : ExtensionStatus.stable;
        this.models = new ArrayList<>(builder.models);
        this.configPrefixes = builder.configPrefixes;
    }

    public boolean isNativeSupported() {
        return nativeSupported;
    }

    public String getJavaPackageBase() {
        return javaPackageBase;
    }

    public String getArtifactIdPrefix() {
        return artifactIdPrefix;
    }

    public String getArtifactIdBase() {
        return artifactIdBase;
    }

    public String getNamePrefix() {
        return namePrefix;
    }

    public String getNameBase() {
        return nameBase;
    }

    public String getNameSegmentDelimiter() {
        return nameSegmentDelimiter;
    }

    public String getQuarkusVersion() {
        return quarkusVersion;
    }

    public String getGroupId() {
        return groupId;
    }

    public String getVersion() {
        return version;
    }

    public String getArtifactId() {
        return artifactId;
    }

    public List<Gavtcs> getAdditionalRuntimeDependencies() {
        return additionalRuntimeDependencies;
    }

    public boolean isRuntimeBomPathSet() {
        return runtimeBomPathSet;
    }

    public String getItestParentRelativePath() {
        return itestParentRelativePath;
    }

    public String getItestParentVersion() {
        return itestParentVersion;
    }

    public String getItestParentArtifactId() {
        return itestParentArtifactId;
    }

    public String getItestParentGroupId() {
        return itestParentGroupId;
    }

    public String getBomEntryVersion() {
        return bomEntryVersion;
    }

    public String getDescription() {
        return description;
    }

    public Set<String> getConfigPrefixes() {
        return configPrefixes;
    }

    public List<String> getKeywords() {
        return keywords;
    }

    public String getGuideUrl() {
        return guideUrl;
    }

    public List<String> getCategories() {
        return categories;
    }

    public Kind getKind() {
        return kind;
    }

    public List<ArtifactModel<?>> getModels() {
        return models;
    }

    public TemplateMethodModelEx getToCapCamelCase() {
        return toCapCamelCase;
    }

    public TemplateMethodModelEx getToSnakeCase() {
        return toSnakeCase;
    }

    public TemplateMethodModelEx getToKebabCase() {
        return toKebabCase;
    }

    public String getJavaPackageBasePath() {
        return javaPackageBase.replace('.', '/');
    }

    public boolean isUnlisted() {
        return unlisted;
    }

    public boolean isDeprecated() {
        return deprecated;
    }

    public static class Builder {
        public ExtensionStatus status;
        private boolean nativeSupported;
        private String itestParentRelativePath;
        private String itestParentVersion;
        private String itestParentArtifactId;
        private String itestParentGroupId;
        private String groupId;
        private String artifactId;
        private String artifactIdPrefix;
        private String artifactIdBase;
        private String version;
        private String namePrefix;
        private String nameBase;
        private String nameSegmentDelimiter;
        private String javaPackageBase;
        private String quarkusVersion;
        private List<Gavtcs> additionalRuntimeDependencies = new ArrayList<>();
        private boolean runtimeBomPathSet;
        private String bomEntryVersion;
        private String description;
        private Set<String> configPrefixes;
        private Collection<String> keywords = new ArrayList<>();
        private String guideUrl;
        private List<String> categories = new ArrayList<>();
        private Kind kind;
        private List<ArtifactModel<?>> models = new ArrayList<>();
        private boolean unlisted;
        private boolean deprecated = false;

        public Builder nativeSupported(boolean nativeSupported) {
            this.nativeSupported = nativeSupported;
            return this;
        }

        public Builder itestParentRelativePath(String itestParentRelativePath) {
            this.itestParentRelativePath = itestParentRelativePath;
            return this;
        }

        public Builder itestParentVersion(String itestParentVersion) {
            this.itestParentVersion = itestParentVersion;
            return this;
        }

        public Builder itestParentArtifactId(String itestParentArtifactId) {
            this.itestParentArtifactId = itestParentArtifactId;
            return this;
        }

        public Builder itestParentGroupId(String itestParentGroupId) {
            this.itestParentGroupId = itestParentGroupId;
            return this;
        }

        public Builder groupId(String groupId) {
            this.groupId = groupId;
            return this;
        }

        public Builder artifactId(String artifactId) {
            this.artifactId = artifactId;
            return this;
        }

        public Builder artifactIdPrefix(String artifactIdPrefix) {
            this.artifactIdPrefix = artifactIdPrefix;
            return this;
        }

        public Builder artifactIdBase(String artifactIdBase) {
            this.artifactIdBase = artifactIdBase;
            return this;
        }

        public Builder version(String version) {
            this.version = version;
            return this;
        }

        public Builder namePrefix(String namePrefix) {
            this.namePrefix = namePrefix;
            return this;
        }

        public Builder nameBase(String nameBase) {
            this.nameBase = nameBase;
            return this;
        }

        public Builder nameSegmentDelimiter(String nameSegmentDelimiter) {
            this.nameSegmentDelimiter = nameSegmentDelimiter;
            return this;
        }

        public Builder javaPackageBase(String javaPackageBase) {
            this.javaPackageBase = javaPackageBase;
            return this;
        }

        public Builder quarkusVersion(String quarkusVersion) {
            this.quarkusVersion = quarkusVersion;
            return this;
        }

        public Builder additionalRuntimeDependencies(List<Gavtcs> additionalRuntimeDependencies) {
            this.additionalRuntimeDependencies = additionalRuntimeDependencies;
            return this;
        }

        public Builder addAdditionalRuntimeDependencies(Gavtcs additionalRuntimeDependencies) {
            this.additionalRuntimeDependencies.add(additionalRuntimeDependencies);
            return this;
        }

        public Builder runtimeBomPathSet(boolean runtimeBomPathSet) {
            this.runtimeBomPathSet = runtimeBomPathSet;
            return this;
        }

        public Builder bomEntryVersion(String bomEntryVersion) {
            this.bomEntryVersion = bomEntryVersion;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder configPrefixes(Set<String> configPrefixes) {
            this.configPrefixes = configPrefixes;
            return this;
        }

        public Builder keywords(Collection<String> keywords) {
            this.keywords = keywords;
            return this;
        }

        public Builder keyword(String keywords) {
            this.keywords.add(keywords);
            return this;
        }

        public Builder guideUrl(String guideUrl) {
            this.guideUrl = guideUrl;
            return this;
        }

        public Builder categories(List<String> categories) {
            this.categories = categories;
            return this;
        }

        public Builder category(String categories) {
            this.categories.add(categories);
            return this;
        }

        public Builder kind(Kind kind) {
            this.kind = kind;
            return this;
        }

        public Builder status(ExtensionStatus status) {
            this.status = status;
            return this;
        }

        public Builder models(List<ArtifactModel<?>> model) {
            this.models = model;
            return this;
        }

        public Builder model(ArtifactModel<?> models) {
            this.models.add(models);
            return this;
        }

        public TemplateParams build() {
            return new TemplateParams(this);
        }

        public String getJavaPackageBasePath() {
            return javaPackageBase.replace('.', '/');
        }

        public String getArtifactId() {
            return artifactId;
        }

        public String getGroupId() {
            return groupId;
        }

        public String getBomEntryVersion() {
            return bomEntryVersion;
        }

        public List<Gavtcs> getAdditionalRuntimeDependencies() {
            return additionalRuntimeDependencies;
        }

        public String getArtifactIdBase() {
            return artifactIdBase;
        }

        public Builder modelParams(ArtifactModel<?> model) {
            description(model.getDescription());
            final String rawLabel = model.getLabel();
            if (rawLabel != null) {
                keywords(Stream.of(rawLabel.split(","))
                        .map(String::trim)
                        .map(label -> label.toLowerCase(Locale.ROOT))
                        .sorted()
                        .collect(Collectors.toList()));
            } else {
                keywords(Collections.emptyList());
            }
            kind(model.getKind());
            return this;
        }

        public Builder unlisted(boolean unlisted) {
            this.unlisted = unlisted;
            return this;
        }

        public Builder deprecated(boolean deprecated) {
            this.deprecated = deprecated;
            return this;
        }
    }

    public ExtensionStatus getStatus() {
        return status;
    }

}
