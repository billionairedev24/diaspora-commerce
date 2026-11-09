package com.afrotransact.catalog.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.catalog.domain.tax.TaxEvidence;
import com.afrotransact.catalog.port.TaxEvidenceRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcTaxEvidenceRepository implements TaxEvidenceRepository {

    private static final String COLUMNS = """
            id, variant_atin, owner_org, fact_id, version, file_name, content_type, bytes, uploaded_by, uploaded_at
            """;

    private final JdbcClient jdbc;

    JdbcTaxEvidenceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public TaxEvidence store(TaxEvidence m, byte[] content) {
        jdbc.sql("""
                insert into variant_tax_evidence (id, variant_atin, owner_org, fact_id, version, file_name,
                                                  content_type, bytes, content, uploaded_by, uploaded_at)
                values (:id, :atin, :org, :fact, :version, :name, :type, :bytes, :content, :by, :at)
                """)
                .param("id", m.id()).param("atin", m.variantAtin()).param("org", m.ownerOrg()).param("fact", m.factId())
                .param("version", m.version()).param("name", m.fileName()).param("type", m.contentType())
                .param("bytes", m.bytes()).param("content", content).param("by", m.uploadedBy())
                .param("at", Timestamp.from(m.uploadedAt())).update();
        return m;
    }

    @Override
    public List<TaxEvidence> findByVariant(String variantAtin) {
        return jdbc.sql("select " + COLUMNS + " from variant_tax_evidence where variant_atin = :atin "
                + "order by fact_id, version desc").param("atin", variantAtin)
                .query(JdbcTaxEvidenceRepository::map).list();
    }

    @Override
    public List<TaxEvidence> latestByVariant(String variantAtin) {
        return jdbc.sql("select distinct on (fact_id) " + COLUMNS + " from variant_tax_evidence "
                + "where variant_atin = :atin order by fact_id, version desc").param("atin", variantAtin)
                .query(JdbcTaxEvidenceRepository::map).list();
    }

    @Override
    public Optional<TaxEvidence> findById(UUID id) {
        return jdbc.sql("select " + COLUMNS + " from variant_tax_evidence where id = :id").param("id", id)
                .query(JdbcTaxEvidenceRepository::map).optional();
    }

    @Override
    public Optional<byte[]> content(UUID id) {
        return jdbc.sql("select content from variant_tax_evidence where id = :id").param("id", id)
                .query((rs, n) -> rs.getBytes("content")).optional();
    }

    @Override
    public int nextVersion(String variantAtin, String factId) {
        Integer max = jdbc.sql("select max(version) from variant_tax_evidence where variant_atin = :atin and fact_id = :fact")
                .param("atin", variantAtin).param("fact", factId).query(Integer.class).optional().orElse(null);
        return max == null ? 1 : max + 1;
    }

    private static TaxEvidence map(ResultSet rs, int n) throws SQLException {
        return new TaxEvidence(rs.getObject("id", UUID.class), rs.getString("variant_atin").trim(),
                rs.getString("owner_org"), rs.getString("fact_id"), rs.getInt("version"), rs.getString("file_name"),
                rs.getString("content_type"), rs.getLong("bytes"), rs.getString("uploaded_by"),
                rs.getTimestamp("uploaded_at").toInstant());
    }
}
