package com.piggymetrics.account.client;

import com.piggymetrics.account.domain.Account;
import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.OutputCaptureRule;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit4.SpringRunner;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Characterization of name-based client-side load balancing and the circuit breaker time limit fallback of
 * {@link StatisticsServiceClient}, with no Eureka. It records the behavior of Spring Cloud LoadBalancer and
 * CircuitBreaker (Resilience4j); it replaced the Ribbon/Hystrix characterization of F0 (see the F1 record of the E8 plan).
 * The 'url' property of the client is left empty, which makes Feign resolve the service by name.
 */
@RunWith(SpringRunner.class)
@SpringBootTest(properties = {
        "clients.statistics-service.url=",
        "feign.circuitbreaker.enabled=true",
        "piggymetrics.circuit-breaker.timeout=1500ms"})
public class StatisticsServiceClientResilienceContractTest {
    private static final HttpServer FIRST = startServer("first");
    private static final HttpServer SECOND = startServer("second");
    private static final List<String> HITS = new CopyOnWriteArrayList<>();
    private static final AtomicInteger FIRST_DELAY_MS = new AtomicInteger();

    @Autowired private StatisticsServiceClient statistics;
    @Autowired private OAuth2AuthorizedClientService store;
    @Autowired private CircuitBreakerRegistry circuitBreakers;
    @Rule public final OutputCaptureRule output = new OutputCaptureRule();

    @DynamicPropertySource
    public static void endpoints(DynamicPropertyRegistry registry) {
        registry.add("security.oauth2.client.accessTokenUri", () -> base(FIRST) + "/oauth/token");
        registry.add("spring.cloud.discovery.client.simple.instances.statistics-service[0].uri", () -> base(FIRST));
        registry.add("spring.cloud.discovery.client.simple.instances.statistics-service[1].uri", () -> base(SECOND));
    }

    @Before
    public void reset() {
        resetCircuits();
        store.removeAuthorizedClient("account-service", "account-service");
        HITS.clear();
        FIRST_DELAY_MS.set(0);
    }

    @After
    public void restoreFixture() {
        FIRST_DELAY_MS.set(0);
        resetCircuits();
    }

    @AfterClass
    public static void stopServers() {
        FIRST.stop(0);
        SECOND.stop(0);
    }

    @Test
    public void serviceNameIsBalancedRoundRobinAcrossTheConfiguredServers() {
        for (int i = 0; i < 6; i++) {
            statistics.updateStatistics("balanced-" + i, new Account());
        }
        assertEquals(6, HITS.size());
        assertEquals(3, HITS.stream().filter("first"::equals).count());
        assertEquals(3, HITS.stream().filter("second"::equals).count());
    }

    @Test
    public void slowServerBeyondTimeLimitYieldsFallbackWithoutExceptionAndBoundedLatency() {
        FIRST_DELAY_MS.set(6000);
        long slowest = 0;
        for (int i = 0; i < 4; i++) {
            long start = System.nanoTime();
            statistics.updateStatistics("timeout-" + i, new Account()); // fallback returns void
            slowest = Math.max(slowest, (System.nanoTime() - start) / 1_000_000);
        }
        output.expect(containsString("Error during update statistics for account: timeout-"));
        assertTrue("a call took " + slowest + " ms, expected near the 1500 ms timeout", slowest < 4500);
    }

    private void resetCircuits() {
        circuitBreakers.getAllCircuitBreakers().forEach(io.github.resilience4j.circuitbreaker.CircuitBreaker::reset);
    }

    private static String base(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static HttpServer startServer(String name) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                drain(exchange.getRequestBody());
                String path = exchange.getRequestURI().getPath();
                if (path.equals("/oauth/token")) {
                    byte[] body = "{\"access_token\":\"t\",\"token_type\":\"bearer\",\"expires_in\":3600,\"scope\":\"server\"}"
                            .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                } else {
                    HITS.add(name); // recorded on arrival so a late response cannot leak into another test
                    if (name.equals("first")) {
                        sleep(FIRST_DELAY_MS.get());
                    }
                    exchange.sendResponseHeaders(200, -1);
                }
                exchange.close();
            });
            server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool(runnable -> {
                Thread thread = new Thread(runnable);
                thread.setDaemon(true);
                return thread;
            }));
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void drain(java.io.InputStream in) throws IOException {
        byte[] buffer = new byte[1024];
        while (in.read(buffer) != -1) {
            // discard
        }
    }

    private static void sleep(int millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
