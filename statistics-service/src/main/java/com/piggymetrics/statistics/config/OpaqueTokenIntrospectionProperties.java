package com.piggymetrics.statistics.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.Assert;
import java.net.URI;

@ConfigurationProperties(prefix = "security.oauth2.resource")
public class OpaqueTokenIntrospectionProperties {
    private String introspectionUri;
    private String clientId;
    private String clientSecret;
    private int connectTimeoutMillis = 3000;
    private int readTimeoutMillis = 5000;
    private int maxResponseBytes = 65536;

    public URI validatedUri() {
        Assert.hasText(introspectionUri, "OAuth introspection URI is required");
        Assert.hasText(clientId, "OAuth introspection clientId is required");
        Assert.hasText(clientSecret, "OAuth introspection clientSecret is required");
        URI uri;
        try { uri = URI.create(introspectionUri); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("Invalid OAuth introspection endpoint"); }
        Assert.isTrue(("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                && uri.getHost() != null && uri.getUserInfo() == null
                && uri.getQuery() == null && uri.getFragment() == null, "Invalid OAuth introspection endpoint");
        Assert.isTrue(connectTimeoutMillis > 0 && readTimeoutMillis > 0, "OAuth introspection timeouts must be positive");
        Assert.isTrue(maxResponseBytes > 0 && maxResponseBytes <= 1048576, "OAuth introspection size limit is invalid");
        return uri;
    }
    public String getIntrospectionUri() { return introspectionUri; }
    public void setIntrospectionUri(String value) { introspectionUri = value; }
    public String getClientId() { return clientId; }
    public void setClientId(String value) { clientId = value; }
    public String getClientSecret() { return clientSecret; }
    public void setClientSecret(String value) { clientSecret = value; }
    public int getConnectTimeoutMillis() { return connectTimeoutMillis; }
    public void setConnectTimeoutMillis(int value) { connectTimeoutMillis = value; }
    public int getReadTimeoutMillis() { return readTimeoutMillis; }
    public void setReadTimeoutMillis(int value) { readTimeoutMillis = value; }
    public int getMaxResponseBytes() { return maxResponseBytes; }
    public void setMaxResponseBytes(int value) { maxResponseBytes = value; }
}
