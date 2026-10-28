package com.piggymetrics.auth.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.server.authorization.InMemoryOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.OAuth2TokenFormat;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.security.oauth2.server.resource.introspection.BadOpaqueTokenException;
import org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DefaultOAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Registered clients and token storage. Tokens are opaque (reference) and kept in memory, so restarting
 * auth-service invalidates them, as the legacy InMemoryTokenStore did.
 */
@Configuration
public class OAuth2AuthorizationConfig {

    private static final Logger log = LoggerFactory.getLogger(OAuth2AuthorizationConfig.class);

    private static TokenSettings tokenSettings() {
        return TokenSettings.builder()
                .accessTokenFormat(OAuth2TokenFormat.REFERENCE)
                .accessTokenTimeToLive(Duration.ofHours(12))
                .build();
    }

    @Bean
    public RegisteredClientRepository registeredClientRepository(Environment env, PasswordEncoder passwordEncoder,
            @Value("${piggymetrics.auth.browser.redirect-uris:http://localhost/}") List<String> browserRedirectUris) {

        // TODO persist clients details
        List<RegisteredClient> clients = new ArrayList<>();

        RegisteredClient.Builder browser = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId("browser")
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .scope("ui")
                .clientSettings(ClientSettings.builder().requireProofKey(true).requireAuthorizationConsent(false).build())
                .tokenSettings(tokenSettings());
        browserRedirectUris.forEach(uri -> browser.redirectUri(uri.trim()));
        clients.add(browser.build());

        addServiceClient(clients, env, passwordEncoder, "account-service", "ACCOUNT_SERVICE_PASSWORD");
        addServiceClient(clients, env, passwordEncoder, "statistics-service", "STATISTICS_SERVICE_PASSWORD");
        addServiceClient(clients, env, passwordEncoder, "notification-service", "NOTIFICATION_SERVICE_PASSWORD");
        return new InMemoryRegisteredClientRepository(clients);
    }

    private void addServiceClient(List<RegisteredClient> clients, Environment env, PasswordEncoder passwordEncoder, String clientId, String secretProperty) {
        String secret = env.getProperty(secretProperty);
        if (secret == null || secret.isEmpty()) {
            log.warn("Client {} not registered: {} is not set", clientId, secretProperty);
            return;
        }
        clients.add(RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId(clientId)
                .clientSecret(passwordEncoder.encode(secret))
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .scope("server")
                .tokenSettings(tokenSettings())
                .build());
    }

    @Bean
    public OAuth2AuthorizationService authorizationService() {
        return new InMemoryOAuth2AuthorizationService();
    }

    /** Local introspection for auth-service's own /users endpoints, without an HTTP round trip to itself. */
    @Bean
    public OpaqueTokenIntrospector localOpaqueTokenIntrospector(OAuth2AuthorizationService authorizations) {
        return token -> {
            OAuth2Authorization authorization = authorizations.findByToken(token, OAuth2TokenType.ACCESS_TOKEN);
            OAuth2Authorization.Token<OAuth2AccessToken> access = authorization == null ? null : authorization.getAccessToken();
            if (access == null || !access.isActive()) {
                throw new BadOpaqueTokenException("Token could not be validated");
            }
            List<GrantedAuthority> authorities = new ArrayList<>();
            access.getToken().getScopes().forEach(scope -> authorities.add(new SimpleGrantedAuthority("SCOPE_" + scope)));
            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put("sub", authorization.getPrincipalName());
            attributes.put("scope", new ArrayList<>(access.getToken().getScopes()));
            OAuth2AuthenticatedPrincipal principal =
                    new DefaultOAuth2AuthenticatedPrincipal(authorization.getPrincipalName(), attributes, authorities);
            return principal;
        };
    }
}
