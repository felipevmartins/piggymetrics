package com.piggymetrics.account;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.piggymetrics.account.domain.Account;
import com.piggymetrics.account.domain.Currency;
import com.piggymetrics.account.domain.Saving;
import com.piggymetrics.account.repository.AccountRepository;
import com.piggymetrics.account.config.LegacyUserInfoProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.*;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@RunWith(SpringRunner.class)
@SpringBootTest(properties = "feign.hystrix.enabled=true")
@AutoConfigureMockMvc
public class AccountBearerContractTest {
    private static final AtomicInteger USERINFO_CALLS = new AtomicInteger();
    private static final AtomicInteger REDIRECT_CALLS = new AtomicInteger();
    private static final List<String> USERINFO_HEADERS = new CopyOnWriteArrayList<>();
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "userinfo-contract"); thread.setDaemon(true); return thread;
    });
    private static volatile String owner;
    private static volatile String responseOverride;
    private static volatile int responseStatus = 200;
    private static volatile CountDownLatch slowBody = new CountDownLatch(0);
    private static final HttpServer SERVER = startServer();

    @Autowired private MockMvc mvc;
    @Autowired private AccountRepository accounts;
    @Autowired private ObjectMapper mapper;
    private String other;
    private String created;

    @DynamicPropertySource
    public static void endpoints(DynamicPropertyRegistry registry) {
        String base = "http://127.0.0.1:" + SERVER.getAddress().getPort();
        registry.add("security.oauth2.resource.user-info-uri", () -> base + "/userinfo");
        registry.add("security.oauth2.resource.read-timeout-millis", () -> 500);
        registry.add("security.oauth2.resource.max-response-bytes", () -> 1024);
        registry.add("security.oauth2.client.accessTokenUri", () -> base + "/oauth/token");
        registry.add("clients.auth-service.url", () -> base);
        registry.add("clients.statistics-service.url", () -> base);
    }

    @Before
    public void seedOnlyFixtureAccounts() {
        owner = "e4d-" + UUID.randomUUID().toString().substring(0, 8);
        other = owner + "-other";
        accounts.save(account(owner, "owner-original"));
        accounts.save(account(other, "other-original"));
        accounts.save(account("account-service", "service-original"));
        responseOverride = null; responseStatus = 200;
        USERINFO_CALLS.set(0); REDIRECT_CALLS.set(0); USERINFO_HEADERS.clear();
        slowBody = new CountDownLatch(0);
    }

    @After
    public void removeOnlyFixtureAccounts() {
        slowBody.countDown();
        for (String name : Arrays.asList(owner, other, "account-service", created)) {
            if (name != null) accounts.deleteById(name);
        }
    }
    @AfterClass public static void closeFixture() { SERVER.stop(0); EXECUTOR.shutdownNow(); }

    @Test
    public void userAndServiceNamesResolveTheirOwnCurrentAccountWithoutSession() throws Exception {
        MvcResult user = mvc.perform(get("/current").header("Authorization", "Bearer user-ui"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value(owner))
                .andExpect(header().doesNotExist("Set-Cookie")).andReturn();
        assertNull(user.getRequest().getSession(false));
        mvc.perform(get("/current").header("Authorization", "Bearer service-server"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("account-service"));
        assertEquals(Arrays.asList("Bearer user-ui", "Bearer service-server"), USERINFO_HEADERS);
    }

    @Test
    public void serverScopeCanReadNamedAccountButUiCannotElevateThroughQuery() throws Exception {
        mvc.perform(get("/" + other).header("Authorization", "Bearer service-server"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value(other));
        mvc.perform(get("/" + other).header("Authorization", "Bearer user-ui"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/" + other).header("Authorization", "Bearer user-ui")
                .param("access_token", "service-server")).andExpect(status().isBadRequest());
        assertEquals("other-original", accounts.findByName(other).getNote());
    }

    @Test
    public void putCurrentUpdatesOnlyPrincipalAccountDespiteAnotherNameInBody() throws Exception {
        Account update = account(other, "owner-updated");
        com.fasterxml.jackson.databind.node.ObjectNode payload = mapper.valueToTree(update);
        payload.put("unknownLegacyField", "ignored");
        MvcResult result = mvc.perform(put("/current").header("Authorization", "Bearer user-ui")
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(payload)))
                .andExpect(status().isOk()).andExpect(header().doesNotExist("Set-Cookie")).andReturn();
        assertNull(result.getRequest().getSession(false));
        assertEquals("owner-updated", accounts.findByName(owner).getNote());
        assertEquals("other-original", accounts.findByName(other).getNote());
    }

    @Test
    public void demoAndRegistrationRemainPublicWithoutCsrfOrSession() throws Exception {
        mvc.perform(get("/demo")).andExpect(status().isOk());
        created = "new-" + UUID.randomUUID().toString().substring(0, 8);
        String body = "{\"username\":\"" + created + "\",\"password\":\"fixture-password\"}";
        MvcResult result = mvc.perform(post("/").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value(created))
                .andExpect(header().doesNotExist("Set-Cookie")).andReturn();
        assertNull(result.getRequest().getSession(false));
        assertNotNull(accounts.findByName(created));
        assertEquals(0, USERINFO_CALLS.get());
    }

    @Test
    public void missingQueryAndFormTokensCannotAuthenticateProtectedRoutes() throws Exception {
        mvc.perform(get("/current")).andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", startsWith("Bearer")));
        mvc.perform(get("/current").param("access_token", "user-ui")).andExpect(status().isUnauthorized());
        mvc.perform(post("/current").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("access_token", "user-ui")).andExpect(status().isUnauthorized());
        assertEquals(0, USERINFO_CALLS.get());
    }

    @Test
    public void malformedAndDuplicateBearerHeadersAreRejected() throws Exception {
        mvc.perform(get("/current").header("Authorization", "Bearer not a token"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/current").header("Authorization", "Bearer user-ui", "Bearer service-server"))
                .andExpect(status().isBadRequest());
        assertEquals(0, USERINFO_CALLS.get());
    }

    @Test
    public void issuerRejectionAndOutageFailClosedWithoutLeakingPayload() throws Exception {
        for (int status : Arrays.asList(401, 500)) {
            responseStatus = status; responseOverride = "sensitive-profile-marker";
            MvcResult result = mvc.perform(get("/current").header("Authorization", "Bearer user-ui"))
                    .andExpect(status().isUnauthorized()).andReturn();
            assertFalse(result.getResponse().getContentAsString().contains("sensitive-profile-marker"));
            assertFalse(result.getResponse().getHeader("WWW-Authenticate").contains("user-ui"));
        }
    }

    @Test
    public void malformedProfilesNeverBecomeUnknownOrPrivilegedAuthentication() throws Exception {
        String valid = profile(owner, "browser", "ui");
        List<String> invalid = Arrays.asList("not-json", "[]", "{\"error\":\"invalid_token\"}",
                valid.replace("\"name\":\"" + owner + "\"", "\"name\":\"\""),
                valid.replace("\"clientId\":\"browser\"", "\"clientId\":{}"),
                valid.replace("[\"ui\"]", "[42]"), valid.replace("[\"ui\"]", "[]"),
                valid.replace("[\"ui\"]", "[\" \" ]"),
                valid.replace(",\"scope\":[\"ui\"]", ""), valid + "{}");
        for (String body : invalid) {
            responseOverride = body;
            mvc.perform(get("/current").header("Authorization", "Bearer user-ui"))
                    .andExpect(status().isUnauthorized());
        }
        assertEquals("owner-original", accounts.findByName(owner).getNote());
    }

    @Test
    public void redirectIsRejectedWithoutForwardingBearer() throws Exception {
        responseStatus = 302;
        mvc.perform(get("/current").header("Authorization", "Bearer user-ui"))
                .andExpect(status().isUnauthorized());
        assertEquals(1, USERINFO_CALLS.get());
        assertEquals(0, REDIRECT_CALLS.get());
    }

    @Test
    public void oversizedAndSlowResponsesFailClosed() throws Exception {
        responseOverride = new String(new char[2048]).replace('\0', ' ');
        mvc.perform(get("/current").header("Authorization", "Bearer user-ui"))
                .andExpect(status().isUnauthorized());
        responseOverride = null;
        slowBody = new CountDownLatch(1);
        try {
            mvc.perform(get("/current").header("Authorization", "Bearer user-ui"))
                    .andExpect(status().isUnauthorized());
        } finally { slowBody.countDown(); }
    }

    @Test
    public void unsafeEndpointNonpositiveTimeoutAndOversizedLimitAreRejected() {
        for (String bad : Arrays.asList("userinfo", "timeout", "limit")) {
            LegacyUserInfoProperties p = new LegacyUserInfoProperties();
            p.setUserInfoUri("http://127.0.0.1/userinfo");
            if (bad.equals("userinfo")) p.setUserInfoUri("http://user:secret-marker@localhost/userinfo");
            if (bad.equals("timeout")) p.setReadTimeoutMillis(0);
            if (bad.equals("limit")) p.setMaxResponseBytes(Integer.MAX_VALUE);
            try { p.validatedUri(); fail("Unsafe userinfo settings must fail"); }
            catch (IllegalArgumentException expected) { assertFalse(expected.getMessage().contains("secret-marker")); }
        }
    }

    private static String profile(String name, String client, String scope) {
        return "{\"name\":\"" + name + "\",\"oauth2Request\":{\"clientId\":\"" + client
                + "\",\"scope\":[\"" + scope + "\"]}}";
    }
    private Account account(String name, String note) {
        Account a = new Account(); a.setName(name); a.setNote(note);
        Saving s = new Saving(); s.setAmount(BigDecimal.ZERO); s.setCurrency(Currency.USD);
        s.setInterest(BigDecimal.ZERO); s.setDeposit(false); s.setCapitalization(false); a.setSaving(s); return a;
    }
    private static HttpServer startServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(EXECUTOR);
            server.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                String body = ""; int status = 200;
                if (path.equals("/userinfo")) {
                    USERINFO_CALLS.incrementAndGet();
                    String authorization = exchange.getRequestHeaders().getFirst("Authorization");
                    USERINFO_HEADERS.add(authorization);
                    status = responseStatus;
                    body = responseOverride != null ? responseOverride
                            : authorization.equals("Bearer service-server")
                            ? profile("account-service", "account-service", "server") : profile(owner, "browser", "ui");
                    if (status == 302) exchange.getResponseHeaders().set("Location",
                            "http://localhost:" + server.getAddress().getPort() + "/redirect-target");
                } else if (path.equals("/redirect-target")) {
                    REDIRECT_CALLS.incrementAndGet(); body = profile(owner, "browser", "ui");
                } else if (path.equals("/oauth/token")) {
                    body = "{\"access_token\":\"outbound-service\",\"token_type\":\"bearer\",\"expires_in\":3600,\"scope\":\"server\"}";
                }
                exchange.getRequestBody().close();
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                try {
                    exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
                    if (path.equals("/userinfo")) slowBody.await(5, TimeUnit.SECONDS);
                    if (bytes.length > 0) exchange.getResponseBody().write(bytes);
                } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                catch (java.io.IOException disconnectedAfterTimeout) { /* Client may have closed the bounded read. */ }
                finally { exchange.close(); }
            });
            server.start(); return server;
        } catch (java.io.IOException failed) { throw new ExceptionInInitializerError(failed); }
    }
}
