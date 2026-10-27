package com.piggymetrics.account.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.*;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;

@Configuration
@EnableConfigurationProperties(ServiceClientCredentialsProperties.class)
public class ServiceOAuthClientConfiguration {
    @Bean
    public OAuth2AuthorizedClientService serviceAuthorizedClients(ServiceClientCredentialsProperties properties) {
        return new InMemoryOAuth2AuthorizedClientService(
                new InMemoryClientRegistrationRepository(properties.registration()));
    }
    @Bean
    public OAuth2AuthorizedClientManager serviceAuthorizedClientManager(
            ServiceClientCredentialsProperties properties, OAuth2AuthorizedClientService serviceAuthorizedClients) {
        AuthorizedClientServiceOAuth2AuthorizedClientManager manager =
                new AuthorizedClientServiceOAuth2AuthorizedClientManager(
                        new InMemoryClientRegistrationRepository(properties.registration()), serviceAuthorizedClients);
        manager.setAuthorizedClientProvider(OAuth2AuthorizedClientProviderBuilder.builder().clientCredentials().build());
        return manager;
    }
}
