package com.piggymetrics.auth.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Login page of the authorization server, styled like the PiggyMetrics UI. It is plain HTML with the CSRF token
 * filled in, so no template engine is needed.
 */
@RestController
public class LoginController {

    private final String template;

    public LoginController() {
        try {
            this.template = new ClassPathResource("templates/login.html").getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @GetMapping(value = "/login", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> login(HttpServletRequest request) {
        CsrfToken csrf = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        String message = request.getParameter("error") != null
                ? "<p class=\"message\">Invalid login or password.</p>"
                : "";
        String page = template
                .replace("{{action}}", HtmlUtils.htmlEscape(request.getContextPath() + "/login"))
                .replace("{{message}}", message)
                .replace("{{csrfName}}", csrf == null ? "_csrf" : HtmlUtils.htmlEscape(csrf.getParameterName()))
                .replace("{{csrfToken}}", csrf == null ? "" : HtmlUtils.htmlEscape(csrf.getToken()));
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(page);
    }
}
