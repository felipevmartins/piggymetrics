package com.piggymetrics.notification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.Assert;
import java.net.URI;

@ConfigurationProperties(prefix = "security.oauth2.resource")
public class LegacyUserInfoProperties {
    private String userInfoUri;
    private int connectTimeoutMillis = 3000;
    private int readTimeoutMillis = 5000;
    private int maxResponseBytes = 65536;

    public URI validatedUri() {
        Assert.hasText(userInfoUri, "OAuth userinfo URI is required");
        URI uri;
        try { uri = URI.create(userInfoUri); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("Invalid OAuth userinfo endpoint"); }
        Assert.isTrue(("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                && uri.getHost() != null && uri.getUserInfo() == null
                && uri.getQuery() == null && uri.getFragment() == null, "Invalid OAuth userinfo endpoint");
        Assert.isTrue(connectTimeoutMillis > 0 && readTimeoutMillis > 0, "OAuth userinfo timeouts must be positive");
        Assert.isTrue(maxResponseBytes > 0 && maxResponseBytes <= 1048576, "OAuth userinfo size limit is invalid");
        return uri;
    }
    public String getUserInfoUri() { return userInfoUri; }
    public void setUserInfoUri(String value) { userInfoUri = value; }
    public int getConnectTimeoutMillis() { return connectTimeoutMillis; }
    public void setConnectTimeoutMillis(int value) { connectTimeoutMillis = value; }
    public int getReadTimeoutMillis() { return readTimeoutMillis; }
    public void setReadTimeoutMillis(int value) { readTimeoutMillis = value; }
    public int getMaxResponseBytes() { return maxResponseBytes; }
    public void setMaxResponseBytes(int value) { maxResponseBytes = value; }
}
