package com.admtechhub.maestrohr.config;

import com.admtechhub.maestrohr.auth.TenantContext;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

@Component
public class HibernateRLSInterceptor implements StatementInspector {

    @Override
    public String inspect(String sql) {
        return sql;
    }
}