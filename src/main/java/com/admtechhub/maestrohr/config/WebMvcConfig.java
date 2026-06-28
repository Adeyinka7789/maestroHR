package com.admtechhub.maestrohr.config;

import com.admtechhub.maestrohr.audit.AuditTrailInterceptor;
import jakarta.servlet.Filter;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final AuditTrailInterceptor auditTrailInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(auditTrailInterceptor);
    }

    /** Allows the service worker at /js/sw.js to claim scope "/" instead of just "/js/". */
    @Bean
    public FilterRegistrationBean<Filter> serviceWorkerScopeFilter() {
        FilterRegistrationBean<Filter> bean = new FilterRegistrationBean<>();
        bean.setFilter((req, res, chain) -> {
            ((jakarta.servlet.http.HttpServletResponse) res).setHeader("Service-Worker-Allowed", "/");
            chain.doFilter(req, res);
        });
        bean.addUrlPatterns("/js/sw.js");
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return bean;
    }
}
