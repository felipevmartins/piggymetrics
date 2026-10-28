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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.util.UriComponentsBuilder;
import java.security.MessageDigest;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import static org.junit.Assert.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Characterizes the Spring Authorization Server contract through MockMvc (authorization code + PKCE, client credentials, introspection). */
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

    private static final String REDIRECT = "http://localhost/";
    private static final String VERIFIER = "contract-verifier-0123456789-0123456789-0123456789-abc";

    @Test
    public void browserAuthorizationCodeTokenCanBeIntrospected() throws Exception {
        JsonNode issued = body(mvc.perform(codeExchange(authorizationCode(login("contract-password")), VERIFIER))
                .andExpect(status().isOk()));
        assertToken(issued, "ui");
        // Public clients receive no refresh token; the UI never used the one the legacy server issued.
        assertFalse(issued.has("refresh_token"));
        JsonNode profile = userInfo(issued.path("access_token").asText());
        assertEquals(username, profile.path("name").asText());
        JsonNode checked = body(mvc.perform(clientPost("/oauth2/introspect", "contract-secret")
                .param("token", issued.path("access_token").asText())).andExpect(status().isOk()));
        assertTrue(checked.path("active").asBoolean());
        assertEquals(username, checked.path("sub").asText());
        assertEquals("browser", checked.path("client_id").asText());
        assertEquals("ui", checked.path("scope").asText());
    }

    @Test
    public void serviceTokenHasServerScopeAndClientIdentity() throws Exception {
        JsonNode issued = body(mvc.perform(clientPost("/oauth2/token", "contract-secret")
                .param("grant_type", "client_credentials").param("scope", "server"))
                .andExpect(status().isOk()));
        assertToken(issued, "server");
        JsonNode profile = userInfo(issued.path("access_token").asText());
        assertEquals("account-service", profile.path("name").asText());
        assertFalse(issued.has("refresh_token"));
        JsonNode checked = body(mvc.perform(clientPost("/oauth2/introspect", "contract-secret")
                .param("token", issued.path("access_token").asText())).andExpect(status().isOk()));
        assertTrue(checked.path("active").asBoolean());
        assertEquals("account-service", checked.path("client_id").asText());
        assertEquals("server", checked.path("scope").asText());
        assertEquals("account-service", checked.path("sub").asText());
    }

    @Test
    public void serverScopeTokenMayCreateUsers() throws Exception {
        String token = body(mvc.perform(clientPost("/oauth2/token", "contract-secret")
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
        String token = body(mvc.perform(codeExchange(authorizationCode(login("contract-password")), VERIFIER))
                .andExpect(status().isOk())).path("access_token").asText();
        String attempted = "upgrade-contract-denied-" + UUID.randomUUID();
        mvc.perform(post("/users").header("Authorization", "Bearer " + token)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + attempted + "\",\"password\":\"contract-password\"}"))
                .andExpect(status().isForbidden());
        assertFalse(users.findById(attempted).isPresent());
    }

    @Test
    public void wrongPasswordDoesNotAuthenticateTheUser() throws Exception {
        ResultActions failed = mvc.perform(post("/login").with(csrf())
                .param("username", username).param("password", "wrong-password"))
                .andExpect(status().is3xxRedirection());
        assertTrue(failed.andReturn().getResponse().getRedirectedUrl().contains("error"));
        MockHttpSession session = (MockHttpSession) failed.andReturn().getRequest().getSession(false);
        ResultActions authorize = mvc.perform(authorizeRequest(session)).andExpect(status().is3xxRedirection());
        assertTrue(authorize.andReturn().getResponse().getRedirectedUrl().contains("/login"));
        assertFalse(authorize.andReturn().getResponse().getRedirectedUrl().contains("code="));
    }

    @Test
    public void passwordGrantIsNoLongerSupported() throws Exception {
        JsonNode rejected = body(mvc.perform(post("/oauth2/token").param("client_id", "browser")
                .param("grant_type", "password").param("scope", "ui")
                .param("username", username).param("password", "contract-password")
                .accept(org.springframework.http.MediaType.APPLICATION_JSON))
                .andExpect(status().is4xxClientError()));
        assertFalse(rejected.has("access_token"));
        assertFalse(rejected.has("refresh_token"));
    }

    @Test
    public void authorizationCodeWithoutPkceIsRejected() throws Exception {
        MockHttpSession session = login("contract-password");
        ResultActions authorize = mvc.perform(get(UriComponentsBuilder.fromPath("/oauth2/authorize")
                .queryParam("response_type", "code").queryParam("client_id", "browser")
                .queryParam("scope", "ui").queryParam("redirect_uri", REDIRECT).build().toUri())
                .session(session).accept(org.springframework.http.MediaType.TEXT_HTML))
                .andExpect(status().is3xxRedirection());
        String location = authorize.andReturn().getResponse().getRedirectedUrl();
        assertTrue(location, location.contains("error=invalid_request"));
        assertFalse(location, location.contains("code="));
    }

    @Test
    public void wrongPkceVerifierDoesNotIssueToken() throws Exception {
        String code = authorizationCode(login("contract-password"));
        assertOAuthError(mvc.perform(codeExchange(code, VERIFIER + "-tampered"))
                .andExpect(status().isBadRequest()), "invalid_grant");
    }

    @Test
    public void wrongClientSecretDoesNotIssueToken() throws Exception {
        ResultActions rejected = mvc.perform(clientPost("/oauth2/token", "wrong-secret")
                .param("grant_type", "client_credentials").param("scope", "server"))
                .andExpect(status().isUnauthorized());
        assertOAuthError(rejected, "invalid_client");
    }

    @Test
    public void serviceCannotRequestBrowserScope() throws Exception {
        assertOAuthError(mvc.perform(clientPost("/oauth2/token", "contract-secret")
                .param("grant_type", "client_credentials").param("scope", "ui"))
                .andExpect(status().isBadRequest()), "invalid_scope");
    }

    @Test
    public void unknownTokenIsReportedInactive() throws Exception {
        JsonNode response = body(mvc.perform(clientPost("/oauth2/introspect", "contract-secret")
                .param("token", "unknown-" + UUID.randomUUID()))
                .andExpect(status().isOk()));
        assertFalse(response.path("active").asBoolean());
        assertFalse(response.has("scope"));
        assertFalse(response.has("client_id"));
    }

    private MockHttpSession login(String password) throws Exception {
        ResultActions login = mvc.perform(post("/login").with(csrf())
                .param("username", username).param("password", password))
                .andExpect(status().is3xxRedirection());
        MockHttpSession session = (MockHttpSession) login.andReturn().getRequest().getSession(false);
        assertNotNull(session);
        return session;
    }

    private MockHttpServletRequestBuilder authorizeRequest(MockHttpSession session) throws Exception {
        // The authorization endpoint reads GET parameters from the query string, not MockMvc params.
        return get(UriComponentsBuilder.fromPath("/oauth2/authorize")
                .queryParam("response_type", "code").queryParam("client_id", "browser").queryParam("scope", "ui")
                .queryParam("redirect_uri", REDIRECT).queryParam("state", "contract-state")
                .queryParam("code_challenge", challenge(VERIFIER)).queryParam("code_challenge_method", "S256")
                .build().toUri()).session(session).accept(org.springframework.http.MediaType.TEXT_HTML);
    }

    private String authorizationCode(MockHttpSession session) throws Exception {
        ResultActions authorize = mvc.perform(authorizeRequest(session));
        authorize.andExpect(status().is3xxRedirection());
        String location = authorize.andReturn().getResponse().getRedirectedUrl();
        assertTrue(location + authorize.andReturn().getResponse().getContentAsString(), location.startsWith(REDIRECT));
        UriComponentsBuilder uri = UriComponentsBuilder.fromUriString(location);
        assertEquals("contract-state", uri.build().getQueryParams().getFirst("state"));
        return uri.build().getQueryParams().getFirst("code");
    }

    private MockHttpServletRequestBuilder codeExchange(String code, String verifier) {
        return post("/oauth2/token").param("client_id", "browser").param("grant_type", "authorization_code")
                .param("code", code).param("redirect_uri", REDIRECT).param("code_verifier", verifier);
    }

    private String challenge(String verifier) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
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
