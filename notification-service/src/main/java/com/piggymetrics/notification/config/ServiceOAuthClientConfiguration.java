package com.piggymetrics.notification.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.oauth2.client.endpoint.RestClientClientCredentialsTokenResponseClient;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.web.client.RestClient;

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
        // The default token RestClient picks Apache HttpClient 5 when present, which silently repeats a POST answered with 503.
        RestClientClientCredentialsTokenResponseClient tokenClient = new RestClientClientCredentialsTokenResponseClient();
        tokenClient.setRestClient(RestClient.builder()
                .requestFactory(new JdkClientHttpRequestFactory())
                .configureMessageConverters(converters -> converters.registerDefaults()
                        .addCustomConverter(new OAuth2AccessTokenResponseHttpMessageConverter()))
                .defaultStatusHandler(new OAuth2ErrorResponseErrorHandler())
                .build());
        manager.setAuthorizedClientProvider(OAuth2AuthorizedClientProviderBuilder.builder()
                .clientCredentials(c -> c.accessTokenResponseClient(tokenClient)).build());
        return manager;
    }
}
