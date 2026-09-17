package com.ledger.service.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledger.service.domain.Account;
import com.ledger.service.repository.AccountRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * End-to-end test of POST /accounts against a real Postgres container,
 * through the actual Spring MVC dispatcher (MockMvc), exercising
 * controller -> service -> repository -> Flyway-migrated schema.
 */
class AccountControllerIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AccountRepository accountRepository;

    @Test
    void createAccount_persistsAccountAndReturns201() throws Exception {
        String requestBody = """
                {
                  "name": "Cash - Operating",
                  "currency": "usd",
                  "accountType": "ASSET"
                }
                """;

        String responseJson = mockMvc.perform(post("/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.name").value("Cash - Operating"))
                // normalized to uppercase ISO 4217 form by the service layer
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.accountType").value("ASSET"))
                .andExpect(jsonPath("$.createdAt").exists())
                // no balance field must ever be serialized - derived, not stored
                .andExpect(jsonPath("$.balance").doesNotExist())
                .andReturn()
                .getResponse()
                .getContentAsString();

        UUID id = UUID.fromString(objectMapper.readTree(responseJson).get("id").asText());

        Optional<Account> persisted = accountRepository.findById(id);
        assertThat(persisted).isPresent();
        assertThat(persisted.get().getName()).isEqualTo("Cash - Operating");
        assertThat(persisted.get().getCurrency()).isEqualTo("USD");
        assertThat(persisted.get().getCreatedAt()).isNotNull();
    }

    @Test
    void createAccount_rejectsBlankNameWith400() throws Exception {
        String requestBody = """
                {
                  "name": "",
                  "currency": "USD",
                  "accountType": "ASSET"
                }
                """;

        mockMvc.perform(post("/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Validation Failed"));
    }

    @Test
    void createAccount_rejectsInvalidCurrencyFormatWith400() throws Exception {
        String requestBody = """
                {
                  "name": "Cash",
                  "currency": "US",
                  "accountType": "ASSET"
                }
                """;

        mockMvc.perform(post("/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createAccount_rejectsUnknownAccountTypeWith400() throws Exception {
        String requestBody = """
                {
                  "name": "Cash",
                  "currency": "USD",
                  "accountType": "NOT_A_TYPE"
                }
                """;

        mockMvc.perform(post("/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createAccount_twoRequestsCreateTwoDistinctAccounts() throws Exception {
        String requestBody = """
                {
                  "name": "Cash",
                  "currency": "USD",
                  "accountType": "ASSET"
                }
                """;

        mockMvc.perform(post("/accounts").contentType(MediaType.APPLICATION_JSON).content(requestBody))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/accounts").contentType(MediaType.APPLICATION_JSON).content(requestBody))
                .andExpect(status().isCreated());

        List<Account> all = accountRepository.findAll();
        long matching = all.stream().filter(a -> a.getName().equals("Cash")).count();
        assertThat(matching).isEqualTo(2);
    }
}
