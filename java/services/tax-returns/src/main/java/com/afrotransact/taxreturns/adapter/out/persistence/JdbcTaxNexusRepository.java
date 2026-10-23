package com.afrotransact.taxreturns.adapter.out.persistence;

import java.sql.Date;
import java.sql.Timestamp;
import java.util.List;

import com.afrotransact.taxreturns.domain.tax.NexusSetting;
import com.afrotransact.taxreturns.port.TaxNexusRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcTaxNexusRepository implements TaxNexusRepository {

    private final JdbcClient jdbc;

    JdbcTaxNexusRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<NexusSetting> all() {
        return jdbc.sql("select * from tax_nexus_state order by jurisdiction").query((rs, n) -> {
            Date on = rs.getDate("registered_on");
            int txns = rs.getInt("transactions_threshold");
            return new NexusSetting(rs.getString("jurisdiction"), rs.getLong("sales_threshold_minor"),
                    rs.wasNull() ? null : txns, rs.getBoolean("registered"), rs.getString("registration_number"),
                    on == null ? null : on.toLocalDate(), rs.getString("note"), rs.getString("updated_by"),
                    rs.getTimestamp("updated_at").toInstant());
        }).list();
    }

    @Override
    public void save(NexusSetting s) {
        jdbc.sql("""
                insert into tax_nexus_state (jurisdiction, sales_threshold_minor, transactions_threshold, registered,
                    registration_number, registered_on, note, updated_by, updated_at)
                values (:j, :sales, :txns, :reg, :num, :on, :note, :by, :at)
                on conflict (jurisdiction) do update set sales_threshold_minor = :sales, transactions_threshold = :txns,
                    registered = :reg, registration_number = :num, registered_on = :on, note = :note,
                    updated_by = :by, updated_at = :at
                """)
                .param("j", s.jurisdiction()).param("sales", s.salesThresholdMinor())
                .param("txns", s.transactionsThreshold()).param("reg", s.registered())
                .param("num", s.registrationNumber())
                .param("on", s.registeredOn() == null ? null : Date.valueOf(s.registeredOn()))
                .param("note", s.note()).param("by", s.updatedBy()).param("at", Timestamp.from(s.updatedAt()))
                .update();
    }
}
