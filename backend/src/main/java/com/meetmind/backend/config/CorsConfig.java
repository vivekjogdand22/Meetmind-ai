package com.meetmind.backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;


// ============================================================
// WHICH WEBSITES MAY CALL THE API FROM A BROWSER (CORS)
//
// The React app runs on its own address (port 5173), so the browser
// only lets it talk to this backend if the backend says so. Vite shows
// the address as "localhost", but people also open 127.0.0.1, so both
// are allowed, plus the preview server (port 4173).
//
// To allow another address, set CORS_ALLOWED_ORIGINS to a
// comma-separated list, for example:
//     CORS_ALLOWED_ORIGINS=http://localhost:5173,https://meetmind.example.com
// ============================================================

@Configuration
public class CorsConfig implements WebMvcConfigurer
{
    private final String[] allowedOrigins;


    public CorsConfig(
            @Value("${CORS_ALLOWED_ORIGINS:"
                    + "http://localhost:5173,http://127.0.0.1:5173,"
                    + "http://localhost:4173,http://127.0.0.1:4173}")
            String[] allowedOrigins)
    {
        this.allowedOrigins = allowedOrigins;
    }


    @Override
    public void addCorsMappings(CorsRegistry registry)
    {
        registry
                .addMapping("/api/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS");
    }
}
