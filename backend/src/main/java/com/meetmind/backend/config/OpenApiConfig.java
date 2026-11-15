package com.meetmind.backend.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


// ============================================================
// THE API DOCUMENTATION PAGE
//
// Start the backend and open http://localhost:8080/swagger-ui.html to read
// and try every endpoint. The raw description is at /v3/api-docs.
// ============================================================

@Configuration
public class OpenApiConfig
{
    @Bean
    public OpenAPI meetMindOpenApi()
    {
        // "Authorize" button on the Swagger page: log in via /api/auth/login,
        // paste the token, and every request carries it.
        return new OpenAPI()
                .components(
                        new Components()
                                .addSecuritySchemes(
                                        "bearerAuth",
                                        new SecurityScheme()
                                                .type(SecurityScheme.Type.HTTP)
                                                .scheme("bearer")
                                                .bearerFormat("JWT")
                                )
                )
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"))
                .info(
                        new Info()
                                .title("MeetMind AI API")
                                .version("1.0")
                                .description(
                                        "Upload meeting recordings and get a transcript, "
                                                + "summary, decisions and action items. "
                                                + "Turn action items into a PERT/CPM project plan, "
                                                + "and ask questions that are answered only "
                                                + "from a meeting's transcript. Log in first: every meeting belongs to one account."
                                )
                );
    }
}
