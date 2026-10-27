package com.piggymetrics.account.config;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import feign.Feign;
import feign.Retryer;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JConfigBuilder;
import org.springframework.cloud.client.circuitbreaker.Customizer;
import org.springframework.cloud.openfeign.FeignCircuitBreaker;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;

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

	/**
	 * Spring Cloud Sleuth 2.2.x (Hoxton) registers a plain {@code Feign.Builder} in the application context when
	 * {@code feign.hystrix.enabled} is absent, and it hides the circuit breaker builder of Spring Cloud OpenFeign.
	 * Declaring the circuit breaker builder here takes precedence over the Sleuth one; tracing still wraps the
	 * Feign client. Remove together with Sleuth (replaced by Micrometer Tracing in the Boot 3 step).
	 */
	@Bean
	@Scope("prototype")
	@ConditionalOnProperty("feign.circuitbreaker.enabled")
	public Feign.Builder feignCircuitBreakerBuilder() {
		return FeignCircuitBreaker.builder().retryer(Retryer.NEVER_RETRY);
	}
}
