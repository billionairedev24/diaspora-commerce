package com.afrotransact.identity.adapter.out.persistence;

import java.util.Optional;
import java.util.UUID;

import com.afrotransact.identity.domain.UserSettings;
import com.afrotransact.identity.domain.UserSettings.DateFormat;
import com.afrotransact.identity.domain.UserSettings.NumberFormat;
import com.afrotransact.identity.domain.UserSettings.TableDensity;
import com.afrotransact.identity.port.UserSettingsRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcUserSettingsRepository implements UserSettingsRepository {

    private final JdbcClient jdbc;

    JdbcUserSettingsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<UserSettings> find(UUID userId) {
        return jdbc.sql("select * from user_setting where user_id = :u").param("u", userId)
                .query((rs, n) -> new UserSettings(rs.getObject("user_id", UUID.class), rs.getString("job_title"),
                        DateFormat.valueOf(rs.getString("date_format")),
                        NumberFormat.valueOf(rs.getString("number_format")),
                        TableDensity.valueOf(rs.getString("table_density")), Jdbc.instant(rs, "updated_at")))
                .optional();
    }

    @Override
    public void save(UserSettings s) {
        jdbc.sql("""
                insert into user_setting (user_id, job_title, date_format, number_format, table_density, updated_at)
                values (:u, :title, :date, :number, :density, :at)
                on conflict (user_id) do update set job_title = excluded.job_title,
                    date_format = excluded.date_format, number_format = excluded.number_format,
                    table_density = excluded.table_density, updated_at = excluded.updated_at,
                    version = user_setting.version + 1
                """)
                .param("u", s.userId()).param("title", s.jobTitle()).param("date", s.dateFormat().name())
                .param("number", s.numberFormat().name()).param("density", s.tableDensity().name())
                .param("at", Jdbc.ts(s.updatedAt()))
                .update();
    }
}
