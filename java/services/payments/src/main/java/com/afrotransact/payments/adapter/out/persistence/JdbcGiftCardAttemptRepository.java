package com.afrotransact.payments.adapter.out.persistence;

import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;

import com.afrotransact.payments.port.GiftCardAttemptRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
class JdbcGiftCardAttemptRepository implements GiftCardAttemptRepository {

    private final JdbcClient jdbc;

    JdbcGiftCardAttemptRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Its own committed transaction: the attempt counts even when the redeem it belongs to fails. */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public OptionalLong reserve(String subject, Instant since, int max, Instant at) {
        jdbc.sql("select count(*) from (select pg_advisory_xact_lock(hashtext(:s))) locked")
                .param("s", "gift-card-attempt:" + subject).query(Long.class).single();
        long failures = jdbc.sql("""
                select count(*) from gift_card_attempt where subject = :s and not success and made_at >= :since
                """)
                .param("s", subject).param("since", Sql.ts(since)).query(Long.class).single();
        if (failures >= max) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(jdbc.sql("""
                insert into gift_card_attempt (subject, success, made_at) values (:s, false, :at) returning id
                """)
                .param("s", subject).param("at", Sql.ts(at)).query(Long.class).single());
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void succeed(long attemptId) {
        jdbc.sql("update gift_card_attempt set success = true where id = :id").param("id", attemptId).update();
    }

    @Override
    public Optional<Instant> oldestFailureSince(String subject, Instant since) {
        return jdbc.sql("""
                select made_at from gift_card_attempt where subject = :s and not success and made_at >= :since
                order by made_at limit 1
                """)
                .param("s", subject).param("since", Sql.ts(since))
                .query((rs, n) -> Sql.instant(rs, "made_at")).optional();
    }
}
