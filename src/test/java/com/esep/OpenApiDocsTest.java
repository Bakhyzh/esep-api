package com.esep;

import com.esep.support.IntegrationTest;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Guards the public API surface: adding, removing or renaming an endpoint must be a conscious change
 * to the expected list below (the frontend depends on it).
 */
@AutoConfigureMockMvc
class OpenApiDocsTest extends IntegrationTest {

    private static final Set<String> EXPECTED_OPERATIONS = new TreeSet<>(List.of(
            "POST /api/auth/register",
            "POST /api/auth/login",
            "GET /api/auth/me",
            "GET /api/accounts",
            "POST /api/accounts",
            "GET /api/accounts/{id}",
            "POST /api/accounts/{id}/close",
            "POST /api/deposits",
            "POST /api/transfers",
            "GET /api/transactions",
            "GET /api/transactions/{id}",
            "GET /api/analytics/spending",
            "GET /api/analytics/top-transactions",
            "GET /api/analytics/moving-average",
            "GET /api/analytics/monthly-comparison",
            "GET /api/notifications"));

    @Autowired
    private MockMvc mvc;

    @Test
    void apiDocsArePublicAndDescribeJwtSecurity() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("Esep API"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
                .andExpect(jsonPath("$.components.schemas.ApiError.properties.code").exists());
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyEndpointIsDocumentedWithSummaryAndErrors() throws Exception {
        String json = mvc.perform(get("/v3/api-docs")).andReturn().getResponse().getContentAsString();
        Map<String, Map<String, Object>> paths = JsonPath.read(json, "$.paths");

        Set<String> actual = new TreeSet<>();
        paths.forEach((path, operations) -> operations.forEach((method, op) -> {
            actual.add(method.toUpperCase() + " " + path);
            Map<String, Object> operation = (Map<String, Object>) op;
            assertThat(operation.get("summary")).as("summary of %s %s", method, path).isNotNull();
            Map<String, Object> responses = (Map<String, Object>) operation.get("responses");
            assertThat(responses).as("error responses of %s %s", method, path).containsKeys("400", "500");
        }));
        assertThat(actual).isEqualTo(EXPECTED_OPERATIONS);
    }

    @Test
    void publicEndpointsNeedNoToken_protectedOnesDo() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath("$.paths['/api/auth/login'].post.security").isEmpty())
                .andExpect(jsonPath("$.paths['/api/auth/login'].post.responses['401']").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/transfers'].post.responses['401']").exists())
                .andExpect(jsonPath("$.paths['/api/transfers'].post.responses['422'].content['application/problem+json']").exists())
                .andExpect(jsonPath("$.paths['/api/transfers'].post.parameters[?(@.name == 'Idempotency-Key')].description").exists());
    }

    @Test
    void swaggerUiIsPublic() throws Exception {
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
    }
}
