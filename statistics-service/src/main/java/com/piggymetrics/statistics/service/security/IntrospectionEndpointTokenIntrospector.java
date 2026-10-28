package com.piggymetrics.statistics.service.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.piggymetrics.statistics.config.OpaqueTokenIntrospectionProperties;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DefaultOAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.server.resource.introspection.BadOpaqueTokenException;
import org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** RFC 7662 introspection against auth-service, authenticating as this service's own OAuth client. */
public class IntrospectionEndpointTokenIntrospector implements OpaqueTokenIntrospector {
    private final URL endpoint;
    private final String clientAuthorization;
    private final int connectTimeout;
    private final int readTimeout;
    private final int maxBytes;
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public IntrospectionEndpointTokenIntrospector(OpaqueTokenIntrospectionProperties properties) {
        try { endpoint = properties.validatedUri().toURL(); }
        catch (java.net.MalformedURLException invalid) { throw new IllegalArgumentException("Invalid OAuth introspection endpoint"); }
        clientAuthorization = "Basic " + Base64.getEncoder().encodeToString(
                (properties.getClientId() + ":" + properties.getClientSecret()).getBytes(StandardCharsets.UTF_8));
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
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Authorization", clientAuthorization);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            byte[] form = ("token=" + URLEncoder.encode(token, "UTF-8") + "&token_type_hint=access_token")
                    .getBytes(StandardCharsets.UTF_8);
            try (OutputStream output = connection.getOutputStream()) { output.write(form); }
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
            JsonNode active = profile.get("active");
            if (active == null || !active.isBoolean() || !active.asBoolean()) throw invalidToken();
            String name = text(profile.get("sub"));
            String clientId = text(profile.get("client_id"));
            String[] scopes = text(profile.get("scope")).split(" ");
            Set<String> scopeValues = new LinkedHashSet<>();
            List<GrantedAuthority> authorities = new ArrayList<>();
            for (String value : scopes) {
                if (value.isEmpty() || value.chars().anyMatch(c -> c <= 32 || c >= 127 || c == 34 || c == 92)) throw invalidToken();
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
