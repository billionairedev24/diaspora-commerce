package com.afrotransact.seller.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.seller.domain.ComplianceHistoryEntry;
import com.afrotransact.seller.domain.ComplianceStatus;
import com.afrotransact.seller.domain.ConnectAccount;
import com.afrotransact.seller.domain.PayoutBank;
import com.afrotransact.seller.domain.PayoutHold;
import com.afrotransact.seller.domain.RequirementError;
import com.afrotransact.seller.domain.RequirementsSnapshot;
import com.afrotransact.seller.port.ConnectAccountRepository;
import com.afrotransact.platform.web.error.ConflictException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;

@Repository
class JdbcConnectAccountRepository implements ConnectAccountRepository {

    private static final TypeReference<List<RequirementError>> ERRORS = new TypeReference<>() { };

    private final JdbcClient jdbc;
    private final JsonCodec json;

    JdbcConnectAccountRepository(JdbcClient jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public boolean insert(ConnectAccount a) {
        return jdbc.sql("""
                insert into connect_account (account_id, org_id, status, updated_at, version)
                values (:id, :org, :status, :at, 0)
                on conflict (account_id) do nothing
                """)
                .param("id", a.accountId()).param("org", a.orgId()).param("status", a.status().name())
                .param("at", Sql.ts(a.updatedAt()))
                .update() == 1;
    }

    @Override
    public ConnectAccount update(ConnectAccount a) {
        RequirementsSnapshot r = a.requirements();
        int rows = jdbc.sql("""
                update connect_account set currently_due = cast(:cur as jsonb), eventually_due = cast(:ev as jsonb),
                       past_due = cast(:past as jsonb), pending_verification = cast(:pend as jsonb),
                       errors = cast(:errors as jsonb), current_deadline = :deadline, disabled_reason = :disabled,
                       charges_enabled = :charges, payouts_enabled = :payouts, details_submitted = :details,
                       individual_verification = :individual, capabilities = cast(:caps as jsonb),
                       persons = cast(:persons as jsonb), external_account_status = :bank,
                       payout_bank = cast(:payoutBank as jsonb), status = :status,
                       requirements_version = :rv, payouts_paused = :paused, hold_reason = :hold,
                       listings_hidden = :hidden, deauthorized = :deauth, reminders_sent = :reminders,
                       last_reminder_at = :lastReminder, updated_at = :at, version = version + 1
                where account_id = :id and version = :version
                """)
                .param("cur", json.write(r.currentlyDue())).param("ev", json.write(r.eventuallyDue()))
                .param("past", json.write(r.pastDue())).param("pend", json.write(r.pendingVerification()))
                .param("errors", json.write(r.errors())).param("deadline", Sql.ts(r.currentDeadline()))
                .param("disabled", r.disabledReason()).param("charges", r.chargesEnabled())
                .param("payouts", r.payoutsEnabled()).param("details", r.detailsSubmitted())
                .param("individual", r.individualVerificationStatus()).param("caps", json.write(r.capabilities()))
                .param("persons", json.write(a.persons())).param("bank", a.externalAccountStatus())
                .param("payoutBank", r.payoutBank() == null ? null : json.write(r.payoutBank()))
                .param("status", a.status().name()).param("rv", a.requirementsVersion())
                .param("paused", a.payoutsPaused()).param("hold", a.holdReason() == null ? null : a.holdReason().name())
                .param("hidden", a.listingsHidden()).param("deauth", a.deauthorized())
                .param("reminders", a.remindersSent()).param("lastReminder", Sql.ts(a.lastReminderAt()))
                .param("at", Sql.ts(a.updatedAt())).param("id", a.accountId()).param("version", a.version())
                .update();
        if (rows == 0) {
            throw new ConflictException("seller.concurrent-update", "Connected account changed; retry");
        }
        return find(a.accountId()).orElseThrow();
    }

    @Override
    public Optional<ConnectAccount> find(String accountId) {
        return jdbc.sql("select * from connect_account where account_id = :id").param("id", accountId)
                .query(this::map).optional();
    }

    @Override
    public Optional<ConnectAccount> lock(String accountId) {
        return jdbc.sql("select * from connect_account where account_id = :id for update").param("id", accountId)
                .query(this::map).optional();
    }

    @Override
    public Optional<ConnectAccount> findByOrg(String orgId) {
        return jdbc.sql("select * from connect_account where org_id = :o").param("o", orgId).query(this::map)
                .optional();
    }

    @Override
    public List<ConnectAccount> search(ComplianceStatus status, boolean openOnly, int limit) {
        StringBuilder where = new StringBuilder(" where true");
        if (status != null) {
            where.append(" and status = :status");
        }
        if (openOnly) {
            where.append(" and (jsonb_array_length(currently_due) > 0 or jsonb_array_length(past_due) > 0)");
        }
        JdbcClient.StatementSpec spec = jdbc.sql("select * from connect_account" + where
                + " order by current_deadline nulls last, updated_at desc limit :limit").param("limit", limit);
        if (status != null) {
            spec = spec.param("status", status.name());
        }
        return spec.query(this::map).list();
    }

    @Override
    public List<ConnectAccount> connectedAfter(String afterAccountId, int limit) {
        return jdbc.sql("""
                select * from connect_account
                where org_id is not null and not deauthorized and account_id > :after
                order by account_id limit :limit
                """)
                .param("after", afterAccountId).param("limit", limit).query(this::map).list();
    }

    @Override
    public List<ConnectAccount> withDeadlineBefore(Instant before, int limit) {
        return jdbc.sql("""
                select * from connect_account
                where current_deadline is not null and current_deadline <= :before
                  and (jsonb_array_length(currently_due) > 0 or jsonb_array_length(past_due) > 0)
                order by current_deadline limit :limit
                """)
                .param("before", Sql.ts(before)).param("limit", limit).query(this::map).list();
    }

    @Override
    public void appendHistory(ComplianceHistoryEntry h) {
        jdbc.sql("""
                insert into connect_account_history (id, account_id, source, event_id, event_type, status, summary,
                                                     snapshot, actor_id, occurred_at)
                values (:id, :acct, :source, :event, :type, :status, :summary, cast(:snapshot as jsonb), :actor, :at)
                """)
                .param("id", h.id()).param("acct", h.accountId()).param("source", h.source())
                .param("event", h.eventId()).param("type", h.eventType()).param("status", h.status().name())
                .param("summary", h.summary()).param("snapshot", json.write(h.snapshot()))
                .param("actor", h.actorId()).param("at", Sql.ts(h.occurredAt()))
                .update();
    }

    @Override
    public List<ComplianceHistoryEntry> history(String accountId, int limit) {
        return jdbc.sql("""
                select * from connect_account_history where account_id = :a order by occurred_at desc limit :limit
                """)
                .param("a", accountId).param("limit", limit)
                .query((rs, n) -> new ComplianceHistoryEntry(rs.getObject("id", UUID.class),
                        rs.getString("account_id"), rs.getString("source"), rs.getString("event_id"),
                        rs.getString("event_type"), ComplianceStatus.valueOf(rs.getString("status")),
                        rs.getString("summary"), json.read(rs.getString("snapshot"), RequirementsSnapshot.class),
                        rs.getString("actor_id"), Sql.instant(rs, "occurred_at")))
                .list();
    }

    @Override
    public boolean claimReminder(String accountId, Instant deadline, int thresholdDays, Instant now) {
        return jdbc.sql("""
                insert into compliance_reminder (id, account_id, deadline, threshold_days, manual, sent_at)
                values (:id, :acct, :deadline, :threshold, false, :now)
                on conflict (account_id, deadline, threshold_days) where manual = false do nothing
                """)
                .param("id", UUID.randomUUID()).param("acct", accountId).param("deadline", Sql.ts(deadline))
                .param("threshold", thresholdDays).param("now", Sql.ts(now))
                .update() == 1;
    }

    @Override
    public void recordManualReminder(String accountId, String actorId, Instant now) {
        jdbc.sql("""
                insert into compliance_reminder (id, account_id, manual, actor_id, sent_at)
                values (:id, :acct, true, :actor, :now)
                """)
                .param("id", UUID.randomUUID()).param("acct", accountId).param("actor", actorId)
                .param("now", Sql.ts(now)).update();
    }

    private PayoutBank payoutBank(String column) {
        return column == null ? null : json.read(column, PayoutBank.class);
    }

    private ConnectAccount map(ResultSet rs, int n) throws SQLException {
        RequirementsSnapshot r = new RequirementsSnapshot(json.strings(rs.getString("currently_due")),
                json.strings(rs.getString("eventually_due")), json.strings(rs.getString("past_due")),
                json.strings(rs.getString("pending_verification")), json.read(rs.getString("errors"), ERRORS),
                Sql.instant(rs, "current_deadline"), rs.getString("disabled_reason"),
                rs.getBoolean("charges_enabled"), rs.getBoolean("payouts_enabled"), rs.getBoolean("details_submitted"),
                rs.getString("individual_verification"), json.stringMap(rs.getString("capabilities")),
                payoutBank(rs.getString("payout_bank")));
        String hold = rs.getString("hold_reason");
        return new ConnectAccount(rs.getString("account_id"), rs.getString("org_id"), r,
                ComplianceStatus.valueOf(rs.getString("status")), rs.getLong("requirements_version"),
                rs.getBoolean("payouts_paused"), hold == null ? null : PayoutHold.valueOf(hold),
                rs.getBoolean("listings_hidden"), rs.getBoolean("deauthorized"), rs.getInt("reminders_sent"),
                Sql.instant(rs, "last_reminder_at"), json.stringMap(rs.getString("persons")),
                rs.getString("external_account_status"), Sql.instant(rs, "updated_at"), rs.getLong("version"));
    }
}
