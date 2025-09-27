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
import net.pretronic.databasequery.api.query.ForeignKey;
import net.pretronic.databasequery.sql.collection.SQLDatabaseCollection;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Represents the basic runtime information of a {@link CollectionField} that was created through the SQL collection API.
 * Editing operations are currently not supported and will result in an {@link UnsupportedOperationException}.
 */
public class SQLCollectionField implements CollectionField {

    private final SQLDatabaseCollection collection;
    private final DataType type;
    private final Set<FieldOption> options;

    private String name;
    private int size;
    private Object defaultValue;
    private ForeignKey foreignKey;

    public SQLCollectionField(SQLDatabaseCollection collection, String name, DataType type, int size, Object defaultValue,
                              ForeignKey foreignKey, FieldOption[] options) {
        this.collection = collection;
        this.name = name;
        this.type = type;
        this.size = size;
        this.defaultValue = defaultValue;
        this.foreignKey = foreignKey;
        this.options = options == null || options.length == 0
                ? EnumSet.noneOf(FieldOption.class)
                : EnumSet.copyOf(Arrays.asList(options));
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
        throw new UnsupportedOperationException("Renaming fields is not supported yet");
    }

    @Override
    public void setSize(int size) {
        throw new UnsupportedOperationException("Changing field size is not supported yet");
    }

    @Override
    public void setDefaultValue(Object defaultValue) {
        throw new UnsupportedOperationException("Changing default values is not supported yet");
    }

    @Override
    public void addFieldOption(FieldOption createOption) {
        throw new UnsupportedOperationException("Modifying field options is not supported yet");
    }

    @Override
    public void removeFieldOption(FieldOption createOption) {
        throw new UnsupportedOperationException("Modifying field options is not supported yet");
    }

    @Override
    public void addForeignKey(ForeignKey foreignKey) {
        throw new UnsupportedOperationException("Adding foreign keys is not supported yet");
    }

    @Override
    public void removeForeignKey() {
        throw new UnsupportedOperationException("Removing foreign keys is not supported yet");
    }

    @Override
    public void update() {
        throw new UnsupportedOperationException("Updating fields is not supported yet");
    }

    @Override
    public java.util.concurrent.CompletableFuture<Void> updateAsync() {
        throw new UnsupportedOperationException("Updating fields is not supported yet");
    }

    @Override
    public void remove() {
        throw new UnsupportedOperationException("Removing fields is not supported yet");
    }

    @Override
    public java.util.concurrent.CompletableFuture<Void> removeAsync() {
        throw new UnsupportedOperationException("Removing fields is not supported yet");
    }

    public SQLDatabaseCollection getCollection() {
        return collection;
    }

    public ForeignKey getForeignKey() {
        return foreignKey;
    }
}

