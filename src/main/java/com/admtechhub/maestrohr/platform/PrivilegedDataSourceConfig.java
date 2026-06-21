package com.admtechhub.maestrohr.platform;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

/**
 * Phase E4b — the <em>privileged</em> datasource: a non-pooled, superuser-credentialed
 * datasource used only by the narrow set of bootstrap / cross-tenant operations that must
 * see every tenant's rows (see {@link AuthBootstrapQueries}, {@link WebhookTenantResolver},
 * {@link SubscriptionSweepQueries}, {@link AdminStatsQueries}, {@link AuditTrailWrites}).
 *
 * <p>Two deliberate differences from the primary datasource in
 * {@code com.admtechhub.maestrohr.config.DataSourceConfig}:
 * <ul>
 *   <li><b>No {@code set_config} wrapper.</b> The primary wraps its pool in a
 *       {@code LazyConnectionDataSourceProxy} that binds {@code app.current_tenant} on every
 *       connection. This datasource does not — its connections carry no tenant session.</li>
 *   <li><b>Its own credentials.</b> They default to the postgres superuser credentials, so
 *       PostgreSQL bypasses RLS for connections from this datasource. It stays postgres
 *       after E4c flips the PRIMARY datasource to the NOBYPASSRLS {@code maestro_app} role —
 *       that is the whole reason it exists.</li>
 * </ul>
 *
 * <p>This bean is intentionally <b>not</b> {@code @Primary}: JPA, the entity manager, and
 * every {@code @SQLRestriction}-scoped repository keep using the primary, RLS-bound pool.
 */
@Configuration
public class PrivilegedDataSourceConfig {

    /**
     * Non-pooled on purpose: all consumers are infrequent one-shot operations (login lookups,
     * webhook resolution, audit writes, admin stats) that don't benefit from a persistent pool.
     * A {@link DriverManagerDataSource} opens a connection only when called and closes it
     * immediately after — leaving no standing footprint. This mirrors the fix applied to
     * {@link com.admtechhub.maestrohr.config.FlywayConfig}: with Spring's test-context caching,
     * each cached context keeps its own Hikari pool alive, and a primary + privileged Hikari pool
     * per context exhausts PostgreSQL's {@code max_connections} in the full test suite.
     */
    @Bean
    @Qualifier("privilegedDataSource")
    public DataSource privilegedDataSource(
            @Value("${PRIVILEGED_DB_URL:${DB_URL}}") String url,
            @Value("${PRIVILEGED_DB_USERNAME:${DB_USERNAME}}") String username,
            @Value("${PRIVILEGED_DB_PASSWORD:${DB_PASSWORD}}") String password) {
        DriverManagerDataSource ds = new DriverManagerDataSource(url, username, password);
        ds.setDriverClassName("org.postgresql.Driver");
        return ds;
    }

    @Bean
    @Qualifier("privilegedJdbcTemplate")
    public JdbcTemplate privilegedJdbcTemplate(
            @Qualifier("privilegedDataSource") DataSource privilegedDataSource) {
        return new JdbcTemplate(privilegedDataSource);
    }
}
