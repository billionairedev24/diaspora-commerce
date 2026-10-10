package com.afrotransact.seller.adapter.out.identity;

import com.afrotransact.seller.port.CallerProfile;
import com.afrotransact.seller.port.CallerProfileProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/** Reads OIDC profile claims (given_name, family_name, name, email, phone_number) from the caller's access token. */
@Component
class JwtCallerProfileProvider implements CallerProfileProvider {

    @Override
    public CallerProfile current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof JwtAuthenticationToken token)) {
            return CallerProfile.empty();
        }
        Jwt jwt = token.getToken();
        return new CallerProfile(jwt.getClaimAsString("given_name"), jwt.getClaimAsString("family_name"),
                jwt.getClaimAsString("name"), jwt.getClaimAsString("email"), jwt.getClaimAsString("phone_number"));
    }
}
