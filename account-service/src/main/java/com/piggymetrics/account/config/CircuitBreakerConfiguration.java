package com.piggymetrics.account.config;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JConfigBuilder;
import org.springframework.cloud.client.circuitbreaker.Customizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Replaces the former Hystrix default command timeout: a call that exceeds it is cut and the Feign fallback runs.
 */
@Configuration
public class CircuitBreakerConfiguration {

	@Bean
	public Customizer<Resilience4JCircuitBreakerFactory> defaultCircuitBreakerCustomizer(
			@Value("${piggymetrics.circuit-breaker.timeout:10s}") Duration timeout) {
		return factory -> factory.configureDefault(id -> new Resilience4JConfigBuilder(id)
				.timeLimiterConfig(TimeLimiterConfig.custom().timeoutDuration(timeout).build())
				.circuitBreakerConfig(CircuitBreakerConfig.ofDefaults())
				.build());
	}
}
