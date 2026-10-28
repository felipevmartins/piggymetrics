package com.piggymetrics.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.piggymetrics.notification.domain.*;
import com.piggymetrics.notification.repository.RecipientRepository;
import com.sun.net.httpserver.HttpServer;
import org.junit.*;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@RunWith(SpringRunner.class)
@SpringBootTest(properties={"backup.cron=-","remind.cron=-"})
@AutoConfigureMockMvc
public class NotificationBearerContractTest {
    private static volatile String owner;
    private static volatile int issuerStatus=200;
    private static volatile String issuerBody;
    private static final AtomicInteger USERINFO=new AtomicInteger();
    private static final AtomicInteger REDIRECT=new AtomicInteger();
    private static final HttpServer SERVER=startServer();
    private String other;
    private Recipient previousService;
    private boolean serviceSnapshotCaptured;
    @Autowired private RecipientRepository recipients;
    @Autowired private ObjectMapper mapper;
    @Autowired private MockMvc mvc;
    @Autowired private org.springframework.context.ApplicationContext context;
    @Autowired private org.springframework.security.web.FilterChainProxy filters;
    @DynamicPropertySource public static void endpoints(DynamicPropertyRegistry registry) {
        registry.add("security.oauth2.resource.introspection-uri",()->"http://127.0.0.1:"+SERVER.getAddress().getPort()+"/oauth2/introspect");
    }
    @Before public void seed() {
        owner="recipient-"+UUID.randomUUID(); other=owner+"-other";
        previousService=recipients.findByAccountName("notification-service"); serviceSnapshotCaptured=true;
        recipients.save(fixture(owner)); recipients.save(fixture(other)); recipients.save(fixture("notification-service"));
        issuerStatus=200; issuerBody=null; USERINFO.set(0); REDIRECT.set(0);
    }
    @After public void cleanup() {
        if(owner!=null) recipients.deleteById(owner);
        if(other!=null) recipients.deleteById(other);
        if(serviceSnapshotCaptured) {
            if(previousService==null) recipients.deleteById("notification-service"); else recipients.save(previousService);
        }
    }
    @AfterClass public static void closeServer() { SERVER.stop(0); }
    @Test public void uiReadsItsOwnSettingsAndStoredConverters() throws Exception {
        mvc.perform(get("/recipients/current").header("Authorization","Bearer user-ui"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.accountName").value(owner))
                .andExpect(jsonPath("$.email").value("before@example.invalid"))
                .andExpect(jsonPath("$.scheduledNotifications.BACKUP.frequency").value("WEEKLY"));
        assertSettings(owner,"before@example.invalid",Frequency.WEEKLY,true,123456789L);
    }
    @Test public void uiPutUsesPrincipalAndPreservesOtherAccountCompletely() throws Exception {
        String otherBefore=mapper.writeValueAsString(recipients.findByAccountName(other));
        mvc.perform(put("/recipients/current").header("Authorization","Bearer user-ui")
                .contentType(MediaType.APPLICATION_JSON).content(payload(other,"after@example.invalid")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.accountName").value(owner));
        assertSettings(owner,"after@example.invalid",Frequency.MONTHLY,false,987654321L);
        assertEquals(otherBefore,mapper.writeValueAsString(recipients.findByAccountName(other)));
    }
    @Test public void servicePrincipalReadsAndWritesOnlyItsOwnSettings() throws Exception {
        String ownerBefore=mapper.writeValueAsString(recipients.findByAccountName(owner));
        mvc.perform(get("/recipients/current").header("Authorization","Bearer service-server"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.accountName").value("notification-service"));
        mvc.perform(put("/recipients/current").header("Authorization","Bearer service-server")
                .contentType(MediaType.APPLICATION_JSON).content(payload(owner,"service@example.invalid")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.accountName").value("notification-service"));
        assertSettings("notification-service","service@example.invalid",Frequency.MONTHLY,false,987654321L);
        assertEquals(ownerBefore,mapper.writeValueAsString(recipients.findByAccountName(owner)));
    }
    @Test public void anonymousGetAndPutCannotReadOrWrite() throws Exception {
        String before=mapper.writeValueAsString(recipients.findByAccountName(owner));
        mvc.perform(get("/recipients/current")).andExpect(status().isUnauthorized());
        mvc.perform(put("/recipients/current").contentType(MediaType.APPLICATION_JSON).content(payload(owner,"blocked@example.invalid")))
                .andExpect(status().isUnauthorized());
        assertEquals(before,mapper.writeValueAsString(recipients.findByAccountName(owner))); assertEquals(0,USERINFO.get());
    }
    @Test public void invalidEmailIsRejectedWithoutChangingStoredSettings() throws Exception {
        String before=mapper.writeValueAsString(recipients.findByAccountName(owner));
        mvc.perform(put("/recipients/current").header("Authorization","Bearer user-ui")
                .contentType(MediaType.APPLICATION_JSON).content(payload(owner,"not-an-email")))
                .andExpect(status().isBadRequest());
        assertEquals(before,mapper.writeValueAsString(recipients.findByAccountName(owner)));
    }

        @Test public void modernResolverRejectsQueryFormMalformedAndDuplicateCredentials() throws Exception {
        mvc.perform(get("/recipients/current").param("access_token","user-ui")).andExpect(status().isUnauthorized());
        mvc.perform(post("/recipients/current").contentType(MediaType.APPLICATION_FORM_URLENCODED).content("access_token=user-ui")).andExpect(status().isUnauthorized());
        mvc.perform(get("/recipients/current").header("Authorization","Bearer bad token")).andExpect(status().isUnauthorized());
        mvc.perform(get("/recipients/current").header("Authorization","Bearer user-ui","Bearer service-server")).andExpect(status().isBadRequest());
        mvc.perform(get("/recipients/current").header("Authorization","Bearer user-ui").param("access_token","service-server")).andExpect(status().isBadRequest());
        assertEquals(0,USERINFO.get()); assertSettings(owner,"before@example.invalid",Frequency.WEEKLY,true,123456789L);
    }
    @Test public void issuerFailureMalformedSchemaAndRedirectFailClosed() throws Exception {
        for(int code:Arrays.asList(401,500,302)) {
            issuerStatus=code;
            mvc.perform(get("/recipients/current").header("Authorization","Bearer user-ui")).andExpect(status().isUnauthorized());
        }
        assertEquals(0,REDIRECT.get()); issuerStatus=200;
        String valid=profile(owner,"browser","ui");
        for(String bad:Arrays.asList("not-json","[]","{\"error\":\"invalid_token\"}",
                valid.replace("\"scope\":\"ui\"","\"scope\":\"\""),valid.replace("\"scope\":\"ui\"","\"scope\":42"),
                valid.replace(",\"scope\":\"ui\"",""),valid.replace("\"client_id\":\"browser\"","\"client_id\":\"\""),
                valid.replace("\"active\":true","\"active\":false"),valid.replace("\"active\":true,",""))) {
            issuerBody=bad;
            mvc.perform(put("/recipients/current").header("Authorization","Bearer user-ui")
                    .contentType(MediaType.APPLICATION_JSON).content(payload(owner,"blocked@example.invalid"))).andExpect(status().isUnauthorized());
        }
        assertSettings(owner,"before@example.invalid",Frequency.WEEKLY,true,123456789L);
    }
    @Test public void modernInboundIsStatelessAndHasNoLegacyOAuthLibrary() throws Exception {
        MvcResult result=mvc.perform(get("/recipients/current").header("Authorization","Bearer user-ui"))
                .andExpect(status().isOk()).andExpect(header().doesNotExist("Set-Cookie")).andReturn();
        assertNull(result.getRequest().getSession(false));
        Object adapter=context.getBean(org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector.class);
        assertEquals("com.piggymetrics.notification.service.security.IntrospectionEndpointTokenIntrospector",adapter.getClass().getName());
        assertTrue(filters.getFilterChains().stream().flatMap(c->c.getFilters().stream()).anyMatch(f->f.getClass().getSimpleName().equals("BearerTokenAuthenticationFilter")));
        assertFalse(filters.getFilterChains().stream().flatMap(c->c.getFilters().stream()).anyMatch(f->f.getClass().getSimpleName().contains("OAuth2Login")));
        try { Class.forName("org.springframework.security.oauth2.provider.token.ResourceServerTokenServices"); fail("Legacy OAuth still present"); }
        catch(ClassNotFoundException expected) { }
    }


    private Recipient fixture(String name) {
        Recipient r=new Recipient(); r.setAccountName(name); r.setEmail("before@example.invalid");
        NotificationSettings settings=new NotificationSettings(); settings.setActive(true); settings.setFrequency(Frequency.WEEKLY); settings.setLastNotified(new Date(123456789L));
        Map<NotificationType,NotificationSettings> map=new EnumMap<>(NotificationType.class); map.put(NotificationType.BACKUP,settings);
        r.setScheduledNotifications(map); return r;
    }
    private String payload(String name,String email) {
        return "{\"accountName\":\""+name+"\",\"email\":\""+email+"\",\"scheduledNotifications\":{\"BACKUP\":{\"active\":false,\"frequency\":\"MONTHLY\",\"lastNotified\":987654321}}}";
    }
    private void assertSettings(String name,String email,Frequency frequency,boolean active,long last) {
        Recipient r=recipients.findByAccountName(name); assertNotNull(r); assertEquals(name,r.getAccountName()); assertEquals(email,r.getEmail());
        assertEquals(Collections.singleton(NotificationType.BACKUP),r.getScheduledNotifications().keySet());
        NotificationSettings settings=r.getScheduledNotifications().get(NotificationType.BACKUP);
        assertEquals(frequency,settings.getFrequency()); assertEquals(Boolean.valueOf(active),settings.getActive()); assertEquals(new Date(last),settings.getLastNotified());
    }
    private static String profile(String name,String client,String scope) {
        return "{\"active\":true,\"sub\":\""+name+"\",\"client_id\":\""+client+"\",\"scope\":\""+scope+"\"}";
    }
    private static HttpServer startServer() {
        try {
            HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/",exchange->{
                String path=exchange.getRequestURI().getPath(); String body; int status=200;
                if(path.equals("/oauth2/introspect")) {
                    USERINFO.incrementAndGet(); status=issuerStatus;
                    String form=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
                    String presented="POST".equals(exchange.getRequestMethod())?"Bearer "+java.net.URLDecoder.decode(form.replaceFirst("^token=([^&]*).*$","$1"),"UTF-8"):"";
                    body=issuerBody!=null?issuerBody:"Bearer service-server".equals(presented)
                            ?profile("notification-service","notification-service","server"):profile(owner,"browser","ui");
                    if(status==302) exchange.getResponseHeaders().set("Location","http://localhost:"+server.getAddress().getPort()+"/redirect");
                } else { REDIRECT.incrementAndGet(); body=profile(owner,"browser","ui"); }
                byte[] bytes=body.getBytes(StandardCharsets.UTF_8); exchange.getResponseHeaders().set("Content-Type","application/json");
                exchange.sendResponseHeaders(status,bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
            }); server.start(); return server;
        } catch(java.io.IOException e) { throw new ExceptionInInitializerError(e); }
    }
}
