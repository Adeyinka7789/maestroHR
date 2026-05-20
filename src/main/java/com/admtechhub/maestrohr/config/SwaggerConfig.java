package com.admtechhub.maestrohr.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.Contact;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SwaggerConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("MaestroHR API")
                        .version("1.0")
                        .description("HR & Payroll Management API")
                        .contact(new Contact()
                                .name("MaestroHR Support")
                                .email("support@maestrohr.com")));
    }
}

//access it via this link:http://localhost:8080/swagger-ui/index.html