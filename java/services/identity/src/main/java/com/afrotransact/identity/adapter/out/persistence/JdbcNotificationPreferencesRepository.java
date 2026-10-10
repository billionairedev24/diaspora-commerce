package com.afrotransact.identity.adapter.out.persistence;

import java.util.Optional;
import java.util.UUID;

import com.afrotransact.identity.domain.NotificationPreferences;
import com.afrotransact.identity.port.NotificationPreferencesRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcNotificationPreferencesRepository implements NotificationPreferencesRepository {

    private final JdbcClient jdbc;

    JdbcNotificationPreferencesRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<NotificationPreferences> find(UUID userId) {
        return jdbc.sql("select * from notification_preference where user_id = :u").param("u", userId)
                .query((rs, n) -> new NotificationPreferences(rs.getObject("user_id", UUID.class),
                        rs.getBoolean("email_enabled"), rs.getBoolean("sms_enabled"), rs.getBoolean("push_enabled"),
                        rs.getBoolean("marketing_opt_in"), Jdbc.instant(rs, "marketing_opt_in_at"),
                        rs.getString("locale"), rs.getString("time_zone"), Jdbc.instant(rs, "updated_at")))
                .optional();
    }

    @Override
    public void save(NotificationPreferences p) {
        jdbc.sql("""
                insert into notification_preference (user_id, email_enabled, sms_enabled, push_enabled,
                    marketing_opt_in, marketing_opt_in_at, locale, time_zone, updated_at)
                values (:u, :email, :sms, :push, :marketing, :marketingAt, :locale, :tz, :at)
                on conflict (user_id) do update set email_enabled = excluded.email_enabled,
                    sms_enabled = excluded.sms_enabled, push_enabled = excluded.push_enabled,
                    marketing_opt_in = excluded.marketing_opt_in, marketing_opt_in_at = excluded.marketing_opt_in_at,
                    locale = excluded.locale, time_zone = excluded.time_zone, updated_at = excluded.updated_at,
                    version = notification_preference.version + 1
                """)
                .param("u", p.userId()).param("email", p.email()).param("sms", p.sms()).param("push", p.push())
                .param("marketing", p.marketingOptIn()).param("marketingAt", Jdbc.ts(p.marketingOptInAt()))
                .param("locale", p.locale()).param("tz", p.timeZone()).param("at", Jdbc.ts(p.updatedAt()))
                .update();
    }
}
