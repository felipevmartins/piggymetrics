package com.piggymetrics.account.client;

import com.piggymetrics.account.config.ServiceClientCredentialsProperties;
import com.piggymetrics.account.domain.Account;
import com.piggymetrics.account.domain.User;
import com.piggymetrics.account.service.security.LegacyUserInfoOpaqueTokenIntrospector;
import org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector;
import com.sun.net.httpserver.HttpServer;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.junit.*;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.OutputCaptureRule;
import org.springframework.cloud.openfeign.FeignContext;
import org.springframework.context.ApplicationContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.*;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.web.servlet.MockMvc;

import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real Spring-created Feign clients against a local HTTP issuer and downstream endpoints. */
@RunWith(SpringRunner.class)
@SpringBootTest(properties = "feign.hystrix.enabled=true")
@AutoConfigureMockMvc
public class ServiceOAuthClientContractTest {
    private static final HttpServer SERVER = startServer();
    private static final AtomicInteger TOKEN_CALLS = new AtomicInteger();
    private static final List<String> DESTINATIONS = new CopyOnWriteArrayList<>();
    private static final List<List<String>> AUTH_HEADERS = new CopyOnWriteArrayList<>();
    private static final List<String> TOKEN_FORMS = new CopyOnWriteArrayList<>();
    private static final List<String> CLIENT_HEADERS = new CopyOnWriteArrayList<>();
    private static volatile int issuerStatus = 200;
    private static volatile int statisticsStatus = 200;

    @Autowired private AuthServiceClient auth;
    @Autowired private StatisticsServiceClient statistics;
    @Autowired private OAuth2AuthorizedClientService store;
    @Autowired private ServiceClientCredentialsProperties properties;
    @Autowired private FeignContext feign;
    @Autowired private ApplicationContext context;
    @Autowired private MockMvc mvc;
    @Autowired private FilterChainProxy filters;
    @Rule public final OutputCaptureRule output = new OutputCaptureRule();

    @DynamicPropertySource
    public static void endpoints(DynamicPropertyRegistry registry) {
        String base = "http://127.0.0.1:" + SERVER.getAddress().getPort();
        registry.add("security.oauth2.client.accessTokenUri", () -> base + "/oauth/token");
        registry.add("clients.auth-service.url", () -> base);
        registry.add("clients.statistics-service.url", () -> base);
    }

    @Before
    public void resetFixture() {
        store.removeAuthorizedClient("account-service", "account-service");
        TOKEN_CALLS.set(0);
        DESTINATIONS.clear(); AUTH_HEADERS.clear(); TOKEN_FORMS.clear(); CLIENT_HEADERS.clear();
        issuerStatus = 200; statisticsStatus = 200;
        SecurityContextHolder.clearContext();
    }

    @After public void clearPrincipal() { SecurityContextHolder.clearContext(); }
    @AfterClass public static void stopServer() { SERVER.stop(0); }

    @Test
    public void bothSpringFeignClientsSendOneServiceTokenAndReuseIt() {
        auth.createUser(user());
        statistics.updateStatistics("contract-account", new Account());
        assertEquals(Arrays.asList("POST /uaa/users", "PUT /statistics/contract-account"), DESTINATIONS);
        assertEquals(1, TOKEN_CALLS.get());
        assertServiceHeaders("service-token-1");
        assertEquals(Collections.singletonList("grant_type=client_credentials&scope=server"), TOKEN_FORMS);
        String basic = "Basic " + Base64.getEncoder().encodeToString(
                "account-service:contract-secret".getBytes(StandardCharsets.UTF_8));
        assertEquals(Collections.singletonList(basic), CLIENT_HEADERS);
        OAuth2AuthorizedClient cached = store.loadAuthorizedClient("account-service", "account-service");
        assertNotNull(cached);
        assertEquals("account-service", cached.getPrincipalName());
        assertEquals(Collections.singleton("server"), cached.getAccessToken().getScopes());
    }

    @Test
    public void userPrincipalAndExistingAuthorizationCannotReplaceServiceIdentity() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("browser-user", "browser-bearer"));
        auth.createUser(user());
        RequestTemplate request = new RequestTemplate();
        request.header("Authorization", "Bearer browser-bearer");
        request.header("authorization", "Bearer another-user-token");
        feign.getInstance("statistics-service", ServiceTokenRequestInterceptor.class).apply(request);
        assertEquals(Collections.singletonList("Bearer service-token-1"),
                new ArrayList<>(request.headers().get("Authorization")));
        assertServiceHeaders("service-token-1");
        assertNull(store.loadAuthorizedClient("account-service", "browser-user"));
    }

    @Test
    public void expiredStoredTokenIsReacquiredWithoutSleeping() {
        auth.createUser(user());
        Instant past = Instant.now().minusSeconds(86400);
        OAuth2AuthorizedClient expired = new OAuth2AuthorizedClient(properties.registration(), "account-service",
                new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "expired-token",
                        past.minusSeconds(3600), past, Collections.singleton("server")));
        store.saveAuthorizedClient(expired,
                new UsernamePasswordAuthenticationToken("account-service", "not-a-user"));
        statistics.updateStatistics("contract-account", new Account());
        assertEquals(2, TOKEN_CALLS.get());
        assertEquals(Collections.singletonList("Bearer service-token-1"), AUTH_HEADERS.get(0));
        assertEquals(Collections.singletonList("Bearer service-token-2"), AUTH_HEADERS.get(1));
    }

    @Test
    public void rejectedClientCredentialsPreventBothDownstreamCalls() {
        issuerStatus = 401;
        assertAuthCallFails();
        statistics.updateStatistics("rejected-client", new Account()); // Existing Hystrix fallback returns void.
        output.expect(containsString("Error during update statistics for account: rejected-client"));
        assertEquals(2, TOKEN_CALLS.get());
        assertTrue(DESTINATIONS.isEmpty());
        assertTrue(AUTH_HEADERS.isEmpty());
    }

    @Test
    public void unavailableIssuerPreventsBothDownstreamCalls() {
        issuerStatus = 503;
        assertAuthCallFails();
        statistics.updateStatistics("unavailable-issuer", new Account());
        output.expect(containsString("Error during update statistics for account: unavailable-issuer"));
        assertEquals(2, TOKEN_CALLS.get());
        assertTrue(DESTINATIONS.isEmpty());
    }

    @Test
    public void downstreamFailureWithValidTokenStillInvokesStatisticsFallback() {
        statisticsStatus = 503;
        statistics.updateStatistics("downstream-unavailable", new Account());
        output.expect(containsString("Error during update statistics for account: downstream-unavailable"));
        assertEquals(1, TOKEN_CALLS.get());
        assertEquals(Collections.singletonList("PUT /statistics/downstream-unavailable"), DESTINATIONS);
        assertEquals(1, AUTH_HEADERS.size());
        assertServiceHeaders("service-token-1");
    }

    @Test
    public void interceptorIsScopedToBothFeignChildrenAndLegacyInboundRemains() throws Exception {
        assertTrue(context.getBeansOfType(ServiceTokenRequestInterceptor.class).isEmpty());
        for (String name : Arrays.asList("auth-service", "statistics-service")) {
            Map<String, RequestInterceptor> interceptors = feign.getInstances(name, RequestInterceptor.class);
            assertEquals(1, interceptors.values().stream()
                    .filter(i -> i instanceof ServiceTokenRequestInterceptor).count());
            assertFalse(interceptors.values().stream()
                    .anyMatch(i -> i.getClass().getName().contains("OAuth2FeignRequestInterceptor")));
        }
        assertTrue(context.getBean(OpaqueTokenIntrospector.class) instanceof LegacyUserInfoOpaqueTokenIntrospector);
        assertTrue(filters.getFilterChains().stream().flatMap(c -> c.getFilters().stream())
                .anyMatch(f -> f.getClass().getSimpleName().equals("BearerTokenAuthenticationFilter")));
        assertFalse(filters.getFilterChains().stream().flatMap(c -> c.getFilters().stream())
                .anyMatch(f -> f.getClass().getName().contains("OAuth2Login")
                        || f.getClass().getName().contains("OAuth2AuthorizationRequestRedirect")));
        mvc.perform(get("/current")).andExpect(status().isUnauthorized());
        mvc.perform(get("/demo")).andExpect(status().isOk());
        mvc.perform(get("/")).andExpect(status().isMethodNotAllowed());
    }

    @Test
    public void invalidConfigurationFailsWithoutIncludingSecretInMessage() {
        assertEquals("account-service", properties.getClientId());
        assertEquals(Collections.singletonList("server"), properties.getScope());
        for (String invalid : Arrays.asList("secret", "uri", "scope", "client", "grant")) {
            ServiceClientCredentialsProperties bad = fixtureProperties();
            if (invalid.equals("secret")) bad.setClientSecret("");
            if (invalid.equals("uri")) bad.setAccessTokenUri("http://user:sensitive-value@localhost/token");
            if (invalid.equals("scope")) bad.setScope(Collections.singletonList("ui"));
            if (invalid.equals("client")) bad.setClientId("");
            if (invalid.equals("grant")) bad.setGrantType("password");
            try { bad.registration(); fail("Invalid OAuth configuration should be rejected"); }
            catch (IllegalArgumentException expected) {
                assertFalse(expected.getMessage().contains("sensitive-value"));
                assertFalse(expected.getMessage().contains("contract-secret"));
            }
        }
    }

    private ServiceClientCredentialsProperties fixtureProperties() {
        ServiceClientCredentialsProperties p = new ServiceClientCredentialsProperties();
        p.setClientId("account-service"); p.setClientSecret("contract-secret");
        p.setAccessTokenUri(properties.getAccessTokenUri()); p.setGrantType("client_credentials");
        p.setScope(Collections.singletonList("server")); return p;
    }

    private void assertAuthCallFails() {
        try { auth.createUser(user()); fail("Issuer failure must prevent user creation"); }
        catch (RuntimeException expected) { assertTrue(DESTINATIONS.isEmpty()); }
    }

    private void assertServiceHeaders(String token) {
        for (List<String> headers : AUTH_HEADERS) {
            assertEquals(Collections.singletonList("Bearer " + token), headers);
        }
    }

    private User user() {
        User user = new User(); user.setUsername("contract-user"); user.setPassword("contract-password"); return user;
    }

    private static HttpServer startServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                int status = 200;
                String response = "";
                if (path.equals("/oauth/token")) {
                    int n = TOKEN_CALLS.incrementAndGet();
                    TOKEN_FORMS.add(URLDecoder.decode(org.springframework.util.StreamUtils.copyToString(exchange.getRequestBody(),
                            StandardCharsets.UTF_8), "UTF-8"));
                    CLIENT_HEADERS.add(exchange.getRequestHeaders().getFirst("Authorization"));
                    status = issuerStatus;
                    response = status == 200 ? "{\"access_token\":\"service-token-" + n
                            + "\",\"token_type\":\"bearer\",\"expires_in\":3600,\"scope\":\"server\"}"
                            : "{\"error\":\"" + (status == 401 ? "invalid_client" : "server_error") + "\"}";
                } else {
                    DESTINATIONS.add(exchange.getRequestMethod() + " " + path);
                    List<String> headers = exchange.getRequestHeaders().get("Authorization");
                    AUTH_HEADERS.add(headers == null ? Collections.emptyList() : new ArrayList<>(headers));
                    exchange.getRequestBody().close();
                    if (path.startsWith("/statistics/")) {
                        status = statisticsStatus;
                        if (status != 200) response = "{\"error\":\"statistics_unavailable\"}";
                    }
                }
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                byte[] body = response.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
                if (body.length != 0) exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start(); return server;
        } catch (java.io.IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
}
