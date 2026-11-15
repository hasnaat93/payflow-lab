package com.payflowlab.common;

import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * Serves the built single-page app from the backend, so the whole product can run as one
 * process behind one port. Off unless {@code payflow.spa.location} is set: the default
 * deployment keeps nginx in front (see frontend/Dockerfile).
 *
 * <p>A path that is not a file, such as /transactions/42 opened directly, is answered with
 * index.html so the client-side router can take over. API and documentation paths are left
 * alone, so an unknown API path is still a 404 with the JSON error body.
 */
@Configuration
@ConditionalOnProperty("payflow.spa.location")
public class SpaConfig implements WebMvcConfigurer {

    private static final String[] NOT_THE_APP = {"api/", "v3/", "swagger-ui", "actuator"};

    private final String location;

    public SpaConfig(@Value("${payflow.spa.location}") String location) {
        this.location = location.endsWith("/") ? location : location + "/";
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/").setViewName("forward:/index.html");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations(location)
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, Resource root) throws IOException {
                        Resource requested = root.createRelative(resourcePath);
                        if (requested.exists() && requested.isReadable()) {
                            return requested;
                        }
                        for (String prefix : NOT_THE_APP) {
                            if (resourcePath.startsWith(prefix)) {
                                return null;
                            }
                        }
                        return root.createRelative("index.html");
                    }
                });
    }
}
