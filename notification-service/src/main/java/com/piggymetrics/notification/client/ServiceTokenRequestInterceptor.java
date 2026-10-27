package com.piggymetrics.notification.client;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.util.StringUtils;

public class ServiceTokenRequestInterceptor implements RequestInterceptor {
    private final OAuth2AuthorizedClientManager manager;
    private final String servicePrincipal;

    public ServiceTokenRequestInterceptor(OAuth2AuthorizedClientManager manager, String servicePrincipal) {
        this.manager = manager;
        this.servicePrincipal = servicePrincipal;
    }
    @Override
    public void apply(RequestTemplate template) {
        OAuth2AuthorizedClient client = manager.authorize(OAuth2AuthorizeRequest
                .withClientRegistrationId("notification-service").principal(servicePrincipal).build());
        if (client == null || client.getAccessToken() == null
                || !StringUtils.hasText(client.getAccessToken().getTokenValue())) {
            throw new IllegalStateException("No OAuth service token available");
        }
        template.removeHeader("Authorization");
        template.header("Authorization", "Bearer " + client.getAccessToken().getTokenValue());
    }
}
