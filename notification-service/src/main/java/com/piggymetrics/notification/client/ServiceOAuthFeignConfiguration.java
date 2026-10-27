package com.piggymetrics.notification.client;

import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import com.piggymetrics.notification.config.ServiceClientCredentialsProperties;

// Intentionally not @Configuration: loaded only in the named account Feign child context.
public class ServiceOAuthFeignConfiguration {
    @Bean
    public ServiceTokenRequestInterceptor serviceTokenRequestInterceptor(
            OAuth2AuthorizedClientManager manager, ServiceClientCredentialsProperties properties) {
        return new ServiceTokenRequestInterceptor(manager, properties.getClientId());
    }
}
