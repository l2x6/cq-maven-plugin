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
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.camel.catalog.CamelCatalog;
import org.apache.camel.catalog.DefaultCamelCatalog;
import org.apache.camel.catalog.DefaultVersionManager;
import org.apache.camel.catalog.Kind;
import org.apache.camel.catalog.RuntimeProvider;
import org.apache.camel.catalog.impl.CatalogHelper;
import org.apache.camel.tooling.model.ArtifactModel;
import org.apache.camel.tooling.model.BaseModel;
import org.apache.camel.tooling.model.ComponentModel;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class CqCatalog {

    private final DefaultCamelCatalog catalog;

    public CqCatalog(Path baseDir) {
        super();
        final DefaultCamelCatalog c = new DefaultCamelCatalog(true);
        c.setRuntimeProvider(new CqRuntimeProvider(c));
        c.setVersionManager(new CqVersionManager(c, baseDir));
        this.catalog = c;
    }

    public CqCatalog() {
        super();
        this.catalog = new DefaultCamelCatalog(true);
    }

    public List<String> toCamelArtifactIdBase(String cqArtifactIdBase) {
        if ("core".equals(cqArtifactIdBase)) {
            return Arrays.asList("camel-base", "camel-core-languages");
        } else if ("reactive-executor".equals(cqArtifactIdBase)) {
            return Collections.singletonList("camel-reactive-executor-vertx");
        } else {
            return Collections.singletonList("camel-" + cqArtifactIdBase);
        }
    }

    public List<ArtifactModel<?>> filterModels(String artifactIdBase) {
        List<String> camelArtifactIds = toCamelArtifactIdBase(artifactIdBase);
        return models()
                .filter(model -> camelArtifactIds.contains(model.getArtifactId()))
                .collect(Collectors.toList());
    }

    public Stream<ArtifactModel<?>> models() {
        return kinds()
                .flatMap(kind -> models(kind));
    }

    public Stream<ArtifactModel<?>> models(org.apache.camel.catalog.Kind kind) {
        return catalog.findNames(kind).stream().map(name -> (ArtifactModel<?>) catalog.model(kind, name));
    }

    public static Stream<org.apache.camel.catalog.Kind> kinds() {
        return Stream.of(org.apache.camel.catalog.Kind.values())
                .filter(kind -> kind != org.apache.camel.catalog.Kind.eip);
    }

    public static boolean isFirstScheme(ArtifactModel<?> model) {
        if (model.getKind().equals("component")) {
            final String altSchemes = ((ComponentModel) model).getAlternativeSchemes();
            if (altSchemes == null || altSchemes.isEmpty()) {
                return true;
            } else {
                final String scheme = model.getName();
                return altSchemes.equals(scheme) || altSchemes.startsWith(scheme + ",");
            }
        } else {
            return true;
        }
    }

    static class CqVersionManager extends DefaultVersionManager {
        private final Path baseDir;

        public CqVersionManager(CamelCatalog camelCatalog, Path baseDir) {
            super(camelCatalog);
            this.baseDir = baseDir;
        }

        @Override
        public InputStream getResourceAsStream(String name) {
            try {
                return Files.newInputStream(baseDir.resolve(name));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

    }

    static class CqRuntimeProvider implements RuntimeProvider {
        public static final String CQ_CATALOG_DIR = "org/apache/camel/catalog/quarkus";

        private static final String COMPONENT_DIR = CQ_CATALOG_DIR + "/components";
        private static final String DATAFORMAT_DIR = CQ_CATALOG_DIR + "/dataformats";
        private static final String LANGUAGE_DIR = CQ_CATALOG_DIR + "/languages";
        private static final String OTHER_DIR = CQ_CATALOG_DIR + "/others";
        private static final String COMPONENTS_CATALOG = CQ_CATALOG_DIR + "/components.properties";
        private static final String DATA_FORMATS_CATALOG = CQ_CATALOG_DIR + "/dataformats.properties";
        private static final String LANGUAGE_CATALOG = CQ_CATALOG_DIR + "/languages.properties";
        private static final String OTHER_CATALOG = CQ_CATALOG_DIR + "/others.properties";

        private CamelCatalog camelCatalog;

        public CqRuntimeProvider(CamelCatalog camelCatalog) {
            this.camelCatalog = camelCatalog;
        }

        @Override
        public CamelCatalog getCamelCatalog() {
            return camelCatalog;
        }

        @Override
        public void setCamelCatalog(CamelCatalog camelCatalog) {
            this.camelCatalog = camelCatalog;
        }

        @Override
        public String getProviderName() {
            return "camel-quarkus";
        }

        @Override
        public String getProviderGroupId() {
            return "org.apache.camel.quarkus";
        }

        @Override
        public String getProviderArtifactId() {
            return "camel-quarkus-catalog";
        }

        @Override
        public String getComponentJSonSchemaDirectory() {
            return COMPONENT_DIR;
        }

        @Override
        public String getDataFormatJSonSchemaDirectory() {
            return DATAFORMAT_DIR;
        }

        @Override
        public String getLanguageJSonSchemaDirectory() {
            return LANGUAGE_DIR;
        }

        @Override
        public String getOtherJSonSchemaDirectory() {
            return OTHER_DIR;
        }

        protected String getComponentsCatalog() {
            return COMPONENTS_CATALOG;
        }

        protected String getDataFormatsCatalog() {
            return DATA_FORMATS_CATALOG;
        }

        protected String getLanguageCatalog() {
            return LANGUAGE_CATALOG;
        }

        protected String getOtherCatalog() {
            return OTHER_CATALOG;
        }

        @Override
        public List<String> findComponentNames() {
            List<String> names = new ArrayList<>();
            InputStream is = getCamelCatalog().getVersionManager().getResourceAsStream(getComponentsCatalog());
            if (is != null) {
                try {
                    CatalogHelper.loadLines(is, names);
                } catch (IOException e) {
                    // ignore
                }
            }
            return names;
        }

        @Override
        public List<String> findDataFormatNames() {
            List<String> names = new ArrayList<>();
            InputStream is = getCamelCatalog().getVersionManager().getResourceAsStream(getDataFormatsCatalog());
            if (is != null) {
                try {
                    CatalogHelper.loadLines(is, names);
                } catch (IOException e) {
                    // ignore
                }
            }
            return names;
        }

        @Override
        public List<String> findLanguageNames() {
            List<String> names = new ArrayList<>();
            InputStream is = getCamelCatalog().getVersionManager().getResourceAsStream(getLanguageCatalog());
            if (is != null) {
                try {
                    CatalogHelper.loadLines(is, names);
                } catch (IOException e) {
                    // ignore
                }
            }
            return names;
        }

        @Override
        public List<String> findOtherNames() {
            List<String> names = new ArrayList<>();
            InputStream is = getCamelCatalog().getVersionManager().getResourceAsStream(getOtherCatalog());
            if (is != null) {
                try {
                    CatalogHelper.loadLines(is, names);
                } catch (IOException e) {
                    // ignore
                }
            }
            return names;
        }
    }

    public BaseModel<?> load(org.apache.camel.catalog.Kind kind, String name) {
        return catalog.model(kind, name);
    }

}
