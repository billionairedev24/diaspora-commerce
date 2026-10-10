package com.afrotransact.fulfilment.adapter.in.web;

import com.afrotransact.fulfilment.application.DriverCredential;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/** The driver app's token of this request: relayed to orders for courier events, iat for sign-out decisions. */
@Component
class Credentials {

    DriverCredential current(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwt) {
            return new DriverCredential(jwt.getToken().getTokenValue(), jwt.getToken().getIssuedAt());
        }
        String header = request.getHeader("Authorization");
        String bearer = header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)
                ? header.substring(7).trim() : null;
        return new DriverCredential(bearer, null);
    }
}
