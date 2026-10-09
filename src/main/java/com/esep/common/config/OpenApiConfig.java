package com.esep.common.config;

import com.esep.common.exception.ApiErrorDoc;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.Map;

// adds the "Authorize" button to Swagger UI: paste the accessToken from /api/auth/login there
@Configuration
@OpenAPIDefinition(info = @Info(title = "Esep API", version = "v1",
        description = "Wallets and transfers on a double-entry ledger. Errors: application/problem+json "
                + "with a stable `code` (see the ApiError schema)."),
        security = @SecurityRequirement(name = "bearerAuth"))
@SecurityScheme(name = "bearerAuth", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "JWT")
public class OpenApiConfig {

    private static final String ERROR_REF = "#/components/schemas/ApiError";

    private static final Map<String, String> COMMON_ERRORS = new LinkedHashMap<>();

    static {
        COMMON_ERRORS.put("400", "Validation failed or invalid parameters (VALIDATION_FAILED, INVALID_REQUEST, ...)");
        COMMON_ERRORS.put("401", "Missing, invalid or expired token (UNAUTHORIZED)");
        COMMON_ERRORS.put("403", "Authenticated, but not allowed (FORBIDDEN)");
        COMMON_ERRORS.put("404", "Resource not found or belongs to another user (RESOURCE_NOT_FOUND)");
        COMMON_ERRORS.put("409", "Conflict or concurrent modification, retry (CONFLICT, CONCURRENT_MODIFICATION, ...)");
        COMMON_ERRORS.put("422", "Business rule violated (INSUFFICIENT_FUNDS, CURRENCY_MISMATCH, ...)");
    }

    /** Registers the ApiError schema and documents the common error responses on every operation. */
    @Bean
    OpenApiCustomizer errorResponsesCustomizer() {
        return openApi -> {
            ModelConverters.getInstance().readAll(ApiErrorDoc.class)
                    .forEach((name, schema) -> openApi.getComponents().addSchemas(name, schema));
            openApi.getPaths().values().forEach(path -> path.readOperations().forEach(operation -> {
                boolean isPublic = operation.getSecurity() != null && operation.getSecurity().isEmpty();
                COMMON_ERRORS.forEach((status, description) -> {
                    if (isPublic && (status.equals("401") || status.equals("403"))) {
                        return;
                    }
                    addIfAbsent(operation, status, description);
                });
                addIfAbsent(operation, "500", "Unexpected error (INTERNAL_ERROR)");
            }));
        };
    }

    private static void addIfAbsent(Operation operation, String status, String description) {
        if (operation.getResponses().containsKey(status)) {
            return;
        }
        Content content = new Content().addMediaType("application/problem+json",
                new MediaType().schema(new Schema<>().$ref(ERROR_REF)));
        operation.getResponses().addApiResponse(status, new ApiResponse().description(description).content(content));
    }
}
