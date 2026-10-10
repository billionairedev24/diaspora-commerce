package com.afrotransact.retail.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.platform.web.error.ConflictException;
import com.afrotransact.retail.domain.EdiProtocol;
import com.afrotransact.retail.domain.SupplierEdiProfile;
import com.afrotransact.retail.port.SupplierEdiProfileRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcSupplierEdiProfileRepository implements SupplierEdiProfileRepository {

    private final JdbcClient jdbc;

    JdbcSupplierEdiProfileRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<SupplierEdiProfile> find(UUID supplierId) {
        return jdbc.sql("select * from supplier_edi_profile where supplier_id = :id").param("id", supplierId)
                .query(this::map).optional();
    }

    @Override
    public SupplierEdiProfile save(SupplierEdiProfile p) {
        int rows = jdbc.sql("""
                insert into supplier_edi_profile (supplier_id, enabled, protocol, interchange_id, endpoint, updated_by,
                                                  updated_at, version)
                values (:id, :enabled, :protocol, :interchange, :endpoint, :by, :at, 0)
                on conflict (supplier_id) do update set enabled = excluded.enabled, protocol = excluded.protocol,
                    interchange_id = excluded.interchange_id, endpoint = excluded.endpoint,
                    updated_by = excluded.updated_by, updated_at = excluded.updated_at,
                    version = supplier_edi_profile.version + 1
                where supplier_edi_profile.version = :version
                """)
                .param("id", p.supplierId()).param("enabled", p.enabled()).param("protocol", p.protocol().name())
                .param("interchange", p.interchangeId()).param("endpoint", p.endpoint()).param("by", p.updatedBy())
                .param("at", Sql.ts(p.updatedAt())).param("version", p.version())
                .update();
        if (rows == 0) {
            throw new ConflictException("retail.concurrent-update", "EDI settings of the supplier changed; reload");
        }
        return find(p.supplierId()).orElseThrow();
    }

    private SupplierEdiProfile map(ResultSet rs, int n) throws SQLException {
        return new SupplierEdiProfile(rs.getObject("supplier_id", UUID.class), rs.getBoolean("enabled"),
                EdiProtocol.valueOf(rs.getString("protocol")), rs.getString("interchange_id"),
                rs.getString("endpoint"), rs.getString("updated_by"), Sql.instant(rs, "updated_at"),
                rs.getLong("version"));
    }
}
