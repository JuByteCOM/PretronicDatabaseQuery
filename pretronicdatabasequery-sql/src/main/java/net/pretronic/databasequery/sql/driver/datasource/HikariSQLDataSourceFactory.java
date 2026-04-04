/*
 * (C) Copyright 2019 The PretronicDatabaseQuery Project (Davide Wietlisbach & Philipp Elvin Friedhoff)
 *
 * @author Philipp Elvin Friedhoff
 * @since 23.12.19, 15:57
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

package net.pretronic.databasequery.sql.driver.datasource;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.util.IsolationLevel;
import net.pretronic.databasequery.common.DatabaseDriverEnvironment;
import net.pretronic.databasequery.sql.dialect.Dialect;
import net.pretronic.databasequery.sql.driver.SQLDatabaseDriver;
import net.pretronic.databasequery.sql.driver.config.SQLDatabaseDriverConfig;
import net.pretronic.databasequery.sql.driver.config.SQLLocalDatabaseDriverConfig;
import net.pretronic.databasequery.sql.driver.config.SQLRemoteDatabaseDriverConfig;
import net.pretronic.libraries.utility.Validate;
import net.pretronic.libraries.utility.reflect.ReflectionUtil;

import javax.sql.DataSource;
import java.util.concurrent.TimeUnit;

public class HikariSQLDataSourceFactory implements SQLDataSourceFactory {

    @Override
    public DataSource createDataSource(SQLDatabaseDriver driver, String database) {
        SQLDatabaseDriverConfig<?> config = driver.getConfig();
        HikariConfig hikariConfig = new HikariConfig();
        Validate.notNull(ReflectionUtil.getFieldValue(hikariConfig.getClass(), "LOGGER"), "No SLF4J logger set for HikariCP");
        hikariConfig.setPoolName(driver.getName());
        if(driver.getDialect().getEnvironment() == DatabaseDriverEnvironment.LOCAL) {
            String jdbcUrl = config.getConnectionString();
            if(jdbcUrl == null) {
                jdbcUrl = driver.getDialect().createConnectionString(null, ((SQLLocalDatabaseDriverConfig)config).getLocation());
            }
            hikariConfig.setJdbcUrl(String.format(jdbcUrl, database));
        } else {
            String jdbcUrl = config.getConnectionString();
            if(jdbcUrl == null) {
                jdbcUrl = driver.getDialect().createConnectionString(null, ((SQLRemoteDatabaseDriverConfig)config).getAddress());
            }
            hikariConfig.setJdbcUrl(String.format(jdbcUrl, database));
        }
        if(config instanceof SQLRemoteDatabaseDriverConfig) {
            SQLRemoteDatabaseDriverConfig remoteConfig = (SQLRemoteDatabaseDriverConfig) config;
            if(remoteConfig.getUsername() != null) hikariConfig.setUsername(remoteConfig.getUsername());
            if(remoteConfig.getPassword() != null) hikariConfig.setPassword(remoteConfig.getPassword());
        }
        boolean isMySql = config.getDialect().equals(Dialect.MYSQL);
        boolean isMariaDb = config.getDialect().equals(Dialect.MARIADB);

        // Compute the MariaDB sslMode value for use in both dialect branches.
        // This is needed because MariaDB Connector/J 3.x can also handle jdbc:mysql:// URLs
        // in plugin classloader environments, but it only understands sslMode (not useSSL).
        String mariaDbSslMode;
        if(!config.isUseSSL()) {
            mariaDbSslMode = "disable";
        } else if(config.isTrustServerCertificate()) {
            mariaDbSslMode = "trust";
        } else {
            mariaDbSslMode = "verify-full";
        }

        if(isMariaDb) {
            // MariaDB Connector/J 3.x: use sslMode instead of deprecated useSSL property
            hikariConfig.addDataSourceProperty("sslMode", mariaDbSslMode);
        } else {
            hikariConfig.addDataSourceProperty("useSSL", config.isUseSSL());
            if(isMySql && config.isUseSSL()) {
                boolean trustServerCertificate = config.isTrustServerCertificate();
                hikariConfig.addDataSourceProperty("enabledTLSProtocols", "TLSv1.2");
                hikariConfig.addDataSourceProperty("trustServerCertificate", trustServerCertificate);
                hikariConfig.addDataSourceProperty("verifyServerCertificate", !trustServerCertificate);
                // Append to JDBC URL for older MySQL 5.x drivers that ignore DataSource properties
                String currentUrl = hikariConfig.getJdbcUrl();
                if(currentUrl != null && !currentUrl.contains("enabledTLSProtocols")) {
                    String separator = currentUrl.contains("?") ? "&" : "?";
                    hikariConfig.setJdbcUrl(currentUrl + separator
                            + "enabledTLSProtocols=TLSv1.2&verifyServerCertificate=" + !trustServerCertificate);
                }
            }
        }

        // Always append sslMode to the JDBC URL for MariaDB Connector/J compatibility.
        // MariaDB Connector/J 3.x can handle both jdbc:mariadb:// and jdbc:mysql:// URLs
        // in plugin classloader environments, but ignores MySQL-style useSSL properties.
        // Without an explicit sslMode in the URL the driver falls back to its internal
        // default, which can trigger PKIX certificate validation errors on Java 8.
        {
            String currentUrl = hikariConfig.getJdbcUrl();
            if(currentUrl != null && !currentUrl.contains("sslMode")) {
                String separator = currentUrl.contains("?") ? "&" : "?";
                hikariConfig.setJdbcUrl(currentUrl + separator + "sslMode=" + mariaDbSslMode);
            }
        }

        // When SSL is enabled, restrict protocol to TLSv1.2 for Java 8 compatibility.
        // Java 8 does not support TLS 1.3 and may fail the SSL handshake without this.
        if((isMariaDb || isMySql) && config.isUseSSL()) {
            String currentUrl = hikariConfig.getJdbcUrl();
            if(currentUrl != null && !currentUrl.contains("enabledSslProtocolSuites")) {
                String separator = currentUrl.contains("?") ? "&" : "?";
                hikariConfig.setJdbcUrl(currentUrl + separator + "enabledSslProtocolSuites=TLSv1.2");
            }
        }
        if(config.getConnectionCatalog() != null) hikariConfig.setCatalog(config.getConnectionCatalog());
        if(config.getConnectionSchema() != null) hikariConfig.setSchema(config.getConnectionSchema());
        hikariConfig.setAutoCommit(false);
        hikariConfig.setReadOnly(config.isConnectionReadOnly());
        long connectionExpire = config.getDataSourceConnectionExpire();
        //@Todo custom max/min config options for every dialect
        if((isMySql || isMariaDb) && connectionExpire > TimeUnit.MINUTES.toMillis(5)) {
            connectionExpire = TimeUnit.MINUTES.toMillis(5);
        }
        if(connectionExpire != 0) {
            hikariConfig.setMaxLifetime(connectionExpire);
        }

        long idleTimeout = config.getDataSourceConnectionExpireAfterAccess();
        if(idleTimeout != 0) {
            // Ensure idleTimeout is less than maxLifetime to prevent HikariCP warnings
            if(connectionExpire != 0 && idleTimeout >= connectionExpire) {
                idleTimeout = connectionExpire - TimeUnit.SECONDS.toMillis(30);
                if(idleTimeout <= 0) idleTimeout = TimeUnit.SECONDS.toMillis(10);
            }
            hikariConfig.setIdleTimeout(idleTimeout);
        }
        if(config.getDataSourceConnectionLoginTimeout() != 0) hikariConfig.setConnectionTimeout(config.getDataSourceConnectionLoginTimeout());
        if(config.getDataSourceMaximumPoolSize() != 0) hikariConfig.setMaximumPoolSize(config.getDataSourceMaximumPoolSize());
        if(config.getDataSourceMinimumIdleConnectionPoolSize() != 0) hikariConfig.setMinimumIdle(config.getDataSourceMinimumIdleConnectionPoolSize());

        if(config.getConnectionIsolationLevel() != 0) hikariConfig.setTransactionIsolation(convertToHikariIsolationLevel(config.getConnectionIsolationLevel()));
        hikariConfig.addDataSourceProperty("dataSourceProperties", "transactionIsolation=READ_COMMITTED");

        return new HikariDataSource(hikariConfig);
    }

    private static String convertToHikariIsolationLevel(int level) {
        for (IsolationLevel value : IsolationLevel.values()) {
            if(value.getLevelId() == level) return value.name();
        }
        return null;
    }
}
