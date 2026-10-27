package com.piggymetrics.notification.client;

import com.piggymetrics.notification.config.ServiceClientCredentialsProperties;
import com.piggymetrics.notification.domain.NotificationType;
import com.piggymetrics.notification.domain.Recipient;
import com.piggymetrics.notification.service.*;
import com.sun.net.httpserver.HttpServer;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.junit.*;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
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
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@RunWith(SpringRunner.class)
@SpringBootTest(properties = {"backup.cron=-", "remind.cron=-"})
@AutoConfigureMockMvc
public class ServiceOAuthClientContractTest {
    private static final AtomicInteger TOKENS = new AtomicInteger();
    private static final List<String> CALLS = new CopyOnWriteArrayList<>();
    private static final List<List<String>> HEADERS = new CopyOnWriteArrayList<>();
    private static final List<String> FORMS = new CopyOnWriteArrayList<>();
    private static final List<String> BASIC = new CopyOnWriteArrayList<>();
    private static volatile int issuerStatus = 200;
    private static final HttpServer SERVER = startServer();
    private static final String ATTACHMENT = "{\"name\":\"backup-owner\",\"saving\":42}";
    @Autowired private AccountServiceClient client;
    @Autowired private OAuth2AuthorizedClientService store;
    @Autowired private ServiceClientCredentialsProperties properties;
    @Autowired private FeignContext feign;
    @Autowired private ApplicationContext context;
    @Autowired private FilterChainProxy filters;
    @Autowired private MockMvc mvc;
    @Autowired private NotificationService notifications;
    @MockBean private RecipientService recipients;
    @MockBean private EmailService email;

    @DynamicPropertySource
    public static void endpoints(DynamicPropertyRegistry registry) {
        String base = "http://127.0.0.1:" + SERVER.getAddress().getPort();
        registry.add("security.oauth2.client.accessTokenUri", () -> base + "/oauth/token");
        registry.add("clients.account-service.url", () -> base);
    }
    @Before public void resetFixture() {
        store.removeAuthorizedClient("notification-service", "notification-service");
        TOKENS.set(0); CALLS.clear(); HEADERS.clear(); FORMS.clear(); BASIC.clear(); issuerStatus = 200;
        SecurityContextHolder.clearContext();
    }
    @After public void clearPrincipal() { SecurityContextHolder.clearContext(); }
    @AfterClass public static void stopServer() { SERVER.stop(0); }

    @Test public void realFeignUsesServiceCredentialsAndCachesToken() {
        assertEquals(ATTACHMENT, client.getAccount("backup-owner"));
        client.getAccount("other-owner");
        assertEquals(Arrays.asList("GET /accounts/backup-owner", "GET /accounts/other-owner"), CALLS);
        assertEquals(1, TOKENS.get()); assertHeaders("service-token-1");
        assertEquals(Collections.singletonList("grant_type=client_credentials&scope=server"), FORMS);
        assertEquals(Collections.singletonList("Basic " + Base64.getEncoder().encodeToString(
                "notification-service:contract-secret".getBytes(StandardCharsets.UTF_8))), BASIC);
        assertNotNull(store.loadAuthorizedClient("notification-service", "notification-service"));
    }
    @Test public void expiredCachedTokenIsReacquiredWithoutSleep() {
        client.getAccount("before");
        Instant past = Instant.now().minusSeconds(86400);
        store.saveAuthorizedClient(new OAuth2AuthorizedClient(properties.registration(), "notification-service",
                new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER,"expired",past.minusSeconds(3600),past,
                        Collections.singleton("server"))), new UsernamePasswordAuthenticationToken("notification-service","unused"));
        client.getAccount("after");
        assertEquals(2, TOKENS.get());
        assertEquals(Collections.singletonList("Bearer service-token-1"), HEADERS.get(0));
        assertEquals(Collections.singletonList("Bearer service-token-2"), HEADERS.get(1));
    }
    @Test public void userContextAndExistingHeadersCannotReplaceServiceIdentity() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("browser-user","browser-secret"));
        client.getAccount("owner");
        RequestTemplate template = new RequestTemplate();
        template.header("Authorization", "Bearer browser-token");
        template.header("authorization", "Bearer other-token");
        feign.getInstance("account-service", ServiceTokenRequestInterceptor.class).apply(template);
        assertEquals(Collections.singletonList("Bearer service-token-1"), new ArrayList<>(template.headers().get("Authorization")));
        assertHeaders("service-token-1");
        assertNull(store.loadAuthorizedClient("notification-service", "browser-user"));
    }
    @Test public void rejectedCredentialsCannotCallAccount() { issuerStatus = 401; assertCallRejected(); }
    @Test public void unavailableIssuerCannotCallAccount() { issuerStatus = 503; assertCallRejected(); }

    @Test public void wiringIsScopedAndInvalidPropertiesFailWithoutSecrets() throws Exception {
        assertTrue(context.getBeansOfType(ServiceTokenRequestInterceptor.class).isEmpty());
        Map<String, RequestInterceptor> interceptors = feign.getInstances("account-service",RequestInterceptor.class);
        assertEquals(1, interceptors.values().stream().filter(i -> i instanceof ServiceTokenRequestInterceptor).count());
        assertFalse(interceptors.values().stream().anyMatch(i -> i.getClass().getName().contains("OAuth2FeignRequestInterceptor")));
        assertFalse(filters.getFilterChains().stream().flatMap(c -> c.getFilters().stream())
                .anyMatch(f -> f.getClass().getName().contains("OAuth2Login") || f.getClass().getName().contains("OAuth2AuthorizationRequestRedirect")));
        mvc.perform(get("/recipients/current")).andExpect(status().isUnauthorized());
        assertEquals("notification-service",properties.getClientId());
        for (String invalid : Arrays.asList("client","secret","uri","scope","grant")) {
            ServiceClientCredentialsProperties p = new ServiceClientCredentialsProperties();
            p.setClientId("notification-service"); p.setClientSecret("sensitive-fixture");
            p.setAccessTokenUri(properties.getAccessTokenUri()); p.setGrantType("client_credentials"); p.setScope(Collections.singletonList("server"));
            if(invalid.equals("client")) p.setClientId("");
            if(invalid.equals("secret")) p.setClientSecret("");
            if(invalid.equals("uri")) p.setAccessTokenUri("http://user:sensitive-fixture@localhost/token");
            if(invalid.equals("scope")) p.setScope(Collections.singletonList("ui"));
            if(invalid.equals("grant")) p.setGrantType("password");
            try { p.registration(); fail("Invalid configuration accepted"); }
            catch(IllegalArgumentException expected) { assertFalse(expected.getMessage().contains("sensitive-fixture")); }
        }
    }
    @Test public void asyncBackupSendsExactAttachmentBeforeMarkingNotified() throws Exception {
        Recipient recipient = recipient();
        when(recipients.findReadyToNotify(NotificationType.BACKUP)).thenReturn(Collections.singletonList(recipient));
        CountDownLatch marked = new CountDownLatch(1);
        doAnswer(invocation -> {marked.countDown(); return null;}).when(recipients).markNotified(NotificationType.BACKUP,recipient);
        notifications.sendBackupNotifications();
        assertTrue("Backup did not reach its last business action", marked.await(10,TimeUnit.SECONDS));
        InOrder order = inOrder(email,recipients);
        order.verify(email).send(NotificationType.BACKUP,recipient,ATTACHMENT);
        order.verify(recipients).markNotified(NotificationType.BACKUP,recipient);
        assertEquals(Collections.singletonList("GET /accounts/"+recipient.getAccountName()),CALLS);
        assertHeaders("service-token-1");
    }
    @Test public void asyncOAuthFailureReachesFinalCatchWithoutDownstreamEmailOrMark() throws Exception {
        issuerStatus = 401;
        Recipient recipient = recipient();
        when(recipients.findReadyToNotify(NotificationType.BACKUP)).thenReturn(Collections.singletonList(recipient));
        CountDownLatch caught = new CountDownLatch(1);
        Logger logger = (Logger) LoggerFactory.getLogger(NotificationServiceImpl.class);
        AppenderBase<ILoggingEvent> observer = new AppenderBase<ILoggingEvent>() {
            @Override protected void append(ILoggingEvent event) {
                if(event.getLevel()==Level.ERROR && event.getFormattedMessage().contains(recipient.getAccountName())
                        && hasOAuthCause(event.getThrowableProxy())) caught.countDown();
            }
        };
        observer.start(); logger.addAppender(observer);
        try {
            notifications.sendBackupNotifications();
            // The synchronous ERROR is the final action of the catch path, after the failed Feign call.
            assertTrue("Job did not reach final failure path",caught.await(10,TimeUnit.SECONDS));
            assertEquals(1,TOKENS.get()); assertTrue(CALLS.isEmpty()); assertTrue(HEADERS.isEmpty());
            verifyNoInteractions(email);
            verify(recipients,never()).markNotified(any(),any());
        } finally { logger.detachAppender(observer); observer.stop(); }
    }
    private Recipient recipient() { Recipient r = new Recipient(); r.setAccountName("backup-"+UUID.randomUUID()); r.setEmail("fixture@example.invalid"); return r; }
    private static boolean hasOAuthCause(ch.qos.logback.classic.spi.IThrowableProxy cause) {
        while(cause!=null) {
            if(cause.getClassName().equals("org.springframework.security.oauth2.client.ClientAuthorizationException")
                    || cause.getClassName().equals("org.springframework.security.oauth2.core.OAuth2AuthorizationException")) return true;
            cause=cause.getCause();
        }
        return false;
    }
    private void assertCallRejected() {
        try { client.getAccount("blocked"); fail("Issuer failure accepted"); }
        catch(RuntimeException expected) { assertEquals(1,TOKENS.get()); assertTrue(CALLS.isEmpty()); }
    }
    private void assertHeaders(String token) { assertFalse(HEADERS.isEmpty()); for(List<String> headers:HEADERS) assertEquals(Collections.singletonList("Bearer "+token),headers); }
    private static HttpServer startServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/",exchange -> {
                String path=exchange.getRequestURI().getPath(); int status=200; String response;
                if(path.equals("/oauth/token")) {
                    int number=TOKENS.incrementAndGet(); status=issuerStatus;
                    FORMS.add(org.springframework.util.StreamUtils.copyToString(exchange.getRequestBody(),StandardCharsets.UTF_8));
                    BASIC.add(exchange.getRequestHeaders().getFirst("Authorization"));
                    response=status==200 ? "{\"access_token\":\"service-token-"+number+"\",\"token_type\":\"bearer\",\"expires_in\":3600,\"scope\":\"server\"}" : "{\"error\":\"invalid_client\"}";
                } else {
                    CALLS.add(exchange.getRequestMethod()+" "+path);
                    List<String> headers=exchange.getRequestHeaders().get("Authorization");
                    HEADERS.add(headers==null?Collections.emptyList():new ArrayList<>(headers));
                    response=ATTACHMENT;
                }
                exchange.getResponseHeaders().set("Content-Type","application/json");
                byte[] bytes=response.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status,bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
            }); server.start(); return server;
        } catch(java.io.IOException e) { throw new ExceptionInInitializerError(e); }
    }
}
