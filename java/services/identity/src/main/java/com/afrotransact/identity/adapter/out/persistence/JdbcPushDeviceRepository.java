package com.afrotransact.identity.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.identity.domain.DevicePlatform;
import com.afrotransact.identity.domain.PushApp;
import com.afrotransact.identity.domain.PushDevice;
import com.afrotransact.identity.domain.PushProvider;
import com.afrotransact.identity.port.PushDeviceRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcPushDeviceRepository implements PushDeviceRepository {

    private final JdbcClient jdbc;

    JdbcPushDeviceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public PushDevice upsert(PushDevice d) {
        jdbc.sql("""
                insert into push_device (id, user_id, app, platform, provider, token, token_hash, device_name,
                    created_at, last_seen_at)
                values (:id, :user, :app, :platform, :provider, :token, :hash, :name, :created, :seen)
                on conflict (token_hash) do update set user_id = excluded.user_id, app = excluded.app,
                    platform = excluded.platform, provider = excluded.provider,
                    device_name = coalesce(excluded.device_name, push_device.device_name),
                    created_at = case when push_device.user_id = excluded.user_id then push_device.created_at
                                      else excluded.created_at end,
                    last_seen_at = excluded.last_seen_at
                """)
                .param("id", d.id()).param("user", d.userId()).param("app", d.app().name())
                .param("platform", d.platform().name()).param("provider", d.provider().name())
                .param("token", d.token()).param("hash", d.tokenHash()).param("name", d.deviceName())
                .param("created", Jdbc.ts(d.createdAt())).param("seen", Jdbc.ts(d.lastSeenAt()))
                .update();
        return findByTokenHash(d.tokenHash()).orElseThrow();
    }

    @Override
    public Optional<PushDevice> findByTokenHash(String tokenHash) {
        return jdbc.sql("select * from push_device where token_hash = :h").param("h", tokenHash)
                .query(JdbcPushDeviceRepository::map).optional();
    }

    @Override
    public List<PushDevice> findByUser(UUID userId) {
        return jdbc.sql("select * from push_device where user_id = :u order by last_seen_at desc, id")
                .param("u", userId).query(JdbcPushDeviceRepository::map).list();
    }

    @Override
    public boolean delete(UUID userId, UUID deviceId) {
        return jdbc.sql("delete from push_device where user_id = :u and id = :id")
                .param("u", userId).param("id", deviceId).update() == 1;
    }

    @Override
    public boolean deleteByTokenHash(UUID userId, String tokenHash) {
        return jdbc.sql("delete from push_device where user_id = :u and token_hash = :h")
                .param("u", userId).param("h", tokenHash).update() == 1;
    }

    @Override
    public int trim(UUID userId, PushApp app, int keep) {
        return jdbc.sql("""
                delete from push_device where id in (
                    select id from push_device where user_id = :u and app = :app
                    order by last_seen_at desc, id offset :keep)
                """).param("u", userId).param("app", app.name()).param("keep", keep).update();
    }

    private static PushDevice map(ResultSet rs, int rowNum) throws SQLException {
        return new PushDevice(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                PushApp.valueOf(rs.getString("app")), DevicePlatform.valueOf(rs.getString("platform")),
                PushProvider.valueOf(rs.getString("provider")), rs.getString("token"), rs.getString("token_hash"),
                rs.getString("device_name"), Jdbc.instant(rs, "created_at"), Jdbc.instant(rs, "last_seen_at"));
    }
}
