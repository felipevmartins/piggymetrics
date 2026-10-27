package com.piggymetrics.statistics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.piggymetrics.statistics.domain.timeseries.DataPoint;
import com.piggymetrics.statistics.domain.timeseries.DataPointId;
import com.piggymetrics.statistics.domain.timeseries.StatisticMetric;
import com.piggymetrics.statistics.repository.DataPointRepository;
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
import java.time.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@RunWith(SpringRunner.class)
@SpringBootTest
@AutoConfigureMockMvc
public class StatisticsBearerContractTest {
    private static final AtomicInteger USERINFO_CALLS = new AtomicInteger();
    private static final AtomicInteger REDIRECT_CALLS = new AtomicInteger();
    private static final List<String> RATE_QUERIES = new CopyOnWriteArrayList<>();
    private static final List<String> RATE_AUTH_HEADERS = new CopyOnWriteArrayList<>();
    private static final List<String> RATE_BODIES = new CopyOnWriteArrayList<>();
    private static volatile String owner;
    private static volatile int issuerStatus = 200;
    private static volatile String issuerBody;
    private static final HttpServer SERVER = startServer();
    private static final String ACCOUNT = "{\"incomes\":[],\"expenses\":[],\"saving\":{\"amount\":10,\"currency\":\"EUR\",\"interest\":0,\"deposit\":false,\"capitalization\":false},\"name\":\"ignored-body-name\",\"unknownLegacyField\":true}";

    @Autowired private org.springframework.context.ApplicationContext context;
    @Autowired private org.springframework.security.web.FilterChainProxy filters;
    @Autowired private MockMvc mvc;
    @Autowired private DataPointRepository points;
    @Autowired private ObjectMapper mapper;
    private String other;
    private DataPoint previousDemo;
    private boolean demoSnapshotCaptured;

    @DynamicPropertySource
    public static void endpoints(DynamicPropertyRegistry registry) {
        String base = "http://127.0.0.1:" + SERVER.getAddress().getPort();
        registry.add("security.oauth2.resource.user-info-uri", () -> base + "/userinfo");
        registry.add("rates.url", () -> base);
    }

    @Before
    public void seedFixtures() {
        owner = "stats-" + UUID.randomUUID().toString().substring(0, 8); other = owner + "-other";
        previousDemo = points.findById(new DataPointId("demo", new Date(0))).orElse(null);
        demoSnapshotCaptured = true;
        for (String name : Arrays.asList(owner, other, "demo")) {
            DataPoint point = new DataPoint(); point.setId(new DataPointId(name, new Date(0)));
            point.setIncomes(Collections.emptySet()); point.setExpenses(Collections.emptySet());
            point.setStatistics(Collections.singletonMap(StatisticMetric.SAVING_AMOUNT, new BigDecimal("7.25")));
            points.save(point);
        }
        issuerStatus = 200; issuerBody = null;
        USERINFO_CALLS.set(0); REDIRECT_CALLS.set(0); RATE_QUERIES.clear(); RATE_AUTH_HEADERS.clear(); RATE_BODIES.clear();
    }
    @After
    public void deleteOnlyFixtures() {
        for (String name : Arrays.asList(owner, other)) {
            for (DataPoint point : points.findByIdAccount(name)) points.deleteById(point.getId());
        }
        if (demoSnapshotCaptured) {
            if (previousDemo == null) points.deleteById(new DataPointId("demo", new Date(0)));
            else points.save(previousDemo);
        }
    }
    @AfterClass public static void closeServer() { SERVER.stop(0); }

    @Test
    public void currentResolvesOwnerAndPreservesDataPointIdentity() throws Exception {
        MvcResult result = mvc.perform(get("/current").header("Authorization", "Bearer user-ui"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id.account").value(owner)).andReturn();
        JsonNode date = mapper.readTree(result.getResponse().getContentAsString()).get(0).path("id").path("date");
        Instant actual = date.isNumber() ? Instant.ofEpochMilli(date.asLong()) : OffsetDateTime.parse(date.asText()).toInstant();
        assertEquals(Instant.EPOCH, actual);
        assertEquals(new Date(0), points.findByIdAccount(owner).get(0).getId().getDate());
        assertEquals(1, points.findByIdAccount(other).size());
    }

    @Test
    public void serverCanReadNamedAccountWhileUiCannot() throws Exception {
        mvc.perform(get("/" + other).header("Authorization", "Bearer service-server"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id.account").value(other));
        mvc.perform(get("/" + other).header("Authorization", "Bearer user-ui"))
                .andExpect(status().isForbidden());
    }

    @Test
    public void demoStillRequiresAuthenticationAtHttpLayer() throws Exception {
        mvc.perform(get("/demo")).andExpect(status().isUnauthorized());
        mvc.perform(get("/demo").header("Authorization", "Bearer user-ui"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id.account").value("demo"));
    }

    @Test
    public void servicePutPersistsPathAccountMoneyAndRatesWithoutLeakingBearer() throws Exception {
        ZoneId zone = ZoneId.systemDefault(); LocalDate before = LocalDate.now(zone);
        mvc.perform(put("/" + other).header("Authorization", "Bearer service-server")
                .contentType(MediaType.APPLICATION_JSON).content(ACCOUNT)).andExpect(status().isOk());
        LocalDate after = LocalDate.now(zone);
        List<DataPoint> saved = points.findByIdAccount(other);
        assertEquals(2, saved.size());
        DataPoint today = saved.stream().filter(p -> p.getId().getDate().getTime() != 0).findFirst().get();
        assertEquals(other, today.getId().getAccount());
        assertTrue(today.getId().getDate().toInstant().equals(before.atStartOfDay(zone).toInstant())
                || today.getId().getDate().toInstant().equals(after.atStartOfDay(zone).toInstant()));
        assertEquals(0, new BigDecimal("5").compareTo(today.getStatistics().get(StatisticMetric.SAVING_AMOUNT)));
        assertEquals(0, BigDecimal.ZERO.compareTo(today.getStatistics().get(StatisticMetric.INCOMES_AMOUNT)));
        assertEquals(0, BigDecimal.ZERO.compareTo(today.getStatistics().get(StatisticMetric.EXPENSES_AMOUNT)));
        assertEquals(0, new BigDecimal("2").compareTo(today.getRates().get(com.piggymetrics.statistics.domain.Currency.EUR)));
        assertEquals(1, points.findByIdAccount(owner).size());
        assertEquals(Collections.singletonList("base=USD"), RATE_QUERIES);
        assertEquals(Collections.singletonList(""), RATE_AUTH_HEADERS);
        assertEquals(Collections.singletonList(""), RATE_BODIES);
    }

    @Test
    public void uiCannotWriteBySpoofingScopeOrBodyName() throws Exception {
        mvc.perform(put("/" + other).header("Authorization", "Bearer user-ui").param("scope", "server")
                .contentType(MediaType.APPLICATION_JSON).content(ACCOUNT)).andExpect(status().isForbidden());
        assertEquals(1, points.findByIdAccount(owner).size());
        List<DataPoint> saved = points.findByIdAccount(other); assertEquals(1, saved.size());
        assertEquals(0, new BigDecimal("7.25").compareTo(saved.get(0).getStatistics().get(StatisticMetric.SAVING_AMOUNT)));
        assertTrue(RATE_QUERIES.isEmpty());
    }

    @Test
    public void modernResolverRejectsMissingQueryFormAndAmbiguousCredentials() throws Exception {
        mvc.perform(get("/current")).andExpect(status().isUnauthorized());
        mvc.perform(get("/current").param("access_token", "user-ui")).andExpect(status().isUnauthorized());
        mvc.perform(post("/current").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("access_token", "user-ui")).andExpect(status().isUnauthorized());
        mvc.perform(get("/current").header("Authorization", "Bearer not a token"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/current").header("Authorization", "Bearer user-ui", "Bearer service-server"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/current").header("Authorization", "Bearer user-ui")
                .param("access_token", "service-server")).andExpect(status().isBadRequest());
        assertEquals(0, USERINFO_CALLS.get());
    }

    @Test
    public void issuerFailuresMalformedScopesAndRedirectCannotAuthorize() throws Exception {
        for (int status : Arrays.asList(401, 500, 302)) {
            issuerStatus = status;
            mvc.perform(get("/current").header("Authorization", "Bearer user-ui"))
                    .andExpect(status().isUnauthorized());
        }
        assertEquals(0, REDIRECT_CALLS.get()); issuerStatus = 200;
        String valid = profile(owner, "browser", "ui");
        for (String invalid : Arrays.asList("[]", "not-json", "{\"error\":\"invalid_token\"}",
                valid.replace("[\"ui\"]", "[42]"), valid.replace("[\"ui\"]", "[]"),
                valid.replace("\"name\":\"" + owner + "\"", "\"name\":{}"),
                valid.replace(",\"scope\":[\"ui\"]", ""))) {
            issuerBody = invalid;
            mvc.perform(get("/current").header("Authorization", "Bearer user-ui"))
                    .andExpect(status().isUnauthorized());
        }
        assertEquals(1, points.findByIdAccount(owner).size());
        assertTrue(RATE_QUERIES.isEmpty());
    }

    @Test
    public void securityIsStatelessAndUsesOnlyThisModulesModernInbound() throws Exception {
        MvcResult result = mvc.perform(get("/current").header("Authorization", "Bearer user-ui"))
                .andExpect(status().isOk()).andExpect(header().doesNotExist("Set-Cookie")).andReturn();
        assertNull(result.getRequest().getSession(false));
        Object introspector = context.getBean(org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector.class);
        assertEquals("com.piggymetrics.statistics.service.security.LegacyUserInfoOpaqueTokenIntrospector", introspector.getClass().getName());
        assertTrue(filters.getFilterChains().stream().flatMap(c -> c.getFilters().stream())
                .anyMatch(f -> f.getClass().getSimpleName().equals("BearerTokenAuthenticationFilter")));
        assertFalse(filters.getFilterChains().stream().flatMap(c -> c.getFilters().stream())
                .anyMatch(f -> f.getClass().getSimpleName().contains("OAuth2Login")));
        try {
            Class.forName("org.springframework.security.oauth2.provider.token.ResourceServerTokenServices");
            fail("Legacy OAuth library must not remain on this module's classpath");
        } catch (ClassNotFoundException expected) { /* Modern resource server has no legacy token services. */ }
    }

    private static String profile(String name, String client, String scope) {
        return "{\"name\":\"" + name + "\",\"oauth2Request\":{\"clientId\":\"" + client + "\",\"scope\":[\"" + scope + "\"]}}";
    }
    private static HttpServer startServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath(); int status = 200; String body;
                if (path.equals("/userinfo")) {
                    USERINFO_CALLS.incrementAndGet(); status = issuerStatus;
                    String auth = exchange.getRequestHeaders().getFirst("Authorization");
                    body = issuerBody != null ? issuerBody : "Bearer service-server".equals(auth)
                            ? profile("account-service", "account-service", "server") : profile(owner, "browser", "ui");
                    if (status == 302) exchange.getResponseHeaders().set("Location",
                            "http://localhost:" + server.getAddress().getPort() + "/redirect");
                } else if (path.equals("/redirect")) {
                    REDIRECT_CALLS.incrementAndGet(); body = profile(owner, "browser", "ui");
                } else {
                    RATE_QUERIES.add(exchange.getRequestURI().getRawQuery());
                    String auth = exchange.getRequestHeaders().getFirst("Authorization"); RATE_AUTH_HEADERS.add(auth == null ? "" : auth);
                    RATE_BODIES.add(org.springframework.util.StreamUtils.copyToString(exchange.getRequestBody(), StandardCharsets.UTF_8));
                    body = "{\"base\":\"USD\",\"date\":\"" + LocalDate.now()
                            + "\",\"rates\":{\"USD\":1,\"EUR\":2,\"RUB\":4}}";
                }
                exchange.getRequestBody().close(); byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
            });
            server.start(); return server;
        } catch (java.io.IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
}
