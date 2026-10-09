package com.esep.demo;

import com.esep.account.Account;
import com.esep.account.AccountRepository;
import com.esep.support.ProdFeaturesIntegrationTest;
import com.esep.user.User;
import com.esep.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DemoDataSeederTest extends ProdFeaturesIntegrationTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private DemoDataSeeder seeder;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private AccountRepository accountRepository;

    @Test
    void demoUserCanLogInWithCredentialsFromEnvironment() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"" + DEMO_EMAIL + "\", \"password\": \"" + DEMO_PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken", notNullValue()));
    }

    @Test
    void demoUserHasOneFundedAccount_andRerunDoesNotDuplicateIt() {
        seeder.run(new DefaultApplicationArguments());   // the app ran it once on startup; this is a restart

        User user = userRepository.findByEmail(DEMO_EMAIL).orElseThrow();
        List<Account> accounts = accountRepository.findAllByUser_IdOrderByIdAsc(user.getId());
        assertThat(accounts).hasSize(1);
        assertThat(accounts.getFirst().getCurrency().getCurrencyCode()).isEqualTo("KZT");
        assertThat(accounts.getFirst().getBalance()).isEqualByComparingTo(new BigDecimal("5000"));
    }
}
