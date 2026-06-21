package com.example.reader.security;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcSecurityConfig implements WebMvcConfigurer {
    private final AdminApiInterceptor adminApiInterceptor;

    public WebMvcSecurityConfig(AdminApiInterceptor adminApiInterceptor) {
        this.adminApiInterceptor = adminApiInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(adminApiInterceptor).addPathPatterns("/api/admin/**");
    }
}
