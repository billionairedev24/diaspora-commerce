package com.afrotransact.payments.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/** S053: the database itself keeps the ledger append-only and balanced. */
class LedgerIntegrityIT extends PaymentsIntegrationTest {

    private TransactionTemplate tx;

    @BeforeEach
    void wire(@Autowired PlatformTransactionManager transactionManager) {
        this.tx = new TransactionTemplate(transactionManager);
    }

    @Test
    void journalsCannotBeUpdatedDeletedOrTruncated() {
        String order = id("ORD");
        JsonNode payment = createAuthorisedPayment(order, List.of(line("L1", "org_l", "Books", "SELF", 1, 2000, 0,
                0)));
        STRIPE.chargeFees("ch_" + order, new long[] {2000, 88});
        capture(payment.path("id").asString(), order, "S1", List.of(), 2000, 88, true);

        assertThatThrownBy(() -> jdbc.sql("update journal_line set amount_minor = amount_minor + 1 where order_id = :o")
                .param("o", order).update()).hasStackTraceContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("delete from journal_line where order_id = :o").param("o", order).update())
                .hasStackTraceContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("update journal_entry set description = 'x' where order_id = :o")
                .param("o", order).update()).hasStackTraceContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("truncate journal_line").update()).hasStackTraceContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("delete from fee_schedule").update()).hasStackTraceContaining("append-only");
        assertThat(jdbc.sql("select count(*) from journal_line where order_id = :o").param("o", order)
                .query(Long.class).single()).isGreaterThanOrEqualTo(3);
    }

    @Test
    void unbalancedJournalIsRejectedAtCommitByTheDeferredTrigger() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            insertEntry(id);
            insertLine(id, 1, "1000", "", "D", 1000);
            insertLine(id, 2, "2000", "org_x", "C", 999);
        })).hasStackTraceContaining("does not balance");
        assertThat(jdbc.sql("select count(*) from journal_entry where id = :id").param("id", id).query(Long.class)
                .single()).isZero();

        UUID single = UUID.randomUUID();
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            insertEntry(single);
            insertLine(single, 1, "1000", "", "D", 5);
        })).hasStackTraceContaining("at least two");

        UUID balanced = UUID.randomUUID();
        tx.executeWithoutResult(s -> {
            insertEntry(balanced);
            insertLine(balanced, 1, "1000", "", "D", 500);
            insertLine(balanced, 2, "2000", "org_x", "C", 500);
        });
        assertThat(jdbc.sql("select count(*) from journal_line where journal_id = :id").param("id", balanced)
                .query(Long.class).single()).isEqualTo(2);
    }

    private void insertEntry(UUID id) {
        jdbc.sql("""
                insert into journal_entry (id, journal_key, type, occurred_at, posted_at, posted_by)
                values (:id, :key, 'CAPTURE', :now, :now, 'test')
                """).param("id", id).param("key", "test:" + id).param("now", Timestamp.from(Instant.now())).update();
    }

    private void insertLine(UUID id, int no, String code, String key, String side, long amount) {
        jdbc.sql("""
                insert into journal_line (journal_id, line_no, account_code, account_key, side, amount_minor, currency)
                values (:id, :no, :code, :key, :side, :amount, 'USD')
                """).param("id", id).param("no", no).param("code", code).param("key", key).param("side", side)
                .param("amount", amount).update();
    }
}
