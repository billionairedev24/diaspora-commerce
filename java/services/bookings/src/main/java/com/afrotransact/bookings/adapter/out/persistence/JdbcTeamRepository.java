package com.afrotransact.bookings.adapter.out.persistence;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.bookings.domain.DayOverride;
import com.afrotransact.bookings.domain.TeamMember;
import com.afrotransact.bookings.domain.TeamRole;
import com.afrotransact.bookings.domain.WeeklyHours;
import com.afrotransact.bookings.port.TeamRepository;
import com.afrotransact.platform.web.error.ConflictException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcTeamRepository implements TeamRepository {

    private final JdbcClient jdbc;
    private final JsonCodec json;

    JdbcTeamRepository(JdbcClient jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public TeamMember insert(TeamMember m) {
        jdbc.sql("""
                insert into team_member (id, org_id, user_id, display_name, role, bookable, active, hours,
                                         created_at, updated_at, version)
                values (:id, :org, :user, :name, :role, :bookable, :active, cast(:hours as jsonb), :createdAt,
                        :updatedAt, 0)
                """)
                .param("id", m.id())
                .param("org", m.orgId())
                .param("user", m.userId())
                .param("name", m.displayName())
                .param("role", m.role().name())
                .param("bookable", m.bookable())
                .param("active", m.active())
                .param("hours", json.write(m.hours()))
                .param("createdAt", Sql.ts(m.createdAt()))
                .param("updatedAt", Sql.ts(m.updatedAt()))
                .update();
        return find(m.id()).orElseThrow();
    }

    @Override
    public TeamMember update(TeamMember m) {
        int rows = jdbc.sql("""
                update team_member set user_id = :user, display_name = :name, role = :role, bookable = :bookable,
                       active = :active, hours = cast(:hours as jsonb), updated_at = :updatedAt,
                       version = version + 1
                where id = :id and version = :version
                """)
                .param("user", m.userId())
                .param("name", m.displayName())
                .param("role", m.role().name())
                .param("bookable", m.bookable())
                .param("active", m.active())
                .param("hours", json.write(m.hours()))
                .param("updatedAt", Sql.ts(m.updatedAt()))
                .param("id", m.id())
                .param("version", m.version())
                .update();
        if (rows == 0) {
            throw new ConflictException("bookings.concurrent-update", "Team member changed; reload and retry");
        }
        return find(m.id()).orElseThrow();
    }

    @Override
    public Optional<TeamMember> find(UUID id) {
        return jdbc.sql("select * from team_member where id = :id").param("id", id).query(this::map).optional();
    }

    @Override
    public Optional<TeamMember> lock(UUID id) {
        return jdbc.sql("select * from team_member where id = :id for update").param("id", id).query(this::map)
                .optional();
    }

    @Override
    public List<TeamMember> byOrg(String orgId) {
        return jdbc.sql("""
                select * from team_member where org_id = :org
                order by case role when 'OWNER' then 0 when 'MANAGER' then 1 when 'PRACTITIONER' then 2 else 3 end,
                         display_name, id
                """).param("org", orgId).query(this::map).list();
    }

    @Override
    public Optional<TeamMember> byUser(String orgId, String userId) {
        return jdbc.sql("select * from team_member where org_id = :org and user_id = :user")
                .param("org", orgId).param("user", userId).query(this::map).optional();
    }

    @Override
    public List<DayOverride> overrides(UUID memberId, LocalDate from, LocalDate to) {
        return jdbc.sql("""
                select * from day_override where member_id = :m and day between :from and :to order by day
                """)
                .param("m", memberId).param("from", Date.valueOf(from)).param("to", Date.valueOf(to))
                .query((rs, n) -> new DayOverride(rs.getDate("day").toLocalDate(), rs.getBoolean("closed"),
                        json.ranges(rs.getString("ranges")), rs.getString("note")))
                .list();
    }

    @Override
    public void putOverride(UUID memberId, DayOverride o) {
        jdbc.sql("""
                insert into day_override (member_id, day, closed, ranges, note)
                values (:m, :day, :closed, cast(:ranges as jsonb), :note)
                on conflict (member_id, day) do update
                    set closed = excluded.closed, ranges = excluded.ranges, note = excluded.note
                """)
                .param("m", memberId)
                .param("day", Date.valueOf(o.date()))
                .param("closed", o.closed())
                .param("ranges", json.write(o.ranges()))
                .param("note", o.note())
                .update();
    }

    @Override
    public boolean deleteOverride(UUID memberId, LocalDate date) {
        return jdbc.sql("delete from day_override where member_id = :m and day = :day")
                .param("m", memberId).param("day", Date.valueOf(date)).update() == 1;
    }

    private TeamMember map(ResultSet rs, int rowNum) throws SQLException {
        return new TeamMember(rs.getObject("id", UUID.class), rs.getString("org_id"), rs.getString("user_id"),
                rs.getString("display_name"), TeamRole.valueOf(rs.getString("role")), rs.getBoolean("bookable"),
                rs.getBoolean("active"), json.read(rs.getString("hours"), WeeklyHours.class),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
                rs.getLong("version"));
    }
}
