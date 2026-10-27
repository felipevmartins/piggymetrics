package com.piggymetrics.statistics.config;

import com.piggymetrics.statistics.service.security.LegacyUserInfoOpaqueTokenIntrospector;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.server.resource.BearerTokenErrors;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import java.util.Collections;
import org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector;

@Configuration
@EnableConfigurationProperties(LegacyUserInfoProperties.class)
public class ResourceServerConfig extends WebSecurityConfigurerAdapter {
    private final LegacyUserInfoProperties properties;

    public ResourceServerConfig(LegacyUserInfoProperties properties) { this.properties = properties; }

    @Bean
    public OpaqueTokenIntrospector opaqueTokenIntrospector() {
        return new LegacyUserInfoOpaqueTokenIntrospector(properties);
    }

    @Override
    protected void configure(HttpSecurity http) throws Exception {
        http.csrf().disable()
                .sessionManagement().sessionCreationPolicy(SessionCreationPolicy.STATELESS).and()
                .authorizeRequests().anyRequest().authenticated().and()
                .oauth2ResourceServer().bearerTokenResolver(request -> {
                    if (Collections.list(request.getHeaders("Authorization")).size() > 1) {
                        throw new OAuth2AuthenticationException(BearerTokenErrors.invalidRequest(
                                "Multiple Authorization headers are not accepted"));
                    }
                    return new DefaultBearerTokenResolver().resolve(request);
                }).opaqueToken().introspector(opaqueTokenIntrospector());
    }
}
