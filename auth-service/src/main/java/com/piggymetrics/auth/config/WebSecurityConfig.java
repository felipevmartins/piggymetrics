package com.piggymetrics.auth.config;

import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;

/**
 * @author cdov
 */
@Configuration
public class WebSecurityConfig {

    /** Stored user passwords are plain BCrypt hashes (no {id} prefix). */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /** OAuth2 protocol endpoints (/oauth2/authorize, /oauth2/token, /oauth2/introspect, /oauth2/revoke). */
    @Bean
    @Order(1)
    public SecurityFilterChain authorizationServerFilterChain(HttpSecurity http) throws Exception {
        OAuth2AuthorizationServerConfigurer authorizationServer = new OAuth2AuthorizationServerConfigurer();
        http.securityMatcher(authorizationServer.getEndpointsMatcher())
                .with(authorizationServer, Customizer.withDefaults())
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .exceptionHandling(exceptions -> exceptions.defaultAuthenticationEntryPointFor(
                        new LoginUrlAuthenticationEntryPoint("/login"),
                        new MediaTypeRequestMatcher(MediaType.TEXT_HTML)));
        return http.build();
    }

    /** User management API, protected by bearer tokens issued by this server. */
    @Bean
    @Order(2)
    public SecurityFilterChain usersFilterChain(HttpSecurity http, OpaqueTokenIntrospector introspector) throws Exception {
        http.securityMatcher("/users/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.opaqueToken(opaque -> opaque.introspector(introspector)));
        return http.build();
    }

    /** Interactive login (form) used by the authorization code flow. */
    @Bean
    @Order(3)
    public SecurityFilterChain loginFilterChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(auth -> auth
                        .requestMatchers(EndpointRequest.to("prometheus")).permitAll()
                        .anyRequest().authenticated())
                .formLogin(Customizer.withDefaults());
        return http.build();
    }
}
