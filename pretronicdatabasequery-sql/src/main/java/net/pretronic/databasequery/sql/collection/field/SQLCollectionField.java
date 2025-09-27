/*
 * (C) Copyright 2024 The PretronicDatabaseQuery Project
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

package net.pretronic.databasequery.sql.collection.field;

import net.pretronic.databasequery.api.collection.field.CollectionField;
import net.pretronic.databasequery.api.collection.field.FieldOption;
import net.pretronic.databasequery.api.datatype.DataType;
import net.pretronic.databasequery.api.exceptions.DatabaseQueryException;
import net.pretronic.databasequery.api.query.ForeignKey;
import net.pretronic.databasequery.common.DatabaseDriverEnvironment;
import net.pretronic.databasequery.sql.collection.SQLDatabaseCollection;
import net.pretronic.databasequery.sql.DataTypeInformation;
import net.pretronic.databasequery.sql.dialect.Dialect;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Represents the basic runtime information of a {@link CollectionField} that was created through the SQL collection API.
 * Editing operations are currently not supported and will result in an {@link UnsupportedOperationException}.
 */
public class SQLCollectionField implements CollectionField {

    private final SQLDatabaseCollection collection;
    private final DataType type;
    private final EnumSet<FieldOption> options;
    private final EnumSet<FieldOption> originalOptions;

    private String name;
    private int size;
    private Object defaultValue;
    private ForeignKey foreignKey;

    private String originalName;
    private int originalSize;
    private Object originalDefaultValue;
    private ForeignKey originalForeignKey;

    public SQLCollectionField(SQLDatabaseCollection collection, String name, DataType type, int size, Object defaultValue,
                              ForeignKey foreignKey, FieldOption[] options) {
        this.collection = collection;
        this.name = name;
        this.type = type;
        this.size = size;
        this.defaultValue = defaultValue;
        this.foreignKey = foreignKey;
        EnumSet<FieldOption> effectiveOptions = options == null || options.length == 0
                ? EnumSet.noneOf(FieldOption.class)
                : EnumSet.copyOf(Arrays.asList(options));
        this.options = effectiveOptions;
        this.originalOptions = EnumSet.copyOf(effectiveOptions);
        this.originalName = name;
        this.originalSize = size;
        this.originalDefaultValue = defaultValue;
        this.originalForeignKey = foreignKey;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public DataType getType() {
        return type;
    }

    @Override
    public void setType(DataType dataType) {

    }

    @Override
    public int getSize() {
        return size;
    }

    @Override
    public Object getDefaultValue() {
        return defaultValue;
    }

    @Override
    public Collection<FieldOption> getOptions() {
        return Collections.unmodifiableSet(options);
    }

    @Override
    public void setName(String name) {
        if(name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Field name must not be null or empty");
        }
        this.name = name;
    }

    @Override
    public void setSize(int size) {
        if(size < 0) {
            throw new IllegalArgumentException("Size must not be negative");
        }
        this.size = size;
    }

    @Override
    public void setDefaultValue(Object defaultValue) {
        this.defaultValue = defaultValue;
    }

    @Override
    public void addFieldOption(FieldOption createOption) {
        if(createOption == null) {
            throw new IllegalArgumentException("Field option must not be null");
        }
        this.options.add(createOption);
    }

    @Override
    public void removeFieldOption(FieldOption createOption) {
        if(createOption == null) {
            throw new IllegalArgumentException("Field option must not be null");
        }
        this.options.remove(createOption);
    }

    @Override
    public void addForeignKey(ForeignKey foreignKey) {
        if(foreignKey == null) {
            throw new IllegalArgumentException("Foreign key must not be null");
        }
        this.foreignKey = foreignKey;
    }

    @Override
    public void removeForeignKey() {
        this.foreignKey = null;
    }

    @Override
    public void update() {
        Dialect dialect = getDialect();
        if(!isMySqlLike(dialect)) {
            throw new UnsupportedOperationException("Updating fields is currently only supported for MySQL compatible dialects");
        }

        String tableReference = buildCollectionReference(dialect);
        String currentColumnName = this.originalName;

        EnumSet<FieldOption> removedOptions = EnumSet.copyOf(originalOptions);
        removedOptions.removeAll(options);

        EnumSet<FieldOption> addedOptions = EnumSet.copyOf(options);
        addedOptions.removeAll(originalOptions);

        boolean foreignKeyChanged = !foreignKeyEquals(originalForeignKey, foreignKey);

        if(foreignKeyChanged && originalForeignKey != null) {
            dropForeignKey(tableReference, currentColumnName);
        }

        if(removedOptions.contains(FieldOption.UNIQUE_INDEX)) {
            dropIndex(tableReference, currentColumnName);
        }
        if(removedOptions.contains(FieldOption.INDEX)) {
            dropIndex(tableReference, currentColumnName);
        }
        if(removedOptions.contains(FieldOption.PRIMARY_KEY)) {
            dropPrimaryKey(tableReference);
        }

        if(!Objects.equals(originalName, name)) {
            renameColumn(tableReference, currentColumnName, name);
            currentColumnName = name;
        }

        boolean defaultChanged = !Objects.equals(originalDefaultValue, defaultValue);
        boolean sizeChanged = originalSize != size;
        boolean definitionOptionChanged = affectsColumnDefinition(addedOptions, removedOptions);

        if(defaultValue == null && originalDefaultValue != null && (defaultChanged || definitionOptionChanged)) {
            dropDefaultValue(tableReference, currentColumnName);
        }

        if(sizeChanged || defaultChanged || definitionOptionChanged) {
            applyColumnDefinition(tableReference, currentColumnName);
        }

        if(addedOptions.contains(FieldOption.PRIMARY_KEY)) {
            addPrimaryKey(tableReference, currentColumnName);
        }

        if(addedOptions.contains(FieldOption.UNIQUE_INDEX)) {
            addIndex(tableReference, currentColumnName, true);
        }
        if(addedOptions.contains(FieldOption.INDEX)) {
            addIndex(tableReference, currentColumnName, false);
        }

        if(foreignKeyChanged && foreignKey != null) {
            addForeignKeyInternal(tableReference, currentColumnName, foreignKey);
        }

        this.originalName = this.name;
        this.originalSize = this.size;
        this.originalDefaultValue = this.defaultValue;
        this.originalForeignKey = this.foreignKey;
        this.originalOptions.clear();
        this.originalOptions.addAll(this.options);

        this.collection.invalidateFieldCache();
    }

    @Override
    public CompletableFuture<Void> updateAsync() {
        return CompletableFuture.runAsync(this::update);
    }

    @Override
    public void remove() {
        Dialect dialect = getDialect();
        if(!isMySqlLike(dialect)) {
            throw new UnsupportedOperationException("Removing fields is currently only supported for MySQL compatible dialects");
        }

        String tableReference = buildCollectionReference(dialect);

        if(originalForeignKey != null) {
            dropForeignKey(tableReference, originalName);
        }
        if(originalOptions.contains(FieldOption.UNIQUE_INDEX)) {
            dropIndex(tableReference, originalName);
        }
        if(originalOptions.contains(FieldOption.INDEX)) {
            dropIndex(tableReference, originalName);
        }

        String sql = "ALTER TABLE " + tableReference + " DROP COLUMN " + quoteIdentifier(dialect, originalName);
        executeUpdate(sql, Collections.emptyList());

        this.collection.invalidateFieldCache();
    }

    @Override
    public CompletableFuture<Void> removeAsync() {
        return CompletableFuture.runAsync(this::remove);
    }

    public SQLDatabaseCollection getCollection() {
        return collection;
    }

    public ForeignKey getForeignKey() {
        return foreignKey;
    }

    private Dialect getDialect() {
        return collection.getDatabase().getDriver().getDialect();
    }

    private boolean isMySqlLike(Dialect dialect) {
        String dialectName = dialect.getName().toLowerCase(Locale.ROOT);
        return dialectName.contains("mysql") || dialectName.contains("mariadb") || dialectName.contains("h2");
    }

    private String buildCollectionReference(Dialect dialect) {
        StringBuilder builder = new StringBuilder();
        if(dialect.getEnvironment() == DatabaseDriverEnvironment.REMOTE) {
            builder.append(quoteIdentifier(dialect, collection.getDatabase().getName())).append(".");
        }
        builder.append(quoteIdentifier(dialect, collection.getName()));
        return builder.toString();
    }

    private String quoteIdentifier(Dialect dialect, String identifier) {
        return dialect.quoteIdentifier(identifier);
    }

    private void renameColumn(String tableReference, String oldName, String newName) {
        String sql = "ALTER TABLE " + tableReference + " RENAME COLUMN "
                + quoteIdentifier(getDialect(), oldName) + " TO " + quoteIdentifier(getDialect(), newName);
        executeUpdate(sql, Collections.emptyList());
    }

    private void dropDefaultValue(String tableReference, String columnName) {
        String sql = "ALTER TABLE " + tableReference + " ALTER COLUMN " + quoteIdentifier(getDialect(), columnName)
                + " DROP DEFAULT";
        executeUpdate(sql, Collections.emptyList());
    }

    private void applyColumnDefinition(String tableReference, String columnName) {
        Dialect dialect = getDialect();
        DataTypeInformation information = dialect.getDataTypeInformation(type);
        StringBuilder builder = new StringBuilder();
        builder.append("ALTER TABLE ").append(tableReference)
                .append(" MODIFY COLUMN ")
                .append(quoteIdentifier(dialect, columnName)).append(" ")
                .append(buildTypeDefinition(information));

        List<Object> prepared = new ArrayList<>();

        if(options.contains(FieldOption.NOT_NULL)) {
            builder.append(" NOT NULL");
        } else {
            builder.append(" NULL");
        }

        if(options.contains(FieldOption.AUTO_INCREMENT)) {
            builder.append(" AUTO_INCREMENT");
        }

        if(defaultValue != null) {
            builder.append(" DEFAULT ?");
            prepared.add(defaultValue);
        }

        if(options.contains(FieldOption.UNIQUE)) {
            builder.append(" UNIQUE");
        }

        if(options.contains(FieldOption.PRIMARY_KEY)) {
            builder.append(" PRIMARY KEY");
        }

        executeUpdate(builder.toString(), prepared);
    }

    private String buildTypeDefinition(DataTypeInformation information) {
        StringBuilder builder = new StringBuilder(information.getName());
        if(information.isSizeAble()) {
            int effectiveSize = resolveSize(information);
            if(effectiveSize > 0) {
                builder.append("(").append(effectiveSize).append(")");
            }
        }
        return builder.toString();
    }

    private int resolveSize(DataTypeInformation information) {
        if(size > 0) {
            return size;
        }
        return information.getDefaultSize();
    }

    private void dropIndex(String tableReference, String columnName) {
        String indexName = buildIndexName(columnName);
        String sql = "DROP INDEX " + quoteIdentifier(getDialect(), indexName) + " ON " + tableReference;
        executeUpdate(sql, Collections.emptyList());
    }

    private void addIndex(String tableReference, String columnName, boolean unique) {
        String indexName = buildIndexName(columnName);
        StringBuilder builder = new StringBuilder("CREATE ");
        if(unique) {
            builder.append("UNIQUE ");
        }
        builder.append("INDEX ").append(quoteIdentifier(getDialect(), indexName)).append(" ON ")
                .append(tableReference).append(" (")
                .append(quoteIdentifier(getDialect(), columnName)).append(")");
        executeUpdate(builder.toString(), Collections.emptyList());
    }

    private String buildIndexName(String columnName) {
        String indexName = collection.getDatabase().getName() + collection.getName() + columnName;
        if(indexName.length() > 64) {
            indexName = indexName.substring(0, 64);
        }
        return indexName;
    }

    private void dropPrimaryKey(String tableReference) {
        String sql = "ALTER TABLE " + tableReference + " DROP PRIMARY KEY";
        executeUpdate(sql, Collections.emptyList());
    }

    private void addPrimaryKey(String tableReference, String columnName) {
        String sql = "ALTER TABLE " + tableReference + " ADD PRIMARY KEY ("
                + quoteIdentifier(getDialect(), columnName) + ")";
        executeUpdate(sql, Collections.emptyList());
    }

    private void dropForeignKey(String tableReference, String columnName) {
        String constraintName = findForeignKeyConstraint(columnName);
        if(constraintName == null) {
            return;
        }
        String sql = "ALTER TABLE " + tableReference + " DROP FOREIGN KEY "
                + quoteIdentifier(getDialect(), constraintName);
        executeUpdate(sql, Collections.emptyList());
    }

    private void addForeignKeyInternal(String tableReference, String columnName, ForeignKey foreignKey) {
        Dialect dialect = getDialect();
        String constraintName = buildForeignKeyName(columnName);
        StringBuilder builder = new StringBuilder("ALTER TABLE ");
        builder.append(tableReference).append(" ADD CONSTRAINT ")
                .append(quoteIdentifier(dialect, constraintName)).append(" FOREIGN KEY (")
                .append(quoteIdentifier(dialect, columnName)).append(") REFERENCES ");

        String referencedDatabase = foreignKey.getDatabase() != null ? foreignKey.getDatabase() : collection.getDatabase().getName();
        if(referencedDatabase != null && !referencedDatabase.isEmpty() && dialect.getEnvironment() == DatabaseDriverEnvironment.REMOTE) {
            builder.append(quoteIdentifier(dialect, referencedDatabase)).append(".");
        }
        builder.append(quoteIdentifier(dialect, foreignKey.getCollection())).append("(")
                .append(quoteIdentifier(dialect, foreignKey.getField())).append(")");

        if(foreignKey.getDeleteOption() != null && foreignKey.getDeleteOption() != ForeignKey.Option.DEFAULT) {
            builder.append(" ON DELETE ").append(foreignKey.getDeleteOption().toString().replace("_", " "));
        }
        if(foreignKey.getUpdateOption() != null && foreignKey.getUpdateOption() != ForeignKey.Option.DEFAULT) {
            builder.append(" ON UPDATE ").append(foreignKey.getUpdateOption().toString().replace("_", " "));
        }

        executeUpdate(builder.toString(), Collections.emptyList());
    }

    private String buildForeignKeyName(String columnName) {
        String name = collection.getName() + "_" + columnName + "_fk";
        if(name.length() > 64) {
            name = name.substring(0, 64);
        }
        return name;
    }

    private String findForeignKeyConstraint(String columnName) {
        DataSource dataSource = collection.getDatabase().getDataSource();
        try(Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            try(ResultSet resultSet = metaData.getImportedKeys(connection.getCatalog(), connection.getSchema(), collection.getName())) {
                while (resultSet.next()) {
                    String fkColumn = resultSet.getString("FKCOLUMN_NAME");
                    if(fkColumn != null && fkColumn.equalsIgnoreCase(columnName)) {
                        return resultSet.getString("FK_NAME");
                    }
                }
            }
        } catch (SQLException exception) {
            throw new DatabaseQueryException("Failed to resolve foreign key metadata for column " + columnName, exception);
        }
        return null;
    }

    private void executeUpdate(String sql, List<Object> parameters) {
        if(parameters == null || parameters.isEmpty()) {
            collection.getDatabase().executeUpdateQuery(sql, true);
        } else {
            collection.getDatabase().executeUpdateQuery(sql, true, preparedStatement -> applyParameters(preparedStatement, parameters));
        }
    }

    private void applyParameters(PreparedStatement preparedStatement, List<Object> parameters) throws SQLException {
        for (int i = 0; i < parameters.size(); i++) {
            preparedStatement.setObject(i + 1, parameters.get(i));
        }
    }

    private boolean foreignKeyEquals(ForeignKey first, ForeignKey second) {
        if(first == second) {
            return true;
        }
        if(first == null || second == null) {
            return false;
        }
        return Objects.equals(first.getDatabase(), second.getDatabase())
                && Objects.equals(first.getCollection(), second.getCollection())
                && Objects.equals(first.getField(), second.getField())
                && first.getDeleteOption() == second.getDeleteOption()
                && first.getUpdateOption() == second.getUpdateOption();
    }

    private boolean affectsColumnDefinition(EnumSet<FieldOption> addedOptions, EnumSet<FieldOption> removedOptions) {
        return intersectsDefinitionOptions(addedOptions) || intersectsDefinitionOptions(removedOptions)
                || originalOptions.contains(FieldOption.UNIQUE) != options.contains(FieldOption.UNIQUE);
    }

    private boolean intersectsDefinitionOptions(EnumSet<FieldOption> options) {
        return options.contains(FieldOption.NOT_NULL)
                || options.contains(FieldOption.PRIMARY_KEY)
                || options.contains(FieldOption.AUTO_INCREMENT)
                || options.contains(FieldOption.UNIQUE);
    }
}

