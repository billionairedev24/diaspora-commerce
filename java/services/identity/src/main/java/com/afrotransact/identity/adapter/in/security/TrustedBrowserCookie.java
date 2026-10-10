package com.afrotransact.identity.adapter.in.security;

import java.time.Duration;
import java.util.Optional;

import com.afrotransact.identity.domain.MfaPolicy;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseCookie;

/** The "trust this browser" cookie (HttpOnly, 30 days); the server stores only its hash. */
public class TrustedBrowserCookie {

    public static final String NAME = "AT_TRUSTED_BROWSER";

    private final boolean secure;

    public TrustedBrowserCookie(boolean secure) {
        this.secure = secure;
    }

    public Optional<String> read(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        for (Cookie c : cookies) {
            if (NAME.equals(c.getName()) && c.getValue() != null && !c.getValue().isBlank()) {
                return Optional.of(c.getValue());
            }
        }
        return Optional.empty();
    }

    public void write(HttpServletResponse response, String token) {
        Duration ttl = MfaPolicy.TRUSTED_BROWSER_TTL;
        ResponseCookie cookie = ResponseCookie.from(NAME, token).httpOnly(true).secure(secure).sameSite("Lax")
                .path("/").maxAge(ttl).build();
        response.addHeader("Set-Cookie", cookie.toString());
    }
}
