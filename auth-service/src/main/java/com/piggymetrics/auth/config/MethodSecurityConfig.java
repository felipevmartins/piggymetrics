package com.piggymetrics.auth.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.provider.expression.OAuth2MethodSecurityExpressionHandler;

/**
 * Provides the handler that resolves #oauth2 in @PreAuthorize (e.g. #oauth2.hasScope('server')).
 * With Spring Security 5.7 the legacy autoconfiguration no longer applies it, so the expression
 * was evaluated against a null context object.
 */
@Configuration
public class MethodSecurityConfig {

	@Bean
	public static OAuth2MethodSecurityExpressionHandler oauth2MethodSecurityExpressionHandler() {
		return new OAuth2MethodSecurityExpressionHandler();
	}
}
