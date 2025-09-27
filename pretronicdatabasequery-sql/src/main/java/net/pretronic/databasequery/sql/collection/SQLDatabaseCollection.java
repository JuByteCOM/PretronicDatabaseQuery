/*
 * (C) Copyright 2019 The PretronicDatabaseQuery Project (Davide Wietlisbach & Philipp Elvin Friedhoff)
 *
 * @author Philipp Elvin Friedhoff
 * @since 19.12.19, 16:39
 *
 * The PretronicDatabaseQuery Project is under the Apache License, version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at:
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package net.pretronic.databasequery.sql.collection;

import net.pretronic.databasequery.api.collection.AliasDatabaseCollection;
import net.pretronic.databasequery.api.collection.DatabaseCollectionType;
import net.pretronic.databasequery.api.collection.field.CollectionField;
import net.pretronic.databasequery.api.collection.field.FieldOption;
import net.pretronic.databasequery.api.datatype.DataType;
import net.pretronic.databasequery.api.exceptions.DatabaseQueryException;
import net.pretronic.databasequery.api.query.Aggregation;
import net.pretronic.databasequery.api.query.ForeignKey;
import net.pretronic.databasequery.api.query.QueryGroup;
import net.pretronic.databasequery.api.query.QueryTransaction;
import net.pretronic.databasequery.api.query.type.*;
import net.pretronic.databasequery.common.DatabaseDriverEnvironment;
import net.pretronic.databasequery.common.collection.AbstractDatabaseCollection;
import net.pretronic.databasequery.sql.DataTypeInformation;
import net.pretronic.databasequery.sql.SQLDatabase;
import net.pretronic.databasequery.sql.collection.field.SQLCollectionField;
import net.pretronic.databasequery.sql.dialect.Dialect;
import net.pretronic.databasequery.sql.dialect.context.AlterQueryContext;
import net.pretronic.databasequery.sql.query.SQLQueryGroup;
import net.pretronic.databasequery.sql.query.SQLQueryTransaction;
import net.pretronic.databasequery.sql.query.type.*;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class SQLDatabaseCollection extends AbstractDatabaseCollection<SQLDatabase> {

    private static final String DROP_QUERY = "DROP TABLE `%s`.`%s`;";
    private static final String CLEAR_QUERY = "DELETE FROM `%s`.`%s`;";

    private final Object fieldCacheLock = new Object();
    private volatile Map<String, CollectionField> fieldCache;

    public SQLDatabaseCollection(String name, SQLDatabase database, DatabaseCollectionType type) {
        super(name, database, type);
    }

    @Override
    public long getSize() {
        return find().get(Aggregation.COUNT, "*", "size").execute().first().getLong("size");
    }

    @Override
    public InsertQuery insert() {
        return new SQLInsertQuery(this);
    }

    @Override
    public FindQuery find() {
        return new SQLFindQuery(this);
    }

    @Override
    public UpdateQuery update() {
        return new SQLUpdateQuery(this);
    }

    @Override
    public ReplaceQuery replace() {
        return new SQLReplaceQuery(this);
    }

    @Override
    public DeleteQuery delete() {
        return new SQLDeleteQuery(this);
    }

    @Override
    public void drop() {
        this.getDatabase().executeUpdateQuery(String.format(DROP_QUERY, getDatabase().getName(), getName()), true);
    }

    @Override
    public void clear() {
        getDatabase().executeUpdateQuery(String.format(CLEAR_QUERY, getDatabase().getName(), getName()), true);
    }

    @Override
    public QueryTransaction transact() {
        return new SQLQueryTransaction(getDatabase());
    }

    @Override
    public QueryGroup group() {
        return new SQLQueryGroup(getDatabase());
    }

    @Override
    public Collection<CollectionField> getFields() {
        return Collections.unmodifiableCollection(new ArrayList<>(ensureFieldCache().values()));
    }

    @Override
    public CollectionField getField(String name) {
        if(name == null) {
            return null;
        }

        String normalizedName = normalizeFieldName(name);
        CollectionField field = ensureFieldCache().get(normalizedName);
        if(field == null) {
            synchronized (fieldCacheLock) {
                fieldCache = null;
            }
            field = ensureFieldCache().get(normalizedName);
        }
        return field;
    }

    @Override
    public boolean hasField(String name) {
        return getField(name) != null;
    }

    @Override
    public CollectionField addFieldInternal(String name, DataType type, int size, Object defaultValue, ForeignKey foreignKey,
                                            FieldOption[] options) {
        if(type == null) {
            throw new IllegalArgumentException("Field type must not be null");
        }

        FieldOption[] effectiveOptions = options != null ? options : new FieldOption[0];

        AlterQueryContext context = getDatabase().getDriver().getDialect()
                .newAddFieldQuery(this, name, type, size, defaultValue, foreignKey, effectiveOptions);

        getDatabase().executeUpdateQuery(context.getQueryBuilder().toString(), true, preparedStatement -> {
            applyPreparedValues(preparedStatement, context);
        });

        for (String additionalQuery : context.getAdditionalExecutedQueries()) {
            getDatabase().executeUpdateQuery(additionalQuery, true);
        }

        SQLCollectionField field = new SQLCollectionField(this, name, type, size, defaultValue, foreignKey, effectiveOptions);

        synchronized (fieldCacheLock) {
            if(fieldCache != null) {
                Map<String, CollectionField> updated = new LinkedHashMap<>(fieldCache);
                updated.put(normalizeFieldName(name), field);
                fieldCache = Collections.unmodifiableMap(updated);
            }
        }

        return field;
    }

    void invalidateFieldCache() {
        synchronized (fieldCacheLock) {
            fieldCache = null;
        }
    }

    private void applyPreparedValues(PreparedStatement preparedStatement, AlterQueryContext context) throws SQLException {
        for (int i = 0; i < context.getPreparedValues().size(); i++) {
            preparedStatement.setObject(i + 1, context.getPreparedValues().get(i));
        }
    }

    private Map<String, CollectionField> ensureFieldCache() {
        Map<String, CollectionField> cache = this.fieldCache;
        if(cache == null) {
            synchronized (fieldCacheLock) {
                cache = this.fieldCache;
                if(cache == null) {
                    cache = Collections.unmodifiableMap(loadFieldMetadata());
                    this.fieldCache = cache;
                }
            }
        }
        return cache;
    }

    private Map<String, CollectionField> loadFieldMetadata() {
        Map<String, CollectionField> fields = new LinkedHashMap<>();
        DataSource dataSource = getDatabase().getDataSource();
        try(Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            String tableName = getName();

            List<String> tablePatterns = createIdentifierCandidates(metaData, tableName);
            List<String> catalogCandidates = createCatalogCandidates(connection);
            List<String> schemaCandidates = createSchemaCandidates(connection, metaData);

            Set<String> primaryKeys = loadPrimaryKeys(metaData, catalogCandidates, schemaCandidates, tablePatterns, tableName);
            Set<String> uniqueColumns = loadUniqueColumns(metaData, catalogCandidates, schemaCandidates, tablePatterns, tableName);

            boolean loaded = false;
            for (String catalog : catalogCandidates) {
                for (String schema : schemaCandidates) {
                    for (String tablePattern : tablePatterns) {
                        try(ResultSet resultSet = metaData.getColumns(catalog, schema, tablePattern, null)) {
                            while (resultSet.next()) {
                                String resultTableName = resultSet.getString("TABLE_NAME");
                                if(resultTableName == null || !resultTableName.equalsIgnoreCase(tableName)) {
                                    continue;
                                }

                                String columnName = resultSet.getString("COLUMN_NAME");
                                if(columnName == null) {
                                    continue;
                                }

                                String normalizedName = normalizeFieldName(columnName);
                                if(fields.containsKey(normalizedName)) {
                                    continue;
                                }

                                String typeName = resultSet.getString("TYPE_NAME");
                                int dataType = safeGetInt(resultSet, "DATA_TYPE");
                                int size = safeGetInt(resultSet, "COLUMN_SIZE");
                                Object defaultValue = safeGetObject(resultSet, "COLUMN_DEF");
                                String isNullable = safeGetString(resultSet, "IS_NULLABLE");
                                String isAutoIncrement = safeGetString(resultSet, "IS_AUTOINCREMENT");

                                DataType resolvedType = resolveDataType(typeName, dataType);
                                EnumSet<FieldOption> options = EnumSet.noneOf(FieldOption.class);
                                if(primaryKeys.contains(normalizedName)) {
                                    options.add(FieldOption.PRIMARY_KEY);
                                }
                                if(isNullable != null && isNullable.equalsIgnoreCase("NO")) {
                                    options.add(FieldOption.NOT_NULL);
                                }
                                if(isAutoIncrement != null && isAutoIncrement.equalsIgnoreCase("YES")) {
                                    options.add(FieldOption.AUTO_INCREMENT);
                                }
                                if(uniqueColumns.contains(normalizedName) && !options.contains(FieldOption.PRIMARY_KEY)) {
                                    options.add(FieldOption.UNIQUE_INDEX);
                                }

                                FieldOption[] optionArray = options.isEmpty() ? new FieldOption[0] : options.toArray(new FieldOption[0]);
                                CollectionField field = new SQLCollectionField(this, columnName, resolvedType, size, defaultValue, null, optionArray);
                                fields.put(normalizedName, field);
                                loaded = true;
                            }
                        }
                        if(loaded) {
                            return fields;
                        }
                    }
                }
            }
            return fields;
        } catch (SQLException exception) {
            throw new DatabaseQueryException("Failed to load fields for collection " + getName(), exception);
        }
    }

    private List<String> createIdentifierCandidates(DatabaseMetaData metaData, String identifier) throws SQLException {
        List<String> candidates = new ArrayList<>();
        addIfAbsent(candidates, identifier);
        addIfAbsent(candidates, identifier.toLowerCase(Locale.ROOT));
        addIfAbsent(candidates, identifier.toUpperCase(Locale.ROOT));
        addIfAbsent(candidates, normalizeIdentifier(metaData, identifier));
        return candidates;
    }

    private List<String> createCatalogCandidates(Connection connection) {
        List<String> candidates = new ArrayList<>();
        addIfAbsent(candidates, safeCatalog(connection));
        if(getDatabase().getDriver().getDialect().getEnvironment() == DatabaseDriverEnvironment.REMOTE) {
            addIfAbsent(candidates, getDatabase().getName());
        }
        addIfAbsent(candidates, null);
        return candidates;
    }

    private List<String> createSchemaCandidates(Connection connection, DatabaseMetaData metaData) throws SQLException {
        List<String> candidates = new ArrayList<>();
        addIfAbsent(candidates, safeSchema(connection));
        addIfAbsent(candidates, normalizeIdentifier(metaData, safeSchema(connection)));
        addIfAbsent(candidates, null);
        return candidates;
    }

    private Set<String> loadPrimaryKeys(DatabaseMetaData metaData, List<String> catalogs, List<String> schemas, List<String> tables,
                                        String originalTableName) throws SQLException {
        Set<String> primaryKeys = new LinkedHashSet<>();
        for (String catalog : catalogs) {
            for (String schema : schemas) {
                for (String table : tables) {
                    try(ResultSet resultSet = metaData.getPrimaryKeys(catalog, schema, table)) {
                        while (resultSet.next()) {
                            String tableName = resultSet.getString("TABLE_NAME");
                            if(tableName == null || !tableName.equalsIgnoreCase(originalTableName)) {
                                continue;
                            }
                            String columnName = resultSet.getString("COLUMN_NAME");
                            if(columnName != null) {
                                primaryKeys.add(normalizeFieldName(columnName));
                            }
                        }
                    }
                    if(!primaryKeys.isEmpty()) {
                        return primaryKeys;
                    }
                }
            }
        }
        return primaryKeys;
    }

    private Set<String> loadUniqueColumns(DatabaseMetaData metaData, List<String> catalogs, List<String> schemas, List<String> tables,
                                          String originalTableName) throws SQLException {
        Set<String> unique = new HashSet<>();
        for (String catalog : catalogs) {
            for (String schema : schemas) {
                for (String table : tables) {
                    try(ResultSet resultSet = metaData.getIndexInfo(catalog, schema, table, true, false)) {
                        while (resultSet.next()) {
                            String tableName = resultSet.getString("TABLE_NAME");
                            if(tableName == null || !tableName.equalsIgnoreCase(originalTableName)) {
                                continue;
                            }
                            String columnName = resultSet.getString("COLUMN_NAME");
                            if(columnName != null) {
                                unique.add(normalizeFieldName(columnName));
                            }
                        }
                    }
                    if(!unique.isEmpty()) {
                        return unique;
                    }
                }
            }
        }
        return unique;
    }

    private String normalizeIdentifier(DatabaseMetaData metaData, String identifier) throws SQLException {
        if(identifier == null) {
            return null;
        }
        if(metaData.storesLowerCaseIdentifiers()) {
            return identifier.toLowerCase(Locale.ROOT);
        }
        if(metaData.storesUpperCaseIdentifiers()) {
            return identifier.toUpperCase(Locale.ROOT);
        }
        return identifier;
    }

    private String safeCatalog(Connection connection) {
        try {
            return connection.getCatalog();
        } catch (SQLException ignored) {
            return null;
        }
    }

    private String safeSchema(Connection connection) {
        try {
            return connection.getSchema();
        } catch (SQLException ignored) {
            return null;
        }
    }

    private void addIfAbsent(List<String> list, String value) {
        if(list == null) return;
        if(value != null && value.isEmpty()) {
            value = null;
        }
        if(!list.contains(value)) {
            list.add(value);
        }
    }

    private String normalizeFieldName(String name) {
        return name == null ? null : name.toLowerCase(Locale.ROOT);
    }

    private String safeGetString(ResultSet resultSet, String columnLabel) {
        try {
            return resultSet.getString(columnLabel);
        } catch (SQLException ignored) {
            return null;
        }
    }

    private int safeGetInt(ResultSet resultSet, String columnLabel) {
        try {
            return resultSet.getInt(columnLabel);
        } catch (SQLException ignored) {
            return 0;
        }
    }

    private Object safeGetObject(ResultSet resultSet, String columnLabel) {
        try {
            return resultSet.getObject(columnLabel);
        } catch (SQLException ignored) {
            return null;
        }
    }

    private DataType resolveDataType(String typeName, int jdbcType) {
        if(typeName != null) {
            Dialect dialect = getDatabase().getDriver().getDialect();
            for (DataTypeInformation information : dialect.getDataTypeInformation()) {
                for (String candidate : information.getNames()) {
                    if(candidate.equalsIgnoreCase(typeName)) {
                        return information.getDataType();
                    }
                }
            }
        }

        switch (jdbcType) {
            case Types.BIGINT:
            case Types.ROWID:
                return DataType.LONG;
            case Types.INTEGER:
            case Types.SMALLINT:
            case Types.TINYINT:
                return DataType.INTEGER;
            case Types.FLOAT:
            case Types.REAL:
                return DataType.FLOAT;
            case Types.DOUBLE:
                return DataType.DOUBLE;
            case Types.NUMERIC:
            case Types.DECIMAL:
                return DataType.DECIMAL;
            case Types.BOOLEAN:
            case Types.BIT:
                return DataType.BOOLEAN;
            case Types.DATE:
                return DataType.DATE;
            case Types.TIMESTAMP:
            case Types.TIMESTAMP_WITH_TIMEZONE:
                return DataType.TIMESTAMP;
            case Types.TIME:
            case Types.TIME_WITH_TIMEZONE:
                return DataType.DATETIME;
            case Types.LONGVARCHAR:
            case Types.CLOB:
            case Types.NCLOB:
                return DataType.LONG_TEXT;
            case Types.CHAR:
            case Types.NCHAR:
                return DataType.CHAR;
            case Types.VARCHAR:
            case Types.NVARCHAR:
            case Types.LONGNVARCHAR:
                return DataType.STRING;
            case Types.BINARY:
            case Types.VARBINARY:
            case Types.LONGVARBINARY:
            case Types.BLOB:
                return DataType.BINARY;
            default:
                return DataType.STRING;
        }
    }

    @Override
    public AliasDatabaseCollection as(String alias) {
        throw new UnsupportedOperationException("Not implemented yet");
    }
}
