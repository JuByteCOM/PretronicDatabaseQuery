/*
 * (C) Copyright 2019 The PretronicDatabaseQuery Project (Davide Wietlisbach & Philipp Elvin Friedhoff)
 *
 * @author Philipp Elvin Friedhoff
 * @since 19.12.19, 16:32
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

package net.pretronic.databasequery.common.collection;

import net.pretronic.databasequery.api.Database;
import net.pretronic.databasequery.api.collection.DatabaseCollection;
import net.pretronic.databasequery.api.collection.DatabaseCollectionType;
import net.pretronic.databasequery.api.collection.field.CollectionField;
import net.pretronic.databasequery.api.collection.field.FieldBuilder;
import net.pretronic.databasequery.api.collection.field.FieldOption;
import net.pretronic.databasequery.api.datatype.DataType;
import net.pretronic.databasequery.api.query.ForeignKey;

import java.util.Collection;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * The {@link AbstractDatabaseCollection} represents the default implementation of {@link DatabaseCollection}.
 * It only holds data and it implements the async methods with the given executor service in {@link net.pretronic.databasequery.api.driver.DatabaseDriver}.
 * @param <T>
 */
public abstract class AbstractDatabaseCollection<T extends Database> implements DatabaseCollection {

    private final String name;
    private final T database;
    private final DatabaseCollectionType type;

    public AbstractDatabaseCollection(String name, T database, DatabaseCollectionType type) {
        this.name = name;
        this.database = database;
        this.type = type;
    }

    @Override
    public String getName() {
        return this.name;
    }

    @Override
    public T getDatabase() {
        return this.database;
    }

    @Override
    public DatabaseCollectionType getType() {
        return this.type;
    }

    @Override
    public CompletableFuture<Long> getSizeAsync() {
        CompletableFuture<Long> future = new CompletableFuture<>();
        this.database.getDriver().getExecutorService().execute(()-> future.complete(getSize()));
        return future;
    }

    @Override
    public CompletableFuture<Void> dropAsync() {
        CompletableFuture<Void> future = new CompletableFuture<>();
        this.database.getDriver().getExecutorService().execute(()-> {
            drop();
            future.complete(null);
        });
        return future;
    }

    @Override
    public CompletableFuture<Void> clearAsync() {
        CompletableFuture<Void> future = new CompletableFuture<>();
        this.database.getDriver().getExecutorService().execute(()-> {
            clear();
            future.complete(null);
        });
        return future;
    }

    @Override
    public CompletableFuture<Collection<CollectionField>> getFieldsAsync() {
        CompletableFuture<Collection<CollectionField>> future = new CompletableFuture<>();
        this.database.getDriver().getExecutorService().execute(()-> future.complete(getFields()));
        return future;
    }

    @Override
    public CompletableFuture<CollectionField> getFieldAsync(String name) {
        CompletableFuture<CollectionField> future = new CompletableFuture<>();
        this.database.getDriver().getExecutorService().execute(()-> future.complete(getField(name)));
        return future;
    }

    @Override
    public CompletableFuture<Boolean> hasFieldAsync(String name) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        this.database.getDriver().getExecutorService().execute(()-> future.complete(hasField(name)));
        return future;
    }

    @Override
    public CollectionField addField(String name) {
        return addField(name, null, 0, null, null, new FieldOption[0]);
    }

    @Override
    public CollectionField addField(String name, DataType type, FieldOption... options) {
        return addField(name, type, 0, null, null, options);
    }

    @Override
    public CollectionField addField(String name, DataType type, int size, FieldOption... options) {
        return addField(name, type, size, null, null, options);
    }

    @Override
    public CollectionField addField(String name, DataType type, int size, Object defaultValue, FieldOption... options) {
        return addField(name, type, size, defaultValue, null, options);
    }

    @Override
    public CollectionField addField(String name, DataType type, ForeignKey foreignKey, FieldOption... options) {
        return addField(name, type, 0, null, foreignKey, options);
    }

    @Override
    public CollectionField addField(Consumer<FieldBuilder> builder) {
        SimpleFieldBuilder fieldBuilder = new SimpleFieldBuilder();
        builder.accept(fieldBuilder);
        Objects.requireNonNull(fieldBuilder.name, "Field name must not be null");
        Objects.requireNonNull(fieldBuilder.type, "Field type must not be null");
        FieldOption[] options = fieldBuilder.options != null ? fieldBuilder.options : new FieldOption[0];
        return addField(fieldBuilder.name,
                fieldBuilder.type,
                fieldBuilder.size,
                fieldBuilder.defaultValue,
                fieldBuilder.foreignKey,
                options);
    }

    /**
     * Developer Note: Override {@link #addFieldInternal(String, DataType, int, Object, ForeignKey, FieldOption[])}
     * in database specific implementations to handle the actual field creation.
     */
    protected CollectionField addFieldInternal(String name, DataType type, int size, Object defaultValue, ForeignKey foreignKey, FieldOption[] options) {
        throw new UnsupportedOperationException("Adding fields is not supported for this collection type.");
    }

    @Override
    public CollectionField addField(String name, DataType type, int size, Object defaultValue, ForeignKey foreignKey, FieldOption... options) {
        return addFieldInternal(name, type, size, defaultValue, foreignKey, options);
    }

    private static final class SimpleFieldBuilder implements FieldBuilder {

        private String name;
        private DataType type;
        private int size = 0;
        private Object defaultValue;
        private ForeignKey foreignKey;
        private FieldOption[] options;

        @Override
        public FieldBuilder name(String name) {
            this.name = name;
            return this;
        }

        @Override
        public FieldBuilder type(DataType type) {
            this.type = type;
            return this;
        }

        @Override
        public FieldBuilder size(int size) {
            this.size = size;
            return this;
        }

        @Override
        public FieldBuilder defaultValue(Object value) {
            this.defaultValue = value;
            return this;
        }

        @Override
        public FieldBuilder foreignKey(ForeignKey foreignKey) {
            this.foreignKey = foreignKey;
            return this;
        }

        @Override
        public FieldBuilder options(FieldOption... options) {
            this.options = options;
            return this;
        }
    }

    @Override
    public boolean equals(Object o) {
        if(this == o) return true;
        if(o instanceof DatabaseCollection) {
            DatabaseCollection collection = ((DatabaseCollection) o);
            return getName().equals(collection.getName()) && getDatabase().equals(((DatabaseCollection) o).getDatabase());
        }
        return false;
    }
}
