package com.admtechhub.maestrohr.config;

import com.admtechhub.maestrohr.auth.TenantContext;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

@Configuration
public class DataSourceConfig {

    @Bean
    @ConfigurationProperties(prefix = "spring.datasource.hikari")
    public HikariDataSource hikariDataSource(DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
    }

    @Bean
    @Primary
    public DataSource dataSource(HikariDataSource hikariDataSource) {
        return new LazyConnectionDataSourceProxy(hikariDataSource) {
            @Override
            public Connection getConnection() throws SQLException {
                Connection conn = super.getConnection();
                setTenantContext(conn);
                return conn;
            }

            @Override
            public Connection getConnection(String u, String p) throws SQLException {
                Connection conn = super.getConnection(u, p);
                setTenantContext(conn);
                return conn;
            }

            // Always write app.current_tenant, even when there is no tenant (writes '').
            // This clears any stale value left by a previous request on the same pooled
            // connection. The RLS policies use NULLIF(..., '') so an empty string is
            // treated the same as "no tenant" — no rows are visible.
            private void setTenantContext(Connection conn) throws SQLException {
                String tenant = TenantContext.getCurrentTenant();
                String value  = (tenant != null && !tenant.isBlank()) ? tenant : "";
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT set_config('app.current_tenant', ?, false)")) {
                    ps.setString(1, value);
                    ps.execute();
                }
            }
        };
    }
}
