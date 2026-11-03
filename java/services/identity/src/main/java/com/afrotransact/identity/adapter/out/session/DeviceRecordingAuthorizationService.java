package com.afrotransact.identity.adapter.out.session;

import com.afrotransact.identity.domain.SessionDevice;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Records which browser or app each sign-in (authorization) came from, the first time it is saved (S291): the device
 * the first-party login ticket carried when the BFF redeemed one, otherwise the User-Agent and address of the request
 * itself (hosted sign-in page, native app). Later saves (refresh-token rotation, issued by the BFF on the user's
 * behalf) keep the recorded device. Everything else is the delegate's.
 */
public final class DeviceRecordingAuthorizationService implements OAuth2AuthorizationService {

    private final OAuth2AuthorizationService delegate;
    private final JdbcClient jdbc;

    public DeviceRecordingAuthorizationService(OAuth2AuthorizationService delegate, JdbcClient jdbc) {
        this.delegate = delegate;
        this.jdbc = jdbc;
    }

    @Override
    public void save(OAuth2Authorization authorization) {
        delegate.save(authorization);
        SessionDevice device = currentDevice();
        if (device != null) {
            jdbc.sql("""
                    insert into session_device (authorization_id, user_agent, ip_hash)
                    values (:id, :userAgent, :ipHash)
                    on conflict (authorization_id) do nothing
                    """)
                    .param("id", authorization.getId()).param("userAgent", device.userAgent())
                    .param("ipHash", device.ipHash()).update();
        }
    }

    @Override
    public void remove(OAuth2Authorization authorization) {
        delegate.remove(authorization);
    }

    @Override
    public OAuth2Authorization findById(String id) {
        return delegate.findById(id);
    }

    @Override
    public OAuth2Authorization findByToken(String token, OAuth2TokenType tokenType) {
        return delegate.findByToken(token, tokenType);
    }

    private static SessionDevice currentDevice() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof ServletRequestAttributes servlet)) {
            return null;
        }
        HttpServletRequest request = servlet.getRequest();
        if (request.getAttribute(SessionDevice.REQUEST_ATTRIBUTE) instanceof SessionDevice fromTicket) {
            return fromTicket;
        }
        return SessionDevice.of(request.getHeader("User-Agent"), request.getRemoteAddr());
    }
}
