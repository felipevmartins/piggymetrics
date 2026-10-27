package com.piggymetrics.statistics.service.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.piggymetrics.statistics.config.LegacyUserInfoProperties;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DefaultOAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.server.resource.introspection.BadOpaqueTokenException;
import org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;

/** Temporary adapter for the protected legacy GET userinfo response, not RFC token introspection. */
public class LegacyUserInfoOpaqueTokenIntrospector implements OpaqueTokenIntrospector {
    private final URL endpoint;
    private final int connectTimeout;
    private final int readTimeout;
    private final int maxBytes;
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public LegacyUserInfoOpaqueTokenIntrospector(LegacyUserInfoProperties properties) {
        try { endpoint = properties.validatedUri().toURL(); }
        catch (java.net.MalformedURLException invalid) { throw new IllegalArgumentException("Invalid OAuth userinfo endpoint"); }
        connectTimeout = properties.getConnectTimeoutMillis();
        readTimeout = properties.getReadTimeoutMillis();
        maxBytes = properties.getMaxResponseBytes();
    }

    @Override
    public OAuth2AuthenticatedPrincipal introspect(String token) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) endpoint.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setConnectTimeout(connectTimeout);
            connection.setReadTimeout(readTimeout);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Authorization", "Bearer " + token);
            connection.setRequestProperty("Accept", "application/json");
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                try (InputStream error = connection.getErrorStream()) { /* Never consume or expose issuer errors. */ }
                throw invalidToken();
            }
            byte[] body;
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
                byte[] chunk = new byte[1024];
                int length;
                while ((length = input.read(chunk)) != -1) {
                    if (buffer.size() + length > maxBytes) throw invalidToken();
                    buffer.write(chunk, 0, length);
                }
                body = buffer.toByteArray();
            }
            JsonNode profile = mapper.readTree(body);
            if (profile == null || !profile.isObject() || profile.has("error")) throw invalidToken();
            JsonNode request = profile.path("oauth2Request");
            if (!request.isObject()) throw invalidToken();
            String name = text(profile.get("name"));
            String clientId = text(request.get("clientId"));
            JsonNode scopes = request.get("scope");
            if (scopes == null || !scopes.isArray() || scopes.size() == 0) throw invalidToken();
            Set<String> scopeValues = new LinkedHashSet<>();
            List<GrantedAuthority> authorities = new ArrayList<>();
            for (JsonNode scope : scopes) {
                String value = text(scope);
                if (value.chars().anyMatch(c -> c <= 32 || c >= 127 || c == 34 || c == 92)) throw invalidToken();
                if (!scopeValues.add(value)) continue;
                authorities.add(new SimpleGrantedAuthority("SCOPE_" + value));
            }
            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put("name", name);
            attributes.put("client_id", clientId);
            attributes.put("scope", new ArrayList<>(scopeValues));
            return new DefaultOAuth2AuthenticatedPrincipal(name, attributes, authorities);
        } catch (IOException | IllegalArgumentException failure) {
            // Do not include the token, response body, endpoint or underlying exception.
            throw invalidToken();
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private String text(JsonNode value) {
        if (value == null || !value.isTextual() || value.asText().trim().isEmpty()) throw invalidToken();
        return value.asText();
    }

    private BadOpaqueTokenException invalidToken() {
        return new BadOpaqueTokenException("Token could not be validated");
    }
}
