package com.afrotransact.identity.adapter.out.persistence;

import java.time.Instant;

import com.afrotransact.identity.domain.AuthSettingsOverrides;
import com.afrotransact.identity.port.AuthSettingsStore;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcAuthSettingsStore implements AuthSettingsStore {

    private final JdbcClient jdbc;

    JdbcAuthSettingsStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public AuthSettingsOverrides load() {
        return jdbc.sql("select * from auth_settings where id = 1")
                .query((rs, n) -> new AuthSettingsOverrides(
                        (Integer) rs.getObject("password_min_length"),
                        (Boolean) rs.getObject("mfa_required_account"),
                        (Boolean) rs.getObject("mfa_required_seller"),
                        (Boolean) rs.getObject("mfa_required_provider"),
                        (Integer) rs.getObject("session_idle_timeout_minutes"),
                        (Integer) rs.getObject("session_absolute_timeout_minutes"),
                        (Integer) rs.getObject("access_token_ttl_minutes"),
                        (Integer) rs.getObject("refresh_token_ttl_minutes")))
                .optional().orElseGet(AuthSettingsOverrides::none);
    }

    @Override
    public void save(AuthSettingsOverrides o, Instant at, String by) {
        jdbc.sql("""
                insert into auth_settings (id, password_min_length, mfa_required_account, mfa_required_seller,
                    mfa_required_provider, session_idle_timeout_minutes, session_absolute_timeout_minutes,
                    access_token_ttl_minutes, refresh_token_ttl_minutes, updated_at, updated_by)
                values (1, :pw, :mfaAccount, :mfaSeller, :mfaProvider, :idle, :absolute, :access, :refresh, :at, :by)
                on conflict (id) do update set password_min_length = :pw, mfa_required_account = :mfaAccount,
                    mfa_required_seller = :mfaSeller, mfa_required_provider = :mfaProvider,
                    session_idle_timeout_minutes = :idle, session_absolute_timeout_minutes = :absolute,
                    access_token_ttl_minutes = :access, refresh_token_ttl_minutes = :refresh,
                    updated_at = :at, updated_by = :by, version = auth_settings.version + 1
                """)
                .param("pw", o.passwordMinLength())
                .param("mfaAccount", o.mfaRequiredAccount())
                .param("mfaSeller", o.mfaRequiredSeller())
                .param("mfaProvider", o.mfaRequiredProvider())
                .param("idle", o.sessionIdleTimeoutMinutes())
                .param("absolute", o.sessionAbsoluteTimeoutMinutes())
                .param("access", o.accessTokenTtlCapMinutes())
                .param("refresh", o.refreshTokenTtlCapMinutes())
                .param("at", Jdbc.ts(at))
                .param("by", by)
                .update();
    }
}
