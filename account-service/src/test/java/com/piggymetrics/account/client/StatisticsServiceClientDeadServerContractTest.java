package com.piggymetrics.account.client;

import com.piggymetrics.account.domain.Account;
import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit4.SpringRunner;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;

/**
 * Characterization of the Spring Cloud LoadBalancer behavior when one listed server refuses connections:
 * the call must not throw (circuit breaker fallback), and the live server receives exactly half of the requests.
 * The value is the same one observed with Ribbon/Hystrix in F0, so the replacement kept this contract.
 */
@RunWith(SpringRunner.class)
@SpringBootTest(properties = {
        "clients.statistics-service.url=",
        "feign.circuitbreaker.enabled=true",
        "piggymetrics.circuit-breaker.timeout=3000ms"})
public class StatisticsServiceClientDeadServerContractTest {
    private static final int CLOSED_PORT = closedPort();
    private static final HttpServer LIVE = startLive();
    private static final AtomicInteger LIVE_HITS = new AtomicInteger();
    private static final int CALLS = 6;

    @Autowired private StatisticsServiceClient statistics;
    @Autowired private OAuth2AuthorizedClientService store;
    @Autowired private CircuitBreakerRegistry circuitBreakers;

    @DynamicPropertySource
    public static void endpoints(DynamicPropertyRegistry registry) {
        String live = "127.0.0.1:" + LIVE.getAddress().getPort();
        registry.add("security.oauth2.client.accessTokenUri", () -> "http://" + live + "/oauth/token");
        registry.add("spring.cloud.discovery.client.simple.instances.statistics-service[0].uri",
                () -> "http://127.0.0.1:" + CLOSED_PORT);
        registry.add("spring.cloud.discovery.client.simple.instances.statistics-service[1].uri", () -> "http://" + live);
    }

    @Before
    public void reset() {
        resetCircuits();
        store.removeAuthorizedClient("account-service", "account-service");
        LIVE_HITS.set(0);
    }

    @org.junit.After
    public void resetCircuitsAfter() {
        resetCircuits();
    }

    private void resetCircuits() {
        circuitBreakers.getAllCircuitBreakers().forEach(io.github.resilience4j.circuitbreaker.CircuitBreaker::reset);
    }

    @AfterClass
    public static void stop() {
        LIVE.stop(0);
    }

    @Test
    public void callsNeverThrowAndRecordHowManyReachTheLiveServer() {
        for (int i = 0; i < CALLS; i++) {
            statistics.updateStatistics("dead-" + i, new Account());
        }
        // Observed with LoadBalancer (Boot 2.3 / Hoxton), same as Ribbon before: strict alternation and no retry on
        // the next server for a refused connection, so half of the PUTs end in the circuit breaker fallback. A change
        // that retries alters this contract on purpose and must update this value together with the plan.
        assertEquals(3, LIVE_HITS.get());
    }

    private static int closedPort() {
        try (ServerSocket socket = new ServerSocket(0, 0, java.net.InetAddress.getByName("127.0.0.1"))) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static HttpServer startLive() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                byte[] buffer = new byte[1024];
                while (exchange.getRequestBody().read(buffer) != -1) {
                    // discard
                }
                if (exchange.getRequestURI().getPath().equals("/oauth/token")) {
                    byte[] body = "{\"access_token\":\"t\",\"token_type\":\"bearer\",\"expires_in\":3600,\"scope\":\"server\"}"
                            .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                } else {
                    LIVE_HITS.incrementAndGet();
                    exchange.sendResponseHeaders(200, -1);
                }
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
