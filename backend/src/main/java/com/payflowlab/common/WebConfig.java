package com.payflowlab.common;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final List<String> allowedOrigins;

    public WebConfig(@Value("${payflow.cors.allowed-origins}") List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    /** CORS is an explicit allow-list from configuration, never a wildcard. */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins.toArray(String[]::new))
                .allowedMethods("GET", "POST", "PUT", "OPTIONS")
                .allowedHeaders("Content-Type", "Idempotency-Key", "Accept")
                .exposedHeaders("Idempotent-Replay", "Content-Disposition")
                .maxAge(3600);
    }

    @Bean
    OpenAPI payflowOpenApi() {
        return new OpenAPI().info(new Info()
                .title("PayFlow Lab API")
                .version("v1")
                .description("Educational simulator of card payment operations: authorization, capture, refunds, "
                        + "fee assessment, double-entry ledger, settlement batching and reconciliation. "
                        + "This is NOT a payment gateway. It connects to no card network, processes no real "
                        + "money and must never be given real card data.")
                .license(new License().name("MIT")));
    }
}
