package com.piggymetrics.statistics.client;

import com.piggymetrics.statistics.domain.Currency;
import com.piggymetrics.statistics.domain.ExchangeRatesContainer;
import com.sun.net.httpserver.HttpServer;
import org.junit.AfterClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringRunner;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static org.junit.Assert.assertEquals;

@RunWith(SpringRunner.class)
@SpringBootTest
@ContextConfiguration(initializers = ExchangeRatesClientTest.RatesServerInitializer.class)
public class ExchangeRatesClientTest {

    private static HttpServer server;

    @Autowired
    private ExchangeRatesClient client;

    public static class RatesServerInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override
        public void initialize(ConfigurableApplicationContext context) {
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                server.createContext("/latest", exchange -> {
                    String query = exchange.getRequestURI().getRawQuery();
                    String base = "base=EUR".equals(query) ? "EUR" : "USD";
                    if (!"base=EUR".equals(query) && !"base=USD".equals(query)) {
                        exchange.sendResponseHeaders(400, -1);
                        exchange.close();
                        return;
                    }
                    byte[] body = ("{\"date\":\"2020-01-02\",\"base\":\"" + base
                            + "\",\"rates\":{\"USD\":1.10,\"EUR\":0.90,\"RUB\":70.00}}")
                            .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, body.length);
                    try {
                        exchange.getResponseBody().write(body);
                    } finally {
                        exchange.close();
                    }
                });
                server.start();
                TestPropertyValues.of("rates.url=http://127.0.0.1:" + server.getAddress().getPort())
                        .applyTo(context.getEnvironment());
            } catch (IOException exception) {
                throw new IllegalStateException("Could not start the local exchange rates server", exception);
            }
        }
    }

    @AfterClass
    public static void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    public void shouldRetrieveExchangeRates() {
        ExchangeRatesContainer container = client.getRates(Currency.getBase());

        assertEquals(LocalDate.now(), container.getDate());
        assertEquals(Currency.getBase(), container.getBase());
        assertEquals(new BigDecimal("1.10"), container.getRates().get(Currency.USD.name()));
        assertEquals(new BigDecimal("0.90"), container.getRates().get(Currency.EUR.name()));
        assertEquals(new BigDecimal("70.00"), container.getRates().get(Currency.RUB.name()));
    }

    @Test
    public void shouldRetrieveExchangeRatesForSpecifiedCurrency() {
        Currency requestedCurrency = Currency.EUR;
        ExchangeRatesContainer container = client.getRates(requestedCurrency);

        assertEquals(LocalDate.now(), container.getDate());
        assertEquals(requestedCurrency, container.getBase());
        assertEquals(new BigDecimal("0.90"), container.getRates().get(requestedCurrency.name()));
    }
}
