package lv.lumii.obis.rest.app;

import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI (Swagger) configuration for the Spring REST web services.
 * It describes and documents RESTful APIs.
 * Swagger UI is used for user interactions with the generated API resources.
 * <p>
 * REST API docs - http://<server>:<port>/v3/api-docs/{group}
 * Swagger UI - http://<server>:<port>/swagger-ui.html
 */
@Configuration
public class SwaggerConfig {

    @Bean
    public GroupedOpenApi currentApi() {
        return GroupedOpenApi.builder()
                .group("v2")
                .displayName("Schema Extractor API - Current V2")
                .packagesToScan("lv.lumii.obis.rest.app")
                .pathsToMatch("/schema-extractor-rest/v2/**")
                .addOpenApiCustomizer(openApi -> openApi.info(currentApiInfo()))
                .build();
    }

    @Bean
    public GroupedOpenApi archivedApi() {
        return GroupedOpenApi.builder()
                .group("v1")
                .displayName("Schema Extractor API - Deprecated V1")
                .packagesToScan("lv.lumii.obis.rest.app")
                .pathsToMatch("/schema-extractor-rest/v1/**")
                .addOpenApiCustomizer(openApi -> openApi.info(archiveApiInfo()))
                .build();
    }

    private Info currentApiInfo() {
        return new Info()
                .title("Schema Extractor REST Services API - V2")
                .license(apacheLicense())
                .version("2.0");
    }

    private Info archiveApiInfo() {
        return new Info()
                .title("Schema Extractor REST Services API - V1 - Deprecated")
                .description("Deprecated/Archived Schema Extractor API documentation (V1). Use V2 services instead.")
                .license(apacheLicense())
                .version("1.0");
    }

    private License apacheLicense() {
        return new License()
                .name("Apache 2.0")
                .url("http://www.apache.org/licenses/LICENSE-2.0.html");
    }

}
