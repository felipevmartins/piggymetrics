package com.piggymetrics.account.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.util.Assert;
import java.net.URI;
import java.util.Collections;
import java.util.List;

@ConfigurationProperties(prefix = "security.oauth2.client")
public class ServiceClientCredentialsProperties {
    private String clientId;
    private String clientSecret;
    private String accessTokenUri;
    private String grantType;
    private List<String> scope;

    public ClientRegistration registration() {
        Assert.hasText(clientId, "OAuth service clientId is required");
        Assert.hasText(clientSecret, "OAuth service clientSecret is required");
        Assert.hasText(accessTokenUri, "OAuth service accessTokenUri is required");
        URI uri;
        try { uri = URI.create(accessTokenUri); }
        catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("OAuth service accessTokenUri must be an HTTP(S) endpoint");
        }
        Assert.isTrue(("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                && uri.getHost() != null && uri.getUserInfo() == null,
                "OAuth service accessTokenUri must be an HTTP(S) endpoint without embedded credentials");
        Assert.isTrue("client_credentials".equals(grantType), "OAuth service grantType must be client_credentials");
        Assert.isTrue(Collections.singletonList("server").equals(scope), "OAuth service scope must be server");
        return ClientRegistration.withRegistrationId("account-service")
                .clientId(clientId).clientSecret(clientSecret)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .tokenUri(accessTokenUri).scope(scope).build();
    }
    public String getClientId() { return clientId; }
    public void setClientId(String value) { clientId = value; }
    public String getClientSecret() { return clientSecret; }
    public void setClientSecret(String value) { clientSecret = value; }
    public String getAccessTokenUri() { return accessTokenUri; }
    public void setAccessTokenUri(String value) { accessTokenUri = value; }
    public String getGrantType() { return grantType; }
    public void setGrantType(String value) { grantType = value; }
    public List<String> getScope() { return scope; }
    public void setScope(List<String> value) { scope = value; }
}
