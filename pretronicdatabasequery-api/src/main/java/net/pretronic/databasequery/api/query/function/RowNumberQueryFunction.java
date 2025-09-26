/*
 * (C) Copyright 2020 The PretronicDatabaseQuery Project (Davide Wietlisbach & Philipp Elvin Friedhoff)
 *
 * @author Philipp Elvin Friedhoff
 * @since 19.07.20, 13:22
 * @web %web%
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

package net.pretronic.databasequery.api.query.function;

import net.pretronic.databasequery.api.query.SearchOrder;

import java.util.Objects;
import java.util.regex.Pattern;

public class RowNumberQueryFunction implements QueryFunction {

    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("^[A-Za-z0-9_]+$");

    private final String orderField;
    private final SearchOrder order;
    private final String orderDatabase;
    private final String orderDatabaseCollection;
    private final String orderFieldName;

    protected RowNumberQueryFunction(String orderField, SearchOrder order) {
        this.orderField = Objects.requireNonNull(orderField, "orderField").trim();
        if(this.orderField.isEmpty()) {
            throw new IllegalArgumentException("orderField may not be empty");
        }
        this.order = Objects.requireNonNull(order, "order");

        String[] segments = this.orderField.split("\\.");
        if(segments.length == 0 || segments.length > 3) {
            throw new IllegalArgumentException("Invalid order field path '" + orderField + "'");
        }

        switch (segments.length) {
            case 3:
                this.orderDatabase = validateIdentifier(segments[0], "database");
                this.orderDatabaseCollection = validateIdentifier(segments[1], "collection");
                this.orderFieldName = validateIdentifier(segments[2], "field");
                break;
            case 2:
                this.orderDatabase = null;
                this.orderDatabaseCollection = validateIdentifier(segments[0], "collection");
                this.orderFieldName = validateIdentifier(segments[1], "field");
                break;
            case 1:
                this.orderDatabase = null;
                this.orderDatabaseCollection = null;
                this.orderFieldName = validateIdentifier(segments[0], "field");
                break;
            default:
                throw new IllegalArgumentException("Invalid order field path '" + orderField + "'");
        }
    }

    private String validateIdentifier(String value, String type) {
        String trimmed = value.trim();
        if(trimmed.isEmpty() || !IDENTIFIER_PATTERN.matcher(trimmed).matches()) {
            throw new IllegalArgumentException(String.format("Invalid %s identifier '%s'", type, value));
        }
        return trimmed;
    }

    public String getOrderField() {
        return orderField;
    }

    public SearchOrder getOrder() {
        return order;
    }

    public String getOrderDatabase() {
        return orderDatabase;
    }

    public String getOrderDatabaseCollection() {
        return orderDatabaseCollection;
    }

    public String getOrderFieldName() {
        return orderFieldName;
    }

    @Override
    public boolean equals(Object o) {
        if(this == o) return true;
        if(o instanceof RowNumberQueryFunction) {
            RowNumberQueryFunction function = ((RowNumberQueryFunction) o);
            return orderField.equals(function.orderField) && order == function.order;
        }
        return false;
    }
}
