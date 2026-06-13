package com.admtechhub.maestrohr.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.flyway.FlywayDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

/**
 * Phase E4c-ii — pin Flyway to the owner/superuser ({@code postgres}) datasource explicitly.
 *
 * <p>Flyway MUST run as the owner: it creates {@code flyway_schema_history} in schema
 * {@code public} (which the NOBYPASSRLS, non-owner {@code maestro_app} primary role cannot do on
 * a fresh database), runs the role management in {@code V24} (CREATE/ALTER ROLE, REVOKE on
 * {@code flyway_schema_history}), and owns all DDL.
 *
 * <p>Setting {@code spring.flyway.user}/{@code password} (and even {@code spring.flyway.url}) in
 * {@code application.yml} is NOT sufficient here: with a custom {@code @Primary} datasource present,
 * Spring Boot reuses that primary pool for Flyway and effectively ignores those properties — so
 * Flyway would connect as {@code maestro_app} and fail to bootstrap a fresh database. A
 * {@code @FlywayDataSource} bean is the unambiguous mechanism: Boot uses it verbatim. This was only
 * surfaced by booting with the {@code maestro_app} primary against a from-scratch DB; the test
 * suite never hit it because strategy A ({@code application-test.yml}) runs the primary as
 * {@code postgres}, so Flyway inherited the owner credentials for free.
 */
@Configuration
public class FlywayConfig {

    /**
     * Non-pooled on purpose: Flyway runs once at startup, so a persistent connection pool here
     * would just sit idle for the app's lifetime — and, multiplied across cached test contexts
     * (each already holding the primary + privileged Hikari pools), it exhausts PostgreSQL's
     * {@code max_connections} ("too many clients already"). {@link DriverManagerDataSource} opens
     * a connection only when Flyway asks and closes it right after, leaving no standing footprint.
     */
    @Bean
    @FlywayDataSource
    public DataSource flywayDataSource(
            @Value("${DB_URL}") String url,
            @Value("${DB_USERNAME}") String username,
            @Value("${DB_PASSWORD}") String password) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(url, username, password);
        dataSource.setDriverClassName("org.postgresql.Driver");
        return dataSource;
    }
}
