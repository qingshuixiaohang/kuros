package com.kuros.kurosbackend.config;

import com.kuros.kurosbackend.storage.LocalImageStorageService;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class MediaResourceConfig implements WebMvcConfigurer {

    private final LocalImageStorageService imageStorageService;

    public MediaResourceConfig(LocalImageStorageService imageStorageService) {
        this.imageStorageService = imageStorageService;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/media/**")
                .addResourceLocations(imageStorageService.root().toUri().toString());
    }
}
