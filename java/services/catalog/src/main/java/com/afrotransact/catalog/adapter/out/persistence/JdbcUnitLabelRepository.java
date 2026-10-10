package com.afrotransact.catalog.adapter.out.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

import com.afrotransact.catalog.domain.Atin;
import com.afrotransact.catalog.domain.barcode.UnitLabelCode;
import com.afrotransact.catalog.port.UnitLabelRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code unit_label}: AT-FN codes, unique per owner organisation and ATIN (concurrent issues keep the first). */
@Repository
class JdbcUnitLabelRepository implements UnitLabelRepository {

    private final JdbcClient jdbc;

    JdbcUnitLabelRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<UnitLabelCode> find(String ownerOrg, Atin atin) {
        return jdbc.sql("select code from unit_label where owner_org = :org and atin = :atin")
                .param("org", ownerOrg).param("atin", atin.value())
                .query(String.class).optional().map(String::strip).map(UnitLabelCode::new);
    }

    @Override
    public UnitLabelCode insertIfAbsent(UnitLabelCode code, String ownerOrg, Atin atin, Instant now) {
        jdbc.sql("""
                insert into unit_label (code, owner_org, atin, created_at) values (:code, :org, :atin, :now)
                on conflict (owner_org, atin) do nothing
                """)
                .param("code", code.value()).param("org", ownerOrg).param("atin", atin.value())
                .param("now", Timestamp.from(now))
                .update();
        return find(ownerOrg, atin).orElseThrow();
    }
}
