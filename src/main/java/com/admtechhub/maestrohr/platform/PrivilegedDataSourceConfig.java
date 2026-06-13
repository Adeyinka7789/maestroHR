package com.admtechhub.maestrohr.platform;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * Phase E4b — the <em>privileged</em> datasource: a second connection pool used only by
 * the narrow set of bootstrap / cross-tenant read operations that must see every tenant's
 * rows (see {@link AuthBootstrapQueries}, {@link WebhookTenantResolver},
 * {@link SubscriptionSweepQueries}, {@link AdminStatsQueries}).
 *
 * <p>Two deliberate differences from the primary datasource in
 * {@code com.admtechhub.maestrohr.config.DataSourceConfig}:
 * <ul>
 *   <li><b>No {@code set_config} wrapper.</b> The primary wraps its pool in a
 *       {@code LazyConnectionDataSourceProxy} that binds {@code app.current_tenant} on every
 *       connection. This pool does not — its connections carry no tenant session.</li>
 *   <li><b>Its own credentials.</b> Today they default to the primary's postgres
 *       credentials, so this pool connects as the table owner / superuser and PostgreSQL
 *       bypasses RLS for it. It is bound separately ({@code privileged.datasource.*}) so it
 *       stays postgres after E4c flips the PRIMARY pool to the NOBYPASSRLS {@code maestro_app}
 *       role — that is the whole reason it exists.</li>
 * </ul>
 *
 * <p>This bean is intentionally <b>not</b> {@code @Primary}: JPA, the entity manager, and
 * every {@code @SQLRestriction}-scoped repository keep using the primary, RLS-bound pool.
 */
@Configuration
public class PrivilegedDataSourceConfig {

    /**
     * Bound to Hikari-native properties ({@code privileged.datasource.hikari.*}, so
     * {@code jdbc-url} rather than {@code url}). Deliberately does <b>not</b> introduce a
     * second {@code DataSourceProperties} bean — that would make the primary datasource's
     * {@code DataSourceProperties} injection ambiguous.
     */
    @Bean
    @Qualifier("privilegedDataSource")
    @ConfigurationProperties("privileged.datasource.hikari")
    public DataSource privilegedDataSource() {
        return DataSourceBuilder.create().type(HikariDataSource.class).build();
    }

    @Bean
    @Qualifier("privilegedJdbcTemplate")
    public JdbcTemplate privilegedJdbcTemplate(
            @Qualifier("privilegedDataSource") DataSource privilegedDataSource) {
        return new JdbcTemplate(privilegedDataSource);
    }
}
