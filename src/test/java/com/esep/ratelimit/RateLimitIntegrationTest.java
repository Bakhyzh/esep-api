package com.esep.ratelimit;

import com.esep.support.ProdFeaturesIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Limit is 3 requests per minute here (see ProdFeaturesIntegrationTest); every test uses its own client IP. */
class RateLimitIntegrationTest extends ProdFeaturesIntegrationTest {

    private static final String WRONG_LOGIN = "{\"email\": \"nobody@test.esep\", \"password\": \"wrong-password\"}";

    @Autowired
    private MockMvc mvc;

    @Test
    void loginIsBlockedAfterLimit_withRetryAfterAndCode() throws Exception {
        String ip = uniqueIp();
        for (int i = 0; i < 3; i++) {
            login(ip).andExpect(status().isUnauthorized());
        }

        login(ip)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.status").value(429));
    }

    @Test
    void limitIsPerClientIp() throws Exception {
        String attacker = uniqueIp();
        for (int i = 0; i < 4; i++) {
            login(attacker);
        }
        login(attacker).andExpect(status().isTooManyRequests());

        // another client is not affected by the attacker's attempts
        login(uniqueIp()).andExpect(status().isUnauthorized());
    }

    @Test
    void loginAndRegisterHaveSeparateCounters() throws Exception {
        String ip = uniqueIp();
        for (int i = 0; i < 3; i++) {
            login(ip);
        }
        login(ip).andExpect(status().isTooManyRequests());

        mvc.perform(post("/api/auth/register").with(fromIp(ip)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"" + UUID.randomUUID() + "@test.esep\", \"password\": \"password123\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void registerIsLimitedToo() throws Exception {
        String ip = uniqueIp();
        for (int i = 0; i < 3; i++) {
            register(ip).andExpect(status().isBadRequest());   // invalid body still counts as an attempt
        }
        register(ip).andExpect(status().isTooManyRequests());
    }

    private ResultActions login(String ip) throws Exception {
        return mvc.perform(post("/api/auth/login").with(fromIp(ip))
                .contentType(MediaType.APPLICATION_JSON).content(WRONG_LOGIN));
    }

    private ResultActions register(String ip) throws Exception {
        return mvc.perform(post("/api/auth/register").with(fromIp(ip))
                .contentType(MediaType.APPLICATION_JSON).content("{\"email\": \"bad\", \"password\": \"x\"}"));
    }

    private static RequestPostProcessor fromIp(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    // random 10.x.y.z address: counters live in Redis and survive between tests in the shared container
    private static String uniqueIp() {
        int n = UUID.randomUUID().hashCode() & 0xFFFFFF;
        return "10." + (n >> 16 & 0xFF) + "." + (n >> 8 & 0xFF) + "." + (n & 0xFF);
    }
}
