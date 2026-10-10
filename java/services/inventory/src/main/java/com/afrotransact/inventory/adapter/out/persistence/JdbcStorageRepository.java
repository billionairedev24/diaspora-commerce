package com.afrotransact.inventory.adapter.out.persistence;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import com.afrotransact.inventory.domain.SkuDimensions;
import com.afrotransact.inventory.port.StorageRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcStorageRepository implements StorageRepository {

    private final JdbcClient jdbc;

    JdbcStorageRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void saveDimensions(SkuDimensions d, String actorId, Instant at) {
        jdbc.sql("""
                insert into sku_dimension (sku, length_in, width_in, height_in, unit_cubic_feet, updated_by, updated_at)
                values (:sku, :l, :w, :h, :cf, :by, :at)
                on conflict (sku) do update set length_in = excluded.length_in, width_in = excluded.width_in,
                    height_in = excluded.height_in, unit_cubic_feet = excluded.unit_cubic_feet,
                    updated_by = excluded.updated_by, updated_at = excluded.updated_at
                """)
                .param("sku", d.sku()).param("l", d.lengthIn()).param("w", d.widthIn()).param("h", d.heightIn())
                .param("cf", d.unitCubicFeet()).param("by", actorId).param("at", Timestamp.from(at))
                .update();
    }

    @Override
    public Optional<StoredDimensions> findDimensions(String sku) {
        return jdbc.sql("select * from sku_dimension where sku = :sku").param("sku", sku)
                .query((rs, n) -> new StoredDimensions(new SkuDimensions(rs.getString("sku"),
                        rs.getBigDecimal("length_in"), rs.getBigDecimal("width_in"), rs.getBigDecimal("height_in")),
                        rs.getBigDecimal("unit_cubic_feet"), rs.getString("updated_by"),
                        rs.getTimestamp("updated_at").toInstant()))
                .optional();
    }

    @Override
    public int snapshot(LocalDate day, BigDecimal defaultUnitCubicFeet, Instant at) {
        // Replicas running the job together queue here instead of colliding on the primary key.
        jdbc.sql("select pg_advisory_xact_lock(hashtext('inventory.storage_snapshot'))").query(Object.class).list();
        jdbc.sql("delete from storage_snapshot where day = :day").param("day", Date.valueOf(day)).update();
        return jdbc.sql("""
                insert into storage_snapshot (day, owner_org, node_id, units, unmeasured_units, cubic_feet, taken_at)
                select :day, p.owner_org, p.node_id, sum(p.on_hand + p.damaged),
                       sum(case when d.sku is null then p.on_hand + p.damaged else 0 end),
                       sum((p.on_hand + p.damaged) * coalesce(d.unit_cubic_feet, :default)), :at
                from inventory_position p
                join node n on n.id = p.node_id and n.kind = 'FULFILMENT_CENTRE'
                left join sku_dimension d on d.sku = p.sku
                group by p.owner_org, p.node_id
                having sum(p.on_hand + p.damaged) > 0
                """)
                .param("day", Date.valueOf(day)).param("default", defaultUnitCubicFeet)
                .param("at", Timestamp.from(at))
                .update();
    }

    @Override
    public List<StorageDay> usage(LocalDate from, LocalDate to, String ownerOrg) {
        return jdbc.sql("""
                select owner_org, day, sum(units) as units, sum(unmeasured_units) as unmeasured,
                       sum(cubic_feet) as cubic_feet
                from storage_snapshot
                where day between :from and :to and (cast(:owner as varchar) is null or owner_org = :owner)
                group by owner_org, day
                order by owner_org, day
                """)
                .param("from", Date.valueOf(from)).param("to", Date.valueOf(to)).param("owner", ownerOrg)
                .query((rs, n) -> new StorageDay(rs.getString("owner_org"), rs.getDate("day").toLocalDate(),
                        rs.getLong("units"), rs.getLong("unmeasured"), rs.getBigDecimal("cubic_feet")))
                .list();
    }
}
