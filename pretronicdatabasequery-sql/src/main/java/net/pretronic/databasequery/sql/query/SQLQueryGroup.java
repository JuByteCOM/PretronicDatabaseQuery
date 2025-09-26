/*
 * (C) Copyright 2019 The PretronicDatabaseQuery Project (Davide Wietlisbach & Philipp Elvin Friedhoff)
 *
 * @author Philipp Elvin Friedhoff
 * @since 19.12.19, 20:51
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

package net.pretronic.databasequery.sql.query;

import net.pretronic.databasequery.api.query.Query;
import net.pretronic.databasequery.api.query.result.QueryResult;
import net.pretronic.databasequery.api.query.result.QueryResultEntry;
import net.pretronic.databasequery.common.query.AbstractQueryGroup;
import net.pretronic.databasequery.common.query.result.DefaultQueryResult;
import net.pretronic.databasequery.sql.SQLDatabase;
import net.pretronic.databasequery.sql.query.CommitOnExecute;

public class SQLQueryGroup extends AbstractQueryGroup {

    private final SQLDatabase database;

    public SQLQueryGroup(SQLDatabase database) {
        this.database = database;
    }

    @Override
    public QueryResult execute() {
        DefaultQueryResult result = new DefaultQueryResult();
        for (Entry entry : this.entries) {
            if(entry == null || entry.query == null) continue;

            Query query = entry.query;
            Object[] values = entry.values != null ? entry.values : Query.EMPTY_OBJECT_ARRAY;

            QueryResult queryResult;
            if(query instanceof CommitOnExecute) {
                queryResult = ((CommitOnExecute) query).execute(true, values);
            } else {
                queryResult = query.execute(values);
            }

            if(queryResult == null) {
                continue;
            }

            queryResult.getProperties().forEach((key, value) -> result.getProperties().put(key, value));
            for (QueryResultEntry resultEntry : queryResult.asList()) {
                result.addEntry(resultEntry);
            }
        }
        return result;
    }
}
