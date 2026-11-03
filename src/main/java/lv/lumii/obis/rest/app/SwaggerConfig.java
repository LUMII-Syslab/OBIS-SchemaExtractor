package lv.lumii.obis.rest.app;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springdoc.core.properties.SwaggerUiConfigProperties;
import org.springdoc.core.properties.SwaggerUiOAuthProperties;
import org.springdoc.core.providers.ObjectMapperProvider;
import org.springdoc.webmvc.ui.SwaggerIndexPageTransformer;
import org.springdoc.webmvc.ui.SwaggerIndexTransformer;
import org.springdoc.webmvc.ui.SwaggerWelcomeCommon;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.resource.TransformedResource;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

/** OpenAPI documentation and Swagger UI for the current and archived services. */
@Configuration
public class SwaggerConfig {
    @Bean
    public GroupedOpenApi currentApi() {
        return GroupedOpenApi.builder()
                .group("current-v2")
                .displayName("Schema Extractor API - Current V2")
                .packagesToScan("lv.lumii.obis.rest.app")
                .pathsToMatch("/schema-extractor-rest/v2/**")
                .addOpenApiCustomizer(api -> api.info(apiInfo("V2", "2.0")))
                .addOperationCustomizer(requestDocumentation())
                .build();
    }

    @Bean
    public GroupedOpenApi archivedApi() {
        return GroupedOpenApi.builder()
                .group("deprecated-v1")
                .displayName("Schema Extractor API - Deprecated V1")
                .packagesToScan("lv.lumii.obis.rest.app")
                .pathsToMatch("/schema-extractor-rest/v1/**")
                .addOpenApiCustomizer(api -> api.info(apiInfo("V1 - Deprecated", "1.0")
                        .description("Deprecated/Archived Schema Extractor API documentation (V1). Use V2 services instead.")))
                .addOperationCustomizer(requestDocumentation())
                .build();
    }

    @Bean
    public OpenAPI schemaExtractorOpenApi() {
        return new OpenAPI().info(apiInfo("V2", "2.0"));
    }

    /** Keep the title links out of the UI while retaining the OpenAPI metadata. */
    @Bean
    public SwaggerIndexTransformer swaggerUiPresentation(SwaggerUiConfigProperties uiConfig,
                                                         SwaggerUiOAuthProperties oauthConfig,
                                                         SwaggerWelcomeCommon welcome,
                                                         ObjectMapperProvider mapperProvider) {
        SwaggerIndexPageTransformer defaults = new SwaggerIndexPageTransformer(uiConfig, oauthConfig, welcome, mapperProvider);
        return (request, resource, chain) -> {
            var transformed = defaults.transform(request, resource, chain);
            if (!"index.html".equals(resource.getFilename())) {
                return transformed;
            }
            try (var input = transformed.getInputStream()) {
                String html = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                String style = "<style>.swagger-ui .info .main > a, .swagger-ui .info .info__license { display: none; }</style>";
                return new TransformedResource(transformed,
                        html.replace("</head>", style + "</head>").getBytes(StandardCharsets.UTF_8));
            }
        };
    }

    private Info apiInfo(String versionLabel, String version) {
        return new Info().title("Schema Extractor REST Services API - " + versionLabel)
                .version(version)
                .license(new License().name("Apache 2.0")
                        .url("https://www.apache.org/licenses/LICENSE-2.0.html"));
    }

    /** Document file uploads and keep form order without parsing optional numeric metadata. */
    private OperationCustomizer requestDocumentation() {
        return (operation, handlerMethod) -> {
            // Keep accepting existing query/form requests while presenting file controls as multipart in Swagger UI.
            boolean hasFileUpload = Arrays.stream(handlerMethod.getMethodParameters())
                    .anyMatch(parameter -> MultipartFile.class.isAssignableFrom(parameter.getParameterType()));
            if (hasFileUpload && operation.getRequestBody() != null
                    && operation.getRequestBody().getContent() != null) {
                operation.getRequestBody().getContent().values().stream()
                        .filter(content -> content.getSchema() != null)
                        .findFirst()
                        .ifPresent(content -> operation.getRequestBody().setContent(
                                new io.swagger.v3.oas.models.media.Content()
                                        .addMediaType(MediaType.MULTIPART_FORM_DATA_VALUE, content)));
            }
            if (operation.getParameters() == null) {
                return operation;
            }
            Map<String, Integer> order = new LinkedHashMap<>();
            for (org.springframework.core.MethodParameter parameter : handlerMethod.getMethodParameters()) {
                if (parameter.hasParameterAnnotation(ModelAttribute.class)) {
                    for (Field field : parameter.getParameterType().getDeclaredFields()) {
                        order.putIfAbsent(field.getName(), order.size());
                    }
                } else {
                    RequestParam requestParam = parameter.getParameterAnnotation(RequestParam.class);
                    String name = requestParam != null && !requestParam.value().isEmpty()
                            ? requestParam.value() : parameter.getParameterName();
                    if (name != null) {
                        order.putIfAbsent(name, order.size());
                    }
                }
            }
            operation.getParameters().sort(Comparator.comparingInt(
                    parameter -> order.getOrDefault(parameter.getName(), Integer.MAX_VALUE)));
            return operation;
        };
    }
}
