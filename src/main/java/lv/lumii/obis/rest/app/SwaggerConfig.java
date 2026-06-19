package lv.lumii.obis.rest.app;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import springfox.documentation.builders.ApiInfoBuilder;
import springfox.documentation.builders.PathSelectors;
import springfox.documentation.builders.RequestHandlerSelectors;
import springfox.documentation.service.ApiInfo;
import springfox.documentation.service.Tag;
import springfox.documentation.spi.DocumentationType;
import springfox.documentation.spring.web.plugins.Docket;
import springfox.documentation.swagger2.annotations.EnableSwagger2;

/**
 * Swagger 2 configuration for the Spring REST web services.
 * It describes and documents RESTful APIs.
 * Swagger UI is used for user interactions with the Swagger-generated API resources.
 * <p>
 * REST API docs - http://<server>:<port>/v2/api-docs
 * Swagger UI - http://<server>:<port>/swagger-ui.html
 */
@Configuration
@EnableSwagger2
public class SwaggerConfig {

    @Bean
    public Docket currentApi() {
        return new Docket(DocumentationType.SWAGGER_2)
                .groupName("Schema Extractor API - Current V2")
                .select()
                .apis(RequestHandlerSelectors.basePackage("lv.lumii.obis.rest.app"))
                .paths(PathSelectors.regex("/schema-extractor-rest/v2/.*"))
                .build()
                .useDefaultResponseMessages(false)
                .apiInfo(currentApiInfo())
                .tags(new Tag("Services V2", ""));
    }

    @Bean
    public Docket archivedApi() {
        return new Docket(DocumentationType.SWAGGER_2)
                .groupName("Schema Extractor API - Deprecated V1")
                .select()
                .apis(RequestHandlerSelectors.basePackage("lv.lumii.obis.rest.app"))
                .paths(PathSelectors.regex("/schema-extractor-rest/v1/.*"))
                .build()
                .useDefaultResponseMessages(false)
                .apiInfo(archiveApiInfo())
                .tags(new Tag("Services V1", ""));
    }

    private ApiInfo currentApiInfo() {
        return new ApiInfoBuilder()
                .title("Schema Extractor REST Services API - V2")
                .license("Apache 2.0")
                .licenseUrl("http://www.apache.org/licenses/LICENSE-2.0.html")
                .version("2.0")
                .build();
    }

    private ApiInfo archiveApiInfo() {
        return new ApiInfoBuilder()
                .title("Schema Extractor REST Services API - V1 - Deprecated")
                .description("Deprecated/Archived Schema Extractor API documentation (V1). Use V2 services instead.")
                .license("Apache 2.0")
                .licenseUrl("http://www.apache.org/licenses/LICENSE-2.0.html")
                .version("1.0")
                .build();
    }

}