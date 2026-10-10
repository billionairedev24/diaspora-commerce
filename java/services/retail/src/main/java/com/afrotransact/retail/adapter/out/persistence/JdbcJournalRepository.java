package com.afrotransact.retail.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.retail.domain.JournalEntry;
import com.afrotransact.retail.domain.JournalLine;
import com.afrotransact.retail.domain.JournalSource;
import com.afrotransact.retail.port.JournalRepository;
import com.afrotransact.retail.port.Page;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;

@Repository
class JdbcJournalRepository implements JournalRepository {

    private static final TypeReference<List<JournalLine>> LINES = new TypeReference<>() { };

    private final JdbcClient jdbc;
    private final JsonCodec json;

    JdbcJournalRepository(JdbcClient jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public JournalEntry insert(JournalEntry e) {
        jdbc.sql("""
                insert into gl_journal_entry (id, number, entry_date, source, source_ref, memo, currency, lines,
                                              amount_minor, posted_by, posted_at)
                values (:id, :number, :date, :source, :ref, :memo, :currency, cast(:lines as jsonb), :amount, :by, :at)
                """)
                .param("id", e.id()).param("number", e.number()).param("date", e.entryDate())
                .param("source", e.source().name()).param("ref", e.sourceRef()).param("memo", e.memo())
                .param("currency", e.currency()).param("lines", json.write(e.lines()))
                .param("amount", e.amountMinor()).param("by", e.postedBy()).param("at", Sql.ts(e.postedAt()))
                .update();
        return e;
    }

    @Override
    public Optional<JournalEntry> findById(UUID id) {
        return jdbc.sql("select * from gl_journal_entry where id = :id").param("id", id).query(this::map).optional();
    }

    @Override
    public List<JournalEntry> findBySourceRef(String sourceRef) {
        return jdbc.sql("select * from gl_journal_entry where source_ref = :ref order by posted_at")
                .param("ref", sourceRef).query(this::map).list();
    }

    @Override
    public Page<JournalEntry> page(JournalSource source, String sourceRef, int page, int size) {
        Sql.Where where = new Sql.Where().eq("source", "source", Sql.name(source)).eq("source_ref", "ref", sourceRef);
        long total = jdbc.sql("select count(*) from gl_journal_entry" + where.sql()).params(where.params())
                .query(Long.class).single();
        List<JournalEntry> items = jdbc.sql("select * from gl_journal_entry" + where.sql()
                        + " order by posted_at desc limit :limit offset :offset")
                .params(where.params()).param("limit", size).param("offset", (long) page * size)
                .query(this::map).list();
        return new Page<>(items, total, page, size);
    }

    private JournalEntry map(ResultSet rs, int rowNum) throws SQLException {
        return new JournalEntry(rs.getObject("id", UUID.class), rs.getString("number"), Sql.date(rs, "entry_date"),
                JournalSource.valueOf(rs.getString("source")), rs.getString("source_ref"), rs.getString("memo"),
                rs.getString("currency"), json.list(rs.getString("lines"), LINES), rs.getString("posted_by"),
                Sql.instant(rs, "posted_at"));
    }
}
