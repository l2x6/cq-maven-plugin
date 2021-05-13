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
package org.l2x6.cq.maven.prod;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.camel.catalog.Kind;
import org.apache.camel.tooling.model.ArtifactModel;
import org.apache.camel.tooling.model.BaseModel;
import org.apache.camel.tooling.model.EipModel;
import org.apache.maven.model.Model;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.l2x6.cq.common.CqCatalog;
import org.l2x6.cq.common.CqCatalog.Flavor;
import org.l2x6.cq.common.CqCatalog.GavCqCatalog;
import org.l2x6.cq.common.CqCommonUtils;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.services.sheets.v4.Sheets;
import com.google.api.services.sheets.v4.SheetsScopes;
import com.google.api.services.sheets.v4.model.ValueRange;

/**
 * Updates a Google Sheet containing CQ extensions based on the data from a specified CQ Catalog
 */
@Mojo(name = "sync-ext-list", threadSafe = true, requiresProject = false)
public class SyncExtensionListMojo extends AbstractMojo {

    private static final String APPLICATION_NAME = "cq-prod-maven-plugin";

    /**
     * The maven project.
     */
    @Parameter(property = "project", required = true, readonly = true)
    private MavenProject project;

    @Parameter(required = true, property = "cq.camelQuarkusVersion")
    private String camelQuarkusVersion;

    /**
     * The directory where to store the output files.
     */
    @Parameter(required = true, property = "cq.googleCredentials", defaultValue = "${user.home}/.config/cq/credentials.json")
    private File googleCredentialsJson;

    @Parameter(required = true, property = "cq.googleTokensDir", defaultValue = "${user.home}/.config/cq/tokens")
    private File googleTokensDir;

    @Parameter(required = true, property = "cq.googleSpreadsheetId", defaultValue = "1vNs6LQN7W2YHbfknf4ZS9-1-O_-6M73nqWGlkIWNCqA")
    private String googleSpreadsheetId;

    @Parameter(defaultValue = "${settings.localRepository}", readonly = true)
    String localRepository;

    /**
     * Execute goal.
     *
     * @throws MojoExecutionException execution of the main class or one of the
     *             threads it generated failed.
     * @throws MojoFailureException something bad happened...
     */
    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        final Path localRepositoryPath = Paths.get(localRepository);
        final String camelVersion = findCamelVersion(localRepositoryPath);

        try {
            final NetHttpTransport HTTP_TRANSPORT = GoogleNetHttpTransport.newTrustedTransport();
            final JsonFactory JSON_FACTORY = JacksonFactory.getDefaultInstance();
            final List<String> SCOPES = Collections.singletonList(SheetsScopes.SPREADSHEETS);

            final Comparator<ArtifactModel<?>> comparator = CqCatalog.compareArtifactId()
                    .thenComparing(BaseModel.compareTitle());
            final Sheets service = new Sheets.Builder(HTTP_TRANSPORT, JSON_FACTORY,
                    getCredentials(HTTP_TRANSPORT, JSON_FACTORY, SCOPES))
                            .setApplicationName(APPLICATION_NAME)
                            .build();
            try (GavCqCatalog camelCatalog = GavCqCatalog.open(localRepositoryPath, Flavor.camel, camelVersion);
                    GavCqCatalog camelQuarkusCatalog = GavCqCatalog.open(localRepositoryPath, Flavor.camelQuarkus,
                            camelQuarkusVersion)) {

                {
                    final Kind kind = Kind.eip;
                    getLog().info("Updating " + CqCommonUtils.humanPlural(kind));
                    final Set<String> allSchemes = new LinkedHashSet<>();

                    final Map<String, EipModel> camelModels = new LinkedHashMap<>();
                    camelCatalog.eips()
                            .sorted(BaseModel.compareTitle())
                            .forEach(m -> {
                                camelModels.put(m.getName(), m);
                                allSchemes.add(m.getName());
                            });

                    final Map<String, EipModel> cqModels = new LinkedHashMap<>();
//                    camelQuarkusCatalog.eips()
//                            .sorted(BaseModel.compareTitle())
//                            .forEach(m -> {
//                                cqModels.put(m.getName(), m);
//                                allSchemes.add(m.getName());
//                            });

                    /* Go through extensions available in the spreadsheet and update them */
                    final Sheet sheet = Sheet.read(service, googleSpreadsheetId, kind, getLog(), Column.eipColumns());

                    for (String scheme : allSchemes) {
                        sheet.updateBase(scheme, camelModels.get(scheme), cqModels.get(scheme));
                    }

                    sheet.update();
                }

                CqCatalog.kinds().forEach(kind -> {

                    getLog().info("Updating " + CqCommonUtils.humanPlural(kind));
                    final Set<String> allSchemes = new LinkedHashSet<>();

                    final Map<String, ArtifactModel<?>> camelModels = new LinkedHashMap<>();
                    camelCatalog.models(kind)
                            .filter(CqCatalog::isFirstScheme)
                            .sorted(comparator)
                            .forEach(m -> {
                                camelModels.put(m.getName(), m);
                                allSchemes.add(m.getName());
                            });

                    final Map<String, ArtifactModel<?>> cqModels = new LinkedHashMap<>();
                    camelQuarkusCatalog.models(kind)
                            .filter(CqCatalog::isFirstScheme)
                            .sorted(comparator)
                            .forEach(m -> {
                                cqModels.put(m.getName(), m);
                                allSchemes.add(m.getName());
                            });

                    /* Go through extensions available in the spreadsheet and update them */
                    final Sheet sheet = Sheet.read(service, googleSpreadsheetId, kind, getLog(), Column.values());

                    for (String scheme : allSchemes) {
                        sheet.update(scheme, camelModels.get(scheme), cqModels.get(scheme));
                    }

                    sheet.update();
                });
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    String findCamelVersion(Path localRepositoryPath) {
        final Path cqPomPath = CqCommonUtils.copyArtifact(localRepositoryPath, Flavor.camelQuarkus.getGroupId(),
                "camel-quarkus", camelQuarkusVersion, "pom");
        final Model cqPomModel = CqCommonUtils.readPom(cqPomPath, StandardCharsets.UTF_8);
        final String camelMajorMinor = (String) Objects.requireNonNull(cqPomModel.getProperties().get("camel.major.minor"));
        final String camelVersion = (String) Objects.requireNonNull(cqPomModel.getProperties().get("camel.version"));
        return camelVersion.replace("${camel.major.minor}", camelMajorMinor);
    }

    /**
     * Creates an authorized Credential object.
     *
     * @param transport The network HTTP Transport.
     * @param scopes
     * @param jSON_FACTORY
     * @return An authorized Credential object.
     * @throws IOException If the credentials.json file cannot be found.
     */
    Credential getCredentials(final NetHttpTransport transport, JsonFactory jsonFactory, List<String> scopes) {

        try (Reader in = new InputStreamReader(new FileInputStream(googleCredentialsJson), StandardCharsets.UTF_8)) {
            GoogleClientSecrets clientSecrets = GoogleClientSecrets.load(jsonFactory, in);

            // Build flow and trigger user authorization request.
            GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(
                    transport, jsonFactory, clientSecrets, scopes)
                            .setDataStoreFactory(new FileDataStoreFactory(googleTokensDir))
                            .setAccessType("offline")
                            .build();
            LocalServerReceiver receiver = new LocalServerReceiver.Builder().setPort(8888).build();
            return new AuthorizationCodeInstalledApp(flow, receiver).authorize("user");
        } catch (FileNotFoundException e) {
            throw new RuntimeException("Could not read from " + googleCredentialsJson, e);
        } catch (IOException e) {
            throw new RuntimeException("Could not authorize", e);
        }

    }

    enum NativeSupport {
        Native, JVM, Rejected, Removed, na("n/a"), unavailable("");

        private final String title;

        private NativeSupport() {
            this.title = name();
        }

        private NativeSupport(String title) {
            this.title = title;
        }

        public static NativeSupport ofTitle(String title) {
            for (NativeSupport column : values()) {
                if (column.title == title || (column.title != null && column.title.equalsIgnoreCase(title))) {
                    return column;
                }
            }
            return NativeSupport.unavailable;
        }
    }

    enum Column {
        Name,
        Scheme,
        Camel_artifactId("Camel artifactId"),
        CQ_artifactId("CQ artifactId"),
        Kind,
        Deprecated,
        CQ_Community("CQ Community"),
        Community_issue("Community issue");

        private final String title;

        private Column() {
            this.title = name();
        }

        public static Column[] eipColumns() {
            return new Column[] {Name, Scheme, Kind, Deprecated, CQ_Community, Community_issue};
        }

        private Column(String title) {
            this.title = title;
        }

        public static Optional<Column> ofTitle(String title) {
            for (Column column : values()) {
                if (column.title.equals(title)) {
                    return Optional.of(column);
                }
            }
            return Optional.empty();
        }

    }

    static class Sheet {

        public static Sheet read(Sheets service, String spreadsheetId, Kind kind, Log log, Column... requiredColumns) {
            final String sheetName = CqCommonUtils.humanPlural(kind);
            final String rangeSpec = sheetName + "!A1:O";
            final ValueRange range;
            try {
                range = service.spreadsheets().values()
                        .get(spreadsheetId, rangeSpec)
                        .execute();

            } catch (IOException e) {
                throw new RuntimeException("Could not get range " + rangeSpec + " from spreadsheet " + spreadsheetId, e);
            }
            final List<Object> headers = range.getValues().get(0);
            final EnumMap<Column, Integer> colMap = new EnumMap<>(Column.class);
            final Set<Column> requiredCols = new LinkedHashSet<>(Arrays.asList(requiredColumns));
            for (int i = 0; i < headers.size(); i++) {
                final String title = String.valueOf(headers.get(i));
                final Optional<Column> col = Column.ofTitle(title);
                if (col.isPresent()) {
                    colMap.put(col.get(), i);
                    requiredCols.remove(col.get());
                }
            }
            if (!requiredCols.isEmpty()) {
                throw new IllegalStateException(
                        "Could not find colums [" +
                                requiredCols.stream()
                                        .map(Column::name)
                                        .collect(Collectors.joining(", "))
                                + "] in sheet " + sheetName);
            }
            return new Sheet(service, spreadsheetId, range, colMap, headers, log);
        }

        final private Set<String> updatedSchemes = new HashSet<>();
        final private List<String> newSchemes = new ArrayList<>();

        public Record updateBase(String scheme, BaseModel<?> camelModel, BaseModel<?> cqModel) {
            Record row = findRecord(scheme);
            if (row == null) {
                row = addRecord(scheme);
                newSchemes.add(scheme);
            }
            BaseModel<?> model = cqModel != null ? cqModel : camelModel;
            row.set(Column.Name, model.getTitle());
            row.set(Column.Kind, model.getKind());

            NativeSupport nativeSupport = row.getNativeSupport();
            if (nativeSupport != NativeSupport.Rejected) {
                if (cqModel == null) {
                    row.setNativeSupport(NativeSupport.na);
                } else {
                    row.setNativeSupport(cqModel.isNativeSupported() ? NativeSupport.Native : NativeSupport.JVM);
                }
            }

            final boolean deprecated = (camelModel != null && camelModel.isDeprecated())
                    || (cqModel != null && cqModel.isDeprecated());
            row.set(Column.Deprecated, String.valueOf(deprecated).toUpperCase(Locale.ROOT));

            updatedSchemes.add(scheme);
            return row;
        }

        public void update(String scheme, ArtifactModel<?> camelModel, ArtifactModel<?> cqModel) {

            final Record row = updateBase(scheme, camelModel, cqModel);

            row.set(Column.Camel_artifactId, camelModel != null ? camelModel.getArtifactId() : "");
            row.set(Column.CQ_artifactId, cqModel != null ? cqModel.getArtifactId() : "");

        }

        public Record addRecord(String scheme) {
            List<Object> row = new ArrayList<>(headers.size());
            for (int i = 0; i < headers.size(); i++) {
                row.add("");
            }
            final Record record = new Record(row);
            rows.add(record);
            record.set(Column.Scheme, scheme);
            return record;
        }

        public Record findRecord(String scheme) {
            for (Record row : rows) {
                String rowScheme = row.getString(Column.Scheme);
                if (scheme.equals(rowScheme)) {
                    return row;
                }
            }
            return null;
        }

        private final Sheets service;
        private final String spreadsheetId;
        private final ValueRange range;
        private final EnumMap<Column, Integer> colMap;
        private final List<Object> headers;
        private final List<Record> rows;
        private final Log log;

        public Sheet(Sheets service, String spreadsheetId, ValueRange range, EnumMap<Column, Integer> colMap,
                List<Object> headers, Log log) {
            this.service = service;
            this.spreadsheetId = spreadsheetId;
            this.range = range;

            final List<Record> rows = new ArrayList<>();
            final List<List<Object>> values = range.getValues();
            for (int i = 1; i < values.size(); i++) {
                List<Object> row = values.get(i);
                rows.add(new Record(row));
            }
            this.rows = rows;

            this.colMap = colMap;
            this.headers = headers;
            this.log = log;
        }

        public Record getRow(int rowIndex) {
            return rows.get(rowIndex);
        }

        public int getRowCount() {
            return rows.size();
        }

        public void update() {

            markRemovedRows();

            List<List<Object>> newValues = new ArrayList<>();
            newValues.add(headers);

            rows.stream()
                    .sorted(Comparator.comparing(Record::getArtifactIdBase).thenComparing(Record::getScheme))
                    .map(r -> r.row)
                    .forEach(newValues::add);

            range.setValues(newValues);

            try {
                service.spreadsheets().values()
                        .update(spreadsheetId, range.getRange(), range)
                        .setValueInputOption("USER_ENTERED")
                        .execute();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

            if (!newSchemes.isEmpty()) {
                log.info(
                        " - Added " + newSchemes.size() + " schemes: " + newSchemes.stream().collect(Collectors.joining(", ")));
            }

        }

        void markRemovedRows() {
            for (Record r : rows) {
                String scheme = r.getString(Column.Scheme);
                if (scheme != null && !scheme.isEmpty() && !updatedSchemes.contains(scheme)
                        && r.getNativeSupport() != NativeSupport.Removed) {
                    r.setNativeSupport(NativeSupport.Removed);
                }
            }
        }

        class Record {

            public Record(List<Object> row) {
                this.row = row;
            }

            public NativeSupport getNativeSupport() {
                return NativeSupport.ofTitle(getString(Column.CQ_Community));
            }

            public void setNativeSupport(NativeSupport nativeSupport) {
                set(Column.CQ_Community, nativeSupport.title);
            }

            public String getString(Column col) {
                return String.valueOf(get(col));
            }

            private final List<Object> row;

            public Object get(Column col) {
                return row.get(colMap.get(col));
            }

            public String getScheme() {
                return (String) row.get(colMap.get(Column.Scheme));
            }

            public String getArtifactIdBase() {
                final Integer index = colMap.get(Column.CQ_artifactId);
                if (index != null) {
                    String cqArtifactId = (String) row.get(index);
                    if (cqArtifactId != null && !cqArtifactId.isEmpty()) {
                        return cqArtifactId.substring("camel-quarkus-".length());
                    }
                    String camelArtifactId = (String) row.get(colMap.get(Column.Camel_artifactId));
                    if (camelArtifactId != null && !camelArtifactId.isEmpty()) {
                        return camelArtifactId.substring("camel-".length());
                    }
                }
                return "ZZZZZZZZZZZZZZZZZZZ";
            }

            public void set(Column col, Object value) {
                final int i = colMap.get(col);
                Object old = row.get(i);
                if (old != value && (old == null || !old.equals(value))) {
                    log.info(" - Updating " + get(Column.Scheme) + "[" + col.title + "]: " + old + " -> " + value);
                    row.set(i, value);
                }
            }
        }
    }

}
