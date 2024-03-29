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
package org.l2x6.cq.common;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.camel.catalog.CamelCatalog;
import org.apache.camel.catalog.DefaultCamelCatalog;
import org.apache.camel.catalog.DefaultRuntimeProvider;
import org.apache.camel.catalog.DefaultVersionManager;
import org.apache.camel.catalog.RuntimeProvider;
import org.apache.camel.catalog.impl.CatalogHelper;
import org.apache.camel.tooling.model.ArtifactModel;
import org.apache.camel.tooling.model.BaseModel;
import org.apache.camel.tooling.model.ComponentModel;
import org.apache.camel.tooling.model.EipModel;
import org.apache.camel.tooling.model.Kind;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.repository.RemoteRepository;

public class CqCatalog {

    public enum Flavor {
        camel("org.apache.camel", "camel-catalog") {
            @Override
            public RuntimeProvider createRuntimeProvider(DefaultCamelCatalog c) {
                return new DefaultRuntimeProvider(c);
            }
        },
        camelQuarkus("org.apache.camel.quarkus", "camel-quarkus-catalog") {
            @Override
            public RuntimeProvider createRuntimeProvider(DefaultCamelCatalog c) {
                return new CqRuntimeProvider(c);
            }
        };

        private final String groupId;
        private final String artifactId;

        private Flavor(String groupId, String artifactId) {
            this.groupId = groupId;
            this.artifactId = artifactId;
        }

        public abstract RuntimeProvider createRuntimeProvider(DefaultCamelCatalog c);

        public String getGroupId() {
            return groupId;
        }

        public String getArtifactId() {
            return artifactId;
        }
    }

    private final DefaultCamelCatalog catalog;
    protected final Path baseDir;
    private Flavor flavor;

    public CqCatalog(Path baseDir, Flavor flavor) {
        super();
        this.baseDir = baseDir;
        this.flavor = flavor;
        final DefaultCamelCatalog c = new DefaultCamelCatalog(true);
        c.setRuntimeProvider(flavor.createRuntimeProvider(c));
        c.setVersionManager(new CqVersionManager(c, baseDir));
        this.catalog = c;
    }

    public CqCatalog(Flavor flavor) {
        super();
        this.flavor = flavor;
        this.baseDir = null;
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

    public Stream<ArtifactModel<?>> filterModels(String cqArtifactIdBase) {
        List<String> camelArtifactIds = toCamelArtifactIdBase(cqArtifactIdBase);
        return models()
                .filter(model -> camelArtifactIds.contains(model.getArtifactId()));
    }

    public List<ArtifactModel<?>> primaryModel(String cqArtifactIdBase) {
        final List<ArtifactModel<?>> models = filterModels(cqArtifactIdBase)
                .filter(CqCatalog::isFirstScheme)
                .filter(m -> !m.getName().startsWith("google-") || !m.getName().endsWith("-stream")) // ignore the
                // google stream
                // component
                // variants
                .collect(Collectors.toList());
        if (models.size() > 1) {
            List<ArtifactModel<?>> componentModels = models.stream()
                    .filter(m -> m.getKind().equals("component"))
                    .collect(Collectors.toList());
            if (componentModels.size() == 1) {
                /* If there is only one component take that one */
                return componentModels;
            }
        }
        return models;
    }

    public Stream<ArtifactModel<?>> models() {
        return kinds()
                .flatMap(kind -> models(kind));
    }

    public Stream<ArtifactModel<?>> models(Kind kind) {
        return findNames(kind).stream().map(name -> (ArtifactModel<?>) model(kind, name));
    }

    // TODO: remove once https://github.com/apache/camel/pull/13665 reaches us
    List<String> findNames(Kind kind) {
        switch (kind) {
        case model:
            return catalog.findModelNames();
        default:
            return catalog.findNames(kind);
        }
    }

    // TODO: remove once https://github.com/apache/camel/pull/13665 reaches us
    BaseModel<?> model(Kind kind, String name) {
        System.out.println("== kind " + kind);
        switch (kind) {
        case bean:
            return catalog.pojoBeanModel(name);
        case model:
            return catalog.eipModel(name);
        default:
            return catalog.model(kind, name);
        }
    }

    public Stream<EipModel> eips() {
        return catalog.findNames(Kind.eip).stream().map(name -> catalog.eipModel(name));
    }

    public static Stream<Kind> kinds() {
        return Stream.of(Kind.values())
                .filter(kind -> kind != Kind.eip && kind != Kind.model);
    }

    /**
     * Normally schemes not available in Camel need to be removed from Camel Quarkus. This method provides a way to
     * maintain a list of exceptions to that rule.
     *
     * @param  scheme the scheme to check
     * @return        {@code true} if the given scheme is known not to be available in Camel by design; {@code false}
     *                otherwise
     */
    public static boolean isCamelQuarkusOrphan(String scheme) {
        return "qute".equals(scheme);
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

    public static Comparator<ArtifactModel<?>> compareArtifactId() {
        return (m1, m2) -> m1.getArtifactId().compareTo(m2.getArtifactId());
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
        private static final String DEV_CONSOLE_DIR = CQ_CATALOG_DIR + "/dev-consoles";
        private static final String POJO_BEAN_DIR = CQ_CATALOG_DIR + "/beans";
        private static final String TRANSFORMER_DIR = CQ_CATALOG_DIR + "/transformers";

        private static final String COMPONENTS_CATALOG = CQ_CATALOG_DIR + "/components.properties";
        private static final String DATA_FORMATS_CATALOG = CQ_CATALOG_DIR + "/dataformats.properties";
        private static final String LANGUAGE_CATALOG = CQ_CATALOG_DIR + "/languages.properties";
        private static final String OTHER_CATALOG = CQ_CATALOG_DIR + "/others.properties";
        private static final String DEV_CONSOLE_CATALOG = CQ_CATALOG_DIR + "/dev-consoles.properties";
        private static final String POJO_BEAN_CATALOG = CQ_CATALOG_DIR + "/beans.properties";
        private static final String TRANSFORMER_CATALOG = CQ_CATALOG_DIR + "/transformers.properties";

        private static final String CAPABILITIES_CATALOG = null;

        private static final String DEV_CONSOLES_CATALOG = null;

        private static final String TRANSFORMERS_CATALOG = null;

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

        @Override
        public String getDevConsoleJSonSchemaDirectory() {
            return DEV_CONSOLE_DIR;
        }

        @Override
        public String getPojoBeanJSonSchemaDirectory() {
            return POJO_BEAN_DIR;
        }

        @Override
        public String getTransformerJSonSchemaDirectory() {
            return TRANSFORMER_DIR;
        }

        @Override
        public List<String> findComponentNames() {
            return findNames(COMPONENTS_CATALOG);
        }

        @Override
        public List<String> findDataFormatNames() {
            return findNames(DATA_FORMATS_CATALOG);
        }

        @Override
        public List<String> findLanguageNames() {
            return findNames(LANGUAGE_CATALOG);
        }

        @Override
        public List<String> findOtherNames() {
            return findNames(OTHER_CATALOG);
        }

        @Override
        public List<String> findBeansNames() {
            return findNames(POJO_BEAN_CATALOG);
        }

        @Override
        public Map<String, String> findCapabilities() {
            final Properties properties = new Properties();

            try (InputStream is = getCamelCatalog().getVersionManager().getResourceAsStream(CAPABILITIES_CATALOG)) {
                properties.load(is);
            } catch (IOException e) {
                // ignore
            }

            return new TreeMap<>((Map<String, String>) (Map) properties);
        }

        @Override
        public List<String> findDevConsoleNames() {
            return findNames(DEV_CONSOLES_CATALOG);
        }

        @Override
        public List<String> findTransformerNames() {
            return findNames(TRANSFORMERS_CATALOG);
        }

        List<String> findNames(String path) {
            List<String> names = new ArrayList<>();
            InputStream is = getCamelCatalog().getVersionManager().getResourceAsStream(path);
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

    public BaseModel<?> load(Kind kind, String name) {
        return catalog.model(kind, name);
    }

    public static class GavCqCatalog extends CqCatalog implements AutoCloseable {

        private final FileSystem jarFileSystem;

        public static GavCqCatalog open(Path localRepository, Flavor flavor, String version,
                List<RemoteRepository> remoteRepositories, RepositorySystem repoSystem, RepositorySystemSession repoSession) {
            final Path jarPath = CqCommonUtils.resolveJar(localRepository, flavor.getGroupId(), flavor.getArtifactId(), version,
                    remoteRepositories, repoSystem, repoSession);
            try {
                final FileSystem fs = FileSystems.newFileSystem(jarPath, (ClassLoader) null);
                return new GavCqCatalog(fs, flavor);
            } catch (IOException e) {
                throw new RuntimeException("Could not open file system " + jarPath, e);
            }
        }

        GavCqCatalog(FileSystem jarFileSystem, Flavor flavor) {
            super(jarFileSystem.getRootDirectories().iterator().next(), flavor);
            this.jarFileSystem = jarFileSystem;
        }

        @Override
        public void close() {
            try {
                jarFileSystem.close();
            } catch (IOException e) {
                throw new RuntimeException("Could not close catalog " + this.baseDir, e);
            }
        }
    }

}
