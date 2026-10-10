package com.afrotransact.identity.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.identity.domain.UserAccount;
import com.afrotransact.identity.port.UserAccountRepository;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcUserAccountRepository implements UserAccountRepository {

    private final JdbcClient jdbc;

    JdbcUserAccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public UserAccount insert(UserAccount a) {
        jdbc.sql("""
                insert into user_account (id, first_name, last_name, email, email_verified, phone_e164, password_hash,
                    enabled, sms_consent, sms_consent_at, terms_accepted_at, active_org_id, created_at, updated_at, version)
                values (:id, :first, :last, :email, :verified, :phone, :hash, :enabled, :sms, :smsAt, :terms, :org,
                    :createdAt, :updatedAt, 0)
                """)
                .param("id", a.id())
                .param("first", a.firstName())
                .param("last", a.lastName())
                .param("email", a.email())
                .param("verified", a.emailVerified())
                .param("phone", a.phone())
                .param("hash", a.passwordHash())
                .param("enabled", a.enabled())
                .param("sms", a.smsConsent())
                .param("smsAt", a.smsConsent() ? Jdbc.ts(a.createdAt()) : null)
                .param("terms", Jdbc.ts(a.termsAcceptedAt()))
                .param("org", a.activeOrgId())
                .param("createdAt", Jdbc.ts(a.createdAt()))
                .param("updatedAt", Jdbc.ts(a.updatedAt()))
                .update();
        return findById(a.id()).orElseThrow();
    }

    @Override
    public UserAccount update(UserAccount a) {
        int rows = jdbc.sql("""
                update user_account set first_name = :first, last_name = :last, email_verified = :verified,
                    phone_e164 = :phone, password_hash = :hash, enabled = :enabled,
                    sms_consent_at = case when cast(:sms as boolean) and not sms_consent then cast(:updatedAt as timestamptz)
                                          when cast(:sms as boolean) then sms_consent_at end,
                    sms_consent = :sms, terms_accepted_at = :terms, active_org_id = :org, updated_at = :updatedAt,
                    version = version + 1
                where id = :id and version = :version
                """)
                .param("id", a.id())
                .param("first", a.firstName())
                .param("last", a.lastName())
                .param("verified", a.emailVerified())
                .param("phone", a.phone())
                .param("hash", a.passwordHash())
                .param("enabled", a.enabled())
                .param("sms", a.smsConsent())
                .param("terms", Jdbc.ts(a.termsAcceptedAt()))
                .param("org", a.activeOrgId())
                .param("updatedAt", Jdbc.ts(a.updatedAt()))
                .param("version", a.version())
                .update();
        if (rows == 0) {
            throw new OptimisticLockingFailureException("Account " + a.id() + " was changed concurrently");
        }
        return findById(a.id()).orElseThrow();
    }

    @Override
    public Optional<UserAccount> findById(UUID id) {
        return jdbc.sql("select * from user_account where id = :id").param("id", id)
                .query(JdbcUserAccountRepository::map).optional();
    }

    @Override
    public Optional<UserAccount> findByEmail(String email) {
        if (email == null) {
            return Optional.empty();
        }
        return jdbc.sql("select * from user_account where lower(email) = lower(:email)").param("email", email)
                .query(JdbcUserAccountRepository::map).optional();
    }

    @Override
    public List<UserAccount> search(String query, int limit, int offset) {
        return jdbc.sql("""
                select * from user_account
                where :q = '' or lower(email) like :like or lower(first_name || ' ' || last_name) like :like
                order by lower(last_name), lower(first_name), id
                limit :limit offset :offset
                """)
                .param("q", query)
                .param("like", "%" + query.toLowerCase(Locale.ROOT) + "%")
                .param("limit", limit)
                .param("offset", offset)
                .query(JdbcUserAccountRepository::map).list();
    }

    @Override
    public long count(String query) {
        return jdbc.sql("""
                select count(*) from user_account
                where :q = '' or lower(email) like :like or lower(first_name || ' ' || last_name) like :like
                """)
                .param("q", query)
                .param("like", "%" + query.toLowerCase(Locale.ROOT) + "%")
                .query(Long.class).single();
    }

    private static UserAccount map(ResultSet rs, int n) throws SQLException {
        return new UserAccount(rs.getObject("id", UUID.class), rs.getString("first_name"), rs.getString("last_name"),
                rs.getString("email"), rs.getBoolean("email_verified"), rs.getString("phone_e164"),
                rs.getString("password_hash"), rs.getBoolean("enabled"), rs.getBoolean("sms_consent"),
                Jdbc.instant(rs, "terms_accepted_at"), rs.getString("active_org_id"),
                Jdbc.instant(rs, "created_at"), Jdbc.instant(rs, "updated_at"), rs.getInt("version"));
    }
}
