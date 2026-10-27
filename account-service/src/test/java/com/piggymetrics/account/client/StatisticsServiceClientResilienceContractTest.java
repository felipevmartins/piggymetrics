package com.piggymetrics.account.client;

import com.piggymetrics.account.domain.Account;
import com.sun.net.httpserver.HttpServer;
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
 * Characterization of name-based client-side load balancing and the Hystrix timeout fallback of
 * {@link StatisticsServiceClient}, with no Eureka. It records the behavior of the current Ribbon/Hystrix
 * stack so a replacement (Spring Cloud LoadBalancer / CircuitBreaker) can be compared with it.
 * The 'url' property of the client is left empty, which makes Feign resolve the service by name.
 */
@RunWith(SpringRunner.class)
@SpringBootTest(properties = {
        "clients.statistics-service.url=",
        "feign.hystrix.enabled=true",
        "ribbon.eureka.enabled=false",
        "hystrix.command.default.execution.isolation.thread.timeoutInMilliseconds=1500"})
public class StatisticsServiceClientResilienceContractTest {
    private static final HttpServer FIRST = startServer("first");
    private static final HttpServer SECOND = startServer("second");
    private static final List<String> HITS = new CopyOnWriteArrayList<>();
    private static final AtomicInteger FIRST_DELAY_MS = new AtomicInteger();

    @Autowired private StatisticsServiceClient statistics;
    @Autowired private OAuth2AuthorizedClientService store;
    @Rule public final OutputCaptureRule output = new OutputCaptureRule();

    @DynamicPropertySource
    public static void endpoints(DynamicPropertyRegistry registry) {
        registry.add("security.oauth2.client.accessTokenUri", () -> base(FIRST) + "/oauth/token");
        registry.add("statistics-service.ribbon.listOfServers",
                () -> "127.0.0.1:" + FIRST.getAddress().getPort() + ",127.0.0.1:" + SECOND.getAddress().getPort());
    }

    @Before
    public void reset() {
        com.netflix.hystrix.Hystrix.reset(); // circuit state is static in the JVM and leaks between contexts
        store.removeAuthorizedClient("account-service", "account-service");
        HITS.clear();
        FIRST_DELAY_MS.set(0);
    }

    @After
    public void restoreFixture() {
        FIRST_DELAY_MS.set(0);
        com.netflix.hystrix.Hystrix.reset();
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
    public void slowServerBeyondHystrixTimeoutYieldsFallbackWithoutExceptionAndBoundedLatency() {
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
