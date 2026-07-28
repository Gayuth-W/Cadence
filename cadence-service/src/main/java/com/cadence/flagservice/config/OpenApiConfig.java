package com.cadence.flagservice.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI cadenceOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Cadence Progressive Delivery API")
                        .version("1.0.0")
                        .description("""
                                Two authentication planes:
                                * **Control plane** (`/api/v1/**`) — human operators, `Authorization: Bearer <JWT>`, RBAC via VIEWER / OPERATOR / ADMIN.
                                * **Data plane** (`/sdk/v1/**`) — applications, `X-Cadence-Api-Key: <service key>`, scoped to read flags + write events.
                                """))
                .components(new Components()
                        .addSecuritySchemes("bearerAuth", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT"))
                        .addSecuritySchemes("apiKey", new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("X-Cadence-Api-Key")))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
    }
}
