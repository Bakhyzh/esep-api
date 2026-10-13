package com.esep.api;

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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.net.URI;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The contract the frontend relies on: CORS, one error format with codes, paginated history. */
@AutoConfigureMockMvc
class ApiContractTest extends IntegrationTest {

    private static final String FRONTEND = "http://localhost:5173";
    private static final String PASSWORD = "password123";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;

    // ---------- CORS ----------

    @Test
    void preflightFromFrontend_isAllowedWithoutToken() throws Exception {
        mvc.perform(options("/api/transfers")
                        .header(HttpHeaders.ORIGIN, FRONTEND)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,content-type,idempotency-key"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, FRONTEND))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, containsString("idempotency-key")))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, containsString("POST")));
    }

    @Test
    void preflight_isCachedByTheBrowserForAnHour() throws Exception {
        // without Access-Control-Max-Age every POST from the UI costs an extra OPTIONS round trip
        mvc.perform(options("/api/transfers")
                        .header(HttpHeaders.ORIGIN, FRONTEND)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_MAX_AGE, "3600"))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
    }

    @Test
    void preflightFromUnknownOrigin_isRejected() throws Exception {
        mvc.perform(options("/api/accounts")
                        .header(HttpHeaders.ORIGIN, "https://evil.example")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    void actualRequestFromFrontend_exposesHeadersTheUiNeeds() throws Exception {
        String token = login(user(Role.USER));

        mvc.perform(authorized(get("/api/accounts"), token).header(HttpHeaders.ORIGIN, FRONTEND))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, FRONTEND))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, containsString("Idempotent-Replayed")));
    }

    @Test
    void errorResponsesAlsoCarryCorsHeaders() throws Exception {
        // otherwise the browser hides a 401 from JavaScript and the UI cannot react to an expired token
        mvc.perform(get("/api/accounts").header(HttpHeaders.ORIGIN, FRONTEND))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, FRONTEND));
    }

    // ---------- unified error format ----------

    @Test
    void validationError_listsFieldsWithCode() throws Exception {
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"not-an-email\", \"password\": \"short\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.detail").value("Request has invalid fields"))
                .andExpect(jsonPath("$.timestamp", notNullValue()))
                .andExpect(jsonPath("$.instance").value("/api/auth/register"))
                .andExpect(jsonPath("$.errors[*].field", hasItem("email")))
                .andExpect(jsonPath("$.errors[*].field", hasItem("password")));
    }

    @Test
    void passwordOver72BytesInUtf8_isValidationErrorNotServerError() throws Exception {
        // 42 Cyrillic letters = 84 bytes: BCrypt would throw on it
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"" + UUID.randomUUID() + "@test.esep\", \"password\": \""
                                + "пароль".repeat(7) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("password"));
    }

    @Test
    void loginEmailWithNulCharacter_isValidationErrorNotDataConflict() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"a\\u0000b@test.esep\", \"password\": \"password123\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("email"));
    }

    @Test
    void malformedJson_isMalformedRequest() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{oops"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void unsupportedMediaType_hasCode() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.TEXT_PLAIN).content("hello"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    void missingToken_isUnauthorized() throws Exception {
        mvc.perform(get("/api/accounts"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void wrongPassword_isInvalidCredentials() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"" + user(Role.USER) + "\", \"password\": \"wrong-password\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void unknownRouteMethodAndMissingHeader_haveCodes() throws Exception {
        String token = login(user(Role.USER));

        mvc.perform(authorized(get("/api/no-such-endpoint"), token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        mvc.perform(authorized(delete("/api/accounts"), token))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
        mvc.perform(authorized(post("/api/transfers"), token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromAccountId\": 1, \"toAccountId\": 2, \"amount\": 1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_PARAMETER"));
        mvc.perform(authorized(get("/api/transactions").param("zone", "Mars/Olympus"), token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void businessRule_hasSpecificCode() throws Exception {
        String token = login(user(Role.USER));
        long from = createAccount(token, "KZT");
        long to = createAccount(login(user(Role.USER)), "KZT");

        transfer(token, from, to, "1")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"));
        mvc.perform(authorized(post("/api/accounts"), token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currency\": \"KZT\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_ACCOUNT"));
    }

    // ---------- account lifecycle ----------

    @Test
    void createdAccount_hasLocationOfItsOwnResource() throws Exception {
        String token = login(user(Role.USER));

        String location = mvc.perform(authorized(post("/api/accounts"), token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currency\": \"USD\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getHeader(HttpHeaders.LOCATION);

        mvc.perform(authorized(get(URI.create(location).getPath()), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currency").value("USD"));
    }

    @Test
    void closingAccount_needsZeroBalance_andAClosedAccountReceivesNothing() throws Exception {
        String alice = login(user(Role.USER));
        String bob = login(user(Role.USER));
        long aliceAccount = createAccount(alice, "KZT");
        long bobAccount = createAccount(bob, "KZT");
        deposit(login(user(Role.ADMIN)), aliceAccount, "10");

        mvc.perform(authorized(post("/api/accounts/" + aliceAccount + "/close"), alice))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_EMPTY"));
        mvc.perform(authorized(post("/api/accounts/" + bobAccount + "/close"), bob))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.closedAt", notNullValue()));

        transfer(alice, aliceAccount, bobAccount, "5")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("ACCOUNT_CLOSED"));
        // a new account in the same currency is allowed once the old one is closed
        createAccount(bob, "KZT");
    }

    // ---------- idempotency over HTTP ----------

    @Test
    void retryWithTheSameKey_is200WithReplayHeaderAndTheSameTransaction() throws Exception {
        String alice = login(user(Role.USER));
        long from = createAccount(alice, "KZT");
        long to = createAccount(login(user(Role.USER)), "KZT");
        deposit(login(user(Role.ADMIN)), from, "100");
        String key = UUID.randomUUID().toString();

        String first = transferWithKey(alice, key, from, to, "30")
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, containsString("/api/transactions/")))
                .andExpect(header().doesNotExist("Idempotent-Replayed"))
                .andReturn().getResponse().getContentAsString();
        transferWithKey(alice, key, from, to, "30.0000")   // same money, written differently
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.id").value(((Number) JsonPath.read(first, "$.id")).intValue()));
        transferWithKey(alice, key, from, to, "31")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        mvc.perform(authorized(get("/api/accounts/" + from), alice))
                .andExpect(jsonPath("$.balance").value(70.0));   // money moved once
    }

    // ---------- operation history ----------

    @Test
    void history_isPaginatedNewestFirstAndOnlyOwn() throws Exception {
        String alice = login(user(Role.USER));
        String bob = login(user(Role.USER));
        String admin = login(user(Role.ADMIN));
        long aliceAccount = createAccount(alice, "KZT");
        long bobAccount = createAccount(bob, "KZT");
        deposit(admin, aliceAccount, "100");
        transfer(alice, aliceAccount, bobAccount, "10").andExpect(status().isCreated());
        transfer(alice, aliceAccount, bobAccount, "20").andExpect(status().isCreated());

        // deposit (CREDIT) + two transfers (DEBIT) = 3 operations for Alice
        mvc.perform(authorized(get("/api/transactions").param("size", "2"), alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].amount").value(20.0))
                .andExpect(jsonPath("$.content[0].direction").value("DEBIT"))
                .andExpect(jsonPath("$.content[0].counterpartyAccountId").value(bobAccount));
        mvc.perform(authorized(get("/api/transactions").param("size", "2").param("page", "1"), alice))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].type").value("DEPOSIT"));

        // Bob sees only his incoming entries; Alice's account id is not his
        mvc.perform(authorized(get("/api/transactions"), bob))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].direction").value("CREDIT"));
        mvc.perform(authorized(get("/api/transactions").param("accountId", String.valueOf(aliceAccount)), bob))
                .andExpect(status().isNotFound());
    }

    @Test
    void history_dateFilterIsInclusiveCalendarDays() throws Exception {
        String alice = login(user(Role.USER));
        long account = createAccount(alice, "KZT");
        deposit(login(user(Role.ADMIN)), account, "5");
        String today = LocalDate.now(ZoneId.of("Asia/Almaty")).toString();
        String yesterday = LocalDate.now(ZoneId.of("Asia/Almaty")).minusDays(1).toString();

        mvc.perform(authorized(get("/api/transactions").param("from", today).param("to", today)
                        .param("zone", "Asia/Almaty"), alice))
                .andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(authorized(get("/api/transactions").param("to", yesterday).param("zone", "Asia/Almaty"), alice))
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.content.length()").value(0));
        mvc.perform(authorized(get("/api/transactions").param("from", today).param("to", yesterday), alice))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    // ---------- helpers ----------

    private String user(Role role) {
        String email = UUID.randomUUID() + "@test.esep";
        userRepository.save(new User(email, passwordEncoder.encode(PASSWORD), role));
        return email;
    }

    private String login(String email) throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"" + email + "\", \"password\": \"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.accessToken");
    }

    private long createAccount(String token, String currency) throws Exception {
        String body = mvc.perform(authorized(post("/api/accounts"), token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currency\": \"" + currency + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    private void deposit(String adminToken, long account, String amount) throws Exception {
        mvc.perform(authorized(post("/api/deposits"), adminToken).header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\": " + account + ", \"amount\": " + amount + "}"))
                .andExpect(status().isCreated());
    }

    private org.springframework.test.web.servlet.ResultActions transfer(String token, long from, long to, String amount)
            throws Exception {
        return mvc.perform(authorized(post("/api/transfers"), token).header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fromAccountId\": " + from + ", \"toAccountId\": " + to + ", \"amount\": " + amount + "}"));
    }

    private org.springframework.test.web.servlet.ResultActions transferWithKey(String token, String key, long from,
                                                                            long to, String amount) throws Exception {
        return mvc.perform(authorized(post("/api/transfers"), token).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fromAccountId\": " + from + ", \"toAccountId\": " + to + ", \"amount\": " + amount + "}"));
    }

    private static MockHttpServletRequestBuilder authorized(MockHttpServletRequestBuilder request, String token) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }
}
