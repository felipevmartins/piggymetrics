package com.piggymetrics.statistics.config;

import com.piggymetrics.statistics.service.security.IntrospectionEndpointTokenIntrospector;
import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.server.resource.BearerTokenErrors;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import java.util.Collections;
import org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector;

@Configuration
@EnableConfigurationProperties(OpaqueTokenIntrospectionProperties.class)
public class ResourceServerConfig {
    private final OpaqueTokenIntrospectionProperties properties;

    public ResourceServerConfig(OpaqueTokenIntrospectionProperties properties) { this.properties = properties; }

    @Bean
    public OpaqueTokenIntrospector opaqueTokenIntrospector() {
        return new IntrospectionEndpointTokenIntrospector(properties);
    }

    @Bean
    public SecurityFilterChain resourceServerFilterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(EndpointRequest.to("prometheus")).permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .bearerTokenResolver(request -> {
                            if (Collections.list(request.getHeaders("Authorization")).size() > 1) {
                                throw new OAuth2AuthenticationException(BearerTokenErrors.invalidRequest(
                                        "Multiple Authorization headers are not accepted"));
                            }
                            // Spring Security 6 ignores access_token when the query parameter is not enabled;
                            // the legacy contract rejects the ambiguous request, so keep rejecting it.
                            if (request.getHeader("Authorization") != null && request.getParameter("access_token") != null) {
                                throw new OAuth2AuthenticationException(BearerTokenErrors.invalidRequest(
                                        "Found multiple bearer tokens in the request"));
                            }
                            return new DefaultBearerTokenResolver().resolve(request);
                        })
                        .opaqueToken(opaque -> opaque.introspector(opaqueTokenIntrospector())));
        return http.build();
    }
}
