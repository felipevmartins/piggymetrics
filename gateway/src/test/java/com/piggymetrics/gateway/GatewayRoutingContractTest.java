package com.piggymetrics.gateway;

import com.sun.net.httpserver.HttpServer;
import org.junit.AfterClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit4.SpringRunner;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Contrato de roteamento externo do gateway: prefixos mantidos (sem strip), Authorization e Cookie
 * repassados ao servico de destino, estaticos publicos e rota desconhecida sem destino.
 * Os destinos sao servidores locais; o contrato e o mesmo antes e depois da troca do Zuul.
 */
@RunWith(SpringRunner.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "eureka.client.enabled=false", "spring.cloud.config.enabled=false", "spring.cloud.bus.enabled=false"})
public class GatewayRoutingContractTest {

    private static final List<String> SEEN = new CopyOnWriteArrayList<>();
    private static final HttpServer DOWNSTREAM = start();

    @LocalServerPort
    private int port;

    private static HttpServer start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                SEEN.add(exchange.getRequestMethod() + " " + exchange.getRequestURI()
                        + " auth=" + exchange.getRequestHeaders().getFirst("Authorization")
                        + " cookie=" + exchange.getRequestHeaders().getFirst("Cookie"));
                byte[] body = "downstream".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return server;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @AfterClass
    public static void stop() {
        DOWNSTREAM.stop(0);
    }

    @DynamicPropertySource
    static void routes(DynamicPropertyRegistry registry) {
        String url = "http://127.0.0.1:" + DOWNSTREAM.getAddress().getPort();
        String[][] routes = {{"auth-service", "/uaa/**"}, {"account-service", "/accounts/**"},
                {"statistics-service", "/statistics/**"}, {"notification-service", "/notifications/**"}};
        registry.add("zuul.ignoredServices", () -> "*");
        for (String[] r : routes) {
            registry.add("zuul.routes." + r[0] + ".path", () -> r[1]);
            registry.add("zuul.routes." + r[0] + ".url", () -> url);
            registry.add("zuul.routes." + r[0] + ".stripPrefix", () -> "false");
            registry.add("zuul.routes." + r[0] + ".sensitiveHeaders", () -> "");
        }
    }

    private String[] get(String path, String... headers) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        c.setInstanceFollowRedirects(false);
        for (int i = 0; i < headers.length; i += 2) {
            c.setRequestProperty(headers[i], headers[i + 1]);
        }
        int status = c.getResponseCode();
        InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (in != null) {
            byte[] buf = new byte[4096];
            for (int n; (n = in.read(buf)) > 0; ) {
                out.write(buf, 0, n);
            }
        }
        return new String[]{String.valueOf(status), out.toString("UTF-8")};
    }

    @Test
    public void everyPrefixIsForwardedWithoutStrippingAndKeepsAuthorizationAndCookie() throws Exception {
        SEEN.clear();
        for (String path : new String[]{"/uaa/users/current", "/accounts/current", "/statistics/current",
                "/notifications/recipients/current"}) {
            String[] r = get(path + "?q=1", "Authorization", "Bearer abc", "Cookie", "JSESSIONID=1");
            assertEquals(path + " body=" + r[1] + " seen=" + SEEN, "200", r[0]);
            assertEquals(path, "downstream", r[1]);
            assertTrue(path + " -> " + SEEN, SEEN.contains("GET " + path + "?q=1 auth=Bearer abc cookie=JSESSIONID=1"));
        }
    }

    @Test
    public void staticIndexIsServedWithoutCredentialsAndNotForwarded() throws Exception {
        SEEN.clear();
        String[] r = get("/index.html");
        assertEquals("200", r[0]);
        assertTrue(r[1].toLowerCase().contains("<html"));
        assertTrue(SEEN.toString(), SEEN.isEmpty());
    }

    @Test
    public void unknownPathIsNotForwardedDownstream() throws Exception {
        SEEN.clear();
        String[] r = get("/nothing-here");
        assertEquals("404", r[0]);
        assertTrue(SEEN.toString(), SEEN.isEmpty());
    }
}
