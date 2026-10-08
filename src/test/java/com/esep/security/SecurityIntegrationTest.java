package com.esep.security;

import com.esep.support.IntegrationTest;
import com.esep.user.Role;
import com.esep.user.User;
import com.esep.user.UserRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class SecurityIntegrationTest extends IntegrationTest {

    private static final String PASSWORD = "password123";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JwtEncoder jwtEncoder;

    @Test
    void protectedEndpointWithoutToken_returns401ProblemJson() throws Exception {
        mvc.perform(get("/api/accounts"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("Unauthorized"));
    }

    @Test
    void garbageToken_returns401() throws Exception {
        mvc.perform(get("/api/accounts").header(HttpHeaders.AUTHORIZATION, "Bearer not.a.jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void expiredToken_returns401() throws Exception {
        Instant past = Instant.now().minusSeconds(7200);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("esep-api").subject("1").claim("role", "USER")
                .issuedAt(past).expiresAt(past.plusSeconds(60))
                .build();
        String expired = jwtEncoder.encode(
                JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();

        mvc.perform(get("/api/accounts").header(HttpHeaders.AUTHORIZATION, "Bearer " + expired))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void registerLoginAndMe_happyPath() throws Exception {
        String email = uniqueEmail();
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json(email, PASSWORD)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());

        String token = login(email);

        mvc.perform(authorized(get("/api/auth/me"), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email));
    }

    @Test
    void loginWithWrongPassword_returns401() throws Exception {
        String email = registeredUser(Role.USER);

        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json(email, "wrong-password")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Invalid email or password"));
    }

    @Test
    void userSeesOwnAccountButNotSomebodyElses() throws Exception {
        String alice = login(registeredUser(Role.USER));
        String bob = login(registeredUser(Role.USER));
        long aliceAccount = createAccount(alice, "KZT");

        mvc.perform(authorized(get("/api/accounts/" + aliceAccount), alice))
                .andExpect(status().isOk());
        mvc.perform(authorized(get("/api/accounts/" + aliceAccount), bob))
                .andExpect(status().isNotFound());
    }

    @Test
    void regularUserCannotListSomebodyElsesAccounts() throws Exception {
        String alice = login(registeredUser(Role.USER));
        long bobId = userRepository.findByEmail(registeredUser(Role.USER)).orElseThrow().getId();

        mvc.perform(authorized(get("/api/accounts").param("userId", String.valueOf(bobId)), alice))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("Forbidden"));
    }

    @Test
    void depositIsAdminOnly() throws Exception {
        String user = login(registeredUser(Role.USER));
        String admin = login(registeredUser(Role.ADMIN));
        long account = createAccount(user, "KZT");
        String body = "{\"accountId\": " + account + ", \"amount\": 100}";

        mvc.perform(authorized(post("/api/deposits"), user).header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mvc.perform(authorized(post("/api/deposits"), admin).header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    @Test
    void transferFromSomebodyElsesAccount_returns404AndTransactionIsHidden() throws Exception {
        String admin = login(registeredUser(Role.ADMIN));
        String alice = login(registeredUser(Role.USER));
        String bob = login(registeredUser(Role.USER));
        String mallory = login(registeredUser(Role.USER));
        long aliceAccount = createAccount(alice, "KZT");
        long bobAccount = createAccount(bob, "KZT");
        createAccount(mallory, "KZT");

        String depositResponse = mvc.perform(authorized(post("/api/deposits"), admin)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\": " + aliceAccount + ", \"amount\": 100}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long depositId = ((Number) JsonPath.read(depositResponse, "$.id")).longValue();

        // bob tries to spend alice's money
        mvc.perform(authorized(post("/api/transfers"), bob).header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromAccountId\": " + aliceAccount + ", \"toAccountId\": " + bobAccount
                                + ", \"amount\": 50}"))
                .andExpect(status().isNotFound());

        mvc.perform(authorized(get("/api/transactions/" + depositId), alice)).andExpect(status().isOk());
        mvc.perform(authorized(get("/api/transactions/" + depositId), mallory)).andExpect(status().isNotFound());
        mvc.perform(authorized(get("/api/accounts/" + aliceAccount), alice))
                .andExpect(jsonPath("$.balance").value(100.0));
    }

    // --- helpers ---

    private String registeredUser(Role role) {
        String email = uniqueEmail();
        userRepository.save(new User(email, passwordEncoder.encode(PASSWORD), role));
        return email;
    }

    private String login(String email) throws Exception {
        String response = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json(email, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.accessToken");
    }

    private long createAccount(String token, String currency) throws Exception {
        String response = mvc.perform(authorized(post("/api/accounts"), token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currency\": \"" + currency + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(response, "$.id")).longValue();
    }

    private static MockHttpServletRequestBuilder authorized(MockHttpServletRequestBuilder request, String token) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private static String json(String email, String password) {
        return "{\"email\": \"" + email + "\", \"password\": \"" + password + "\"}";
    }

    private static String uniqueEmail() {
        return UUID.randomUUID() + "@test.esep";
    }
}
