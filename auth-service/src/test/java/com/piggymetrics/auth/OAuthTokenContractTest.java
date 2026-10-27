package com.piggymetrics.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.piggymetrics.auth.domain.User;
import com.piggymetrics.auth.repository.UserRepository;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import static org.junit.Assert.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Characterizes legacy behavior through MockMvc; does not endorse password grant for the replacement. */
@RunWith(SpringRunner.class)
@SpringBootTest(properties = "ACCOUNT_SERVICE_PASSWORD=contract-secret")
@AutoConfigureMockMvc
public class OAuthTokenContractTest {
    @Autowired private MockMvc mvc;
    @Autowired private UserRepository users;
    @Autowired private ObjectMapper mapper;
    private String username;

    @Before
    public void createFixture() {
        username = "upgrade-contract-" + UUID.randomUUID();
        User user = new User();
        user.setUsername(username);
        user.setPassword(new BCryptPasswordEncoder().encode("contract-password"));
        users.save(user);
    }

    @After
    public void removeOnlyFixture() {
        if (username != null) users.deleteById(username);
    }

    @Test
    public void browserPasswordTokenCanBeIntrospectedAndRefreshed() throws Exception {
        JsonNode issued = body(mvc.perform(passwordRequest("contract-password"))
                .andExpect(status().isOk()));
        assertToken(issued, "ui");
        JsonNode profile = userInfo(issued.path("access_token").asText());
        assertEquals(username, profile.path("name").asText());
        assertEquals("browser", profile.path("oauth2Request").path("clientId").asText());
        assertEquals("ui", profile.path("oauth2Request").path("scope").get(0).asText());
        assertFalse(issued.path("refresh_token").asText().isEmpty());
        JsonNode checked = body(mvc.perform(clientPost("/oauth/check_token", "contract-secret")
                .param("token", issued.path("access_token").asText())).andExpect(status().isOk()));
        assertEquals(username, checked.path("user_name").asText());
        assertEquals("browser", checked.path("client_id").asText());
        assertEquals("ui", checked.path("scope").get(0).asText());
        JsonNode refreshed = body(mvc.perform(browserPost().param("grant_type", "refresh_token")
                .param("refresh_token", issued.path("refresh_token").asText()))
                .andExpect(status().isOk()));
        assertToken(refreshed, "ui");
    }

    @Test
    public void serviceTokenHasServerScopeAndClientIdentity() throws Exception {
        JsonNode issued = body(mvc.perform(clientPost("/oauth/token", "contract-secret")
                .param("grant_type", "client_credentials").param("scope", "server"))
                .andExpect(status().isOk()));
        assertToken(issued, "server");
        JsonNode profile = userInfo(issued.path("access_token").asText());
        assertEquals("account-service", profile.path("name").asText());
        assertEquals("account-service", profile.path("oauth2Request").path("clientId").asText());
        assertEquals("server", profile.path("oauth2Request").path("scope").get(0).asText());
        assertFalse(issued.has("refresh_token"));
        JsonNode checked = body(mvc.perform(clientPost("/oauth/check_token", "contract-secret")
                .param("token", issued.path("access_token").asText())).andExpect(status().isOk()));
        assertEquals("account-service", checked.path("client_id").asText());
        assertEquals("server", checked.path("scope").get(0).asText());
        assertFalse(checked.has("user_name"));
    }

    @Test
    public void serverScopeTokenMayCreateUsers() throws Exception {
        String token = body(mvc.perform(clientPost("/oauth/token", "contract-secret")
                .param("grant_type", "client_credentials").param("scope", "server"))
                .andExpect(status().isOk())).path("access_token").asText();
        String created = "upgrade-contract-created-" + UUID.randomUUID();
        try {
            mvc.perform(post("/users").header("Authorization", "Bearer " + token)
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .content("{\"username\":\"" + created + "\",\"password\":\"contract-password\"}"))
                    .andExpect(status().isOk());
        } finally {
            users.deleteById(created);
        }
    }

    @Test
    public void uiScopeTokenIsForbiddenFromCreatingUsers() throws Exception {
        String token = body(mvc.perform(passwordRequest("contract-password"))
                .andExpect(status().isOk())).path("access_token").asText();
        String attempted = "upgrade-contract-denied-" + UUID.randomUUID();
        mvc.perform(post("/users").header("Authorization", "Bearer " + token)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + attempted + "\",\"password\":\"contract-password\"}"))
                .andExpect(status().isForbidden());
        assertFalse(users.findById(attempted).isPresent());
    }

    @Test
    public void wrongPasswordDoesNotIssueToken() throws Exception {
        assertOAuthError(mvc.perform(passwordRequest("wrong-password"))
                .andExpect(status().isBadRequest()), "invalid_grant");
    }

    @Test
    public void wrongClientSecretDoesNotIssueToken() throws Exception {
        ResultActions rejected = mvc.perform(clientPost("/oauth/token", "wrong-secret")
                .param("grant_type", "client_credentials").param("scope", "server"))
                .andExpect(status().isUnauthorized());
        // MockMvc does not perform the servlet container error-page dispatch.
        assertEquals("Unauthorized", rejected.andReturn().getResponse().getErrorMessage());
        assertTrue(rejected.andReturn().getResponse().getHeader("WWW-Authenticate").startsWith("Basic "));
        assertEquals("", rejected.andReturn().getResponse().getContentAsString());
    }

    @Test
    public void serviceCannotRequestBrowserScope() throws Exception {
        assertOAuthError(mvc.perform(clientPost("/oauth/token", "contract-secret")
                .param("grant_type", "client_credentials").param("scope", "ui"))
                .andExpect(status().isBadRequest()), "invalid_scope");
    }

    @Test
    public void unknownTokenCannotBeIntrospected() throws Exception {
        assertOAuthError(mvc.perform(clientPost("/oauth/check_token", "contract-secret")
                .param("token", "unknown-" + UUID.randomUUID()))
                .andExpect(status().isBadRequest()), "invalid_token");
    }

    private MockHttpServletRequestBuilder passwordRequest(String password) {
        return browserPost().param("grant_type", "password").param("scope", "ui")
                .param("username", username).param("password", password);
    }

    private MockHttpServletRequestBuilder browserPost() {
        return post("/oauth/token").header("Authorization", basic("browser", ""));
    }

    private MockHttpServletRequestBuilder clientPost(String path, String secret) {
        return post(path).header("Authorization", basic("account-service", secret));
    }

    private String basic(String client, String secret) {
        return "Basic " + Base64.getEncoder().encodeToString((client + ":" + secret)
                .getBytes(StandardCharsets.UTF_8));
    }

    private JsonNode userInfo(String token) throws Exception {
        return body(mvc.perform(get("/users/current").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()));
    }

    private JsonNode body(ResultActions action) throws Exception {
        return mapper.readTree(action.andReturn().getResponse().getContentAsString());
    }

    private void assertToken(JsonNode token, String scope) {
        assertFalse(token.path("access_token").asText().isEmpty());
        assertEquals("bearer", token.path("token_type").asText().toLowerCase(java.util.Locale.ROOT));
        assertEquals(scope, token.path("scope").asText());
        assertTrue(token.path("expires_in").asLong() > 0);
    }

    private void assertOAuthError(ResultActions action, String error) throws Exception {
        JsonNode response = body(action);
        assertEquals(error, response.path("error").asText());
        assertFalse(response.has("access_token"));
        assertFalse(response.has("refresh_token"));
    }
}
