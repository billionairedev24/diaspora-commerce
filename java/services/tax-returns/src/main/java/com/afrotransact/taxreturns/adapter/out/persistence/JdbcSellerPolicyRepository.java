package com.afrotransact.taxreturns.adapter.out.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

import com.afrotransact.platform.web.error.ConflictException;
import com.afrotransact.taxreturns.domain.returns.SellerReturnPolicy;
import com.afrotransact.taxreturns.port.SellerPolicyRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcSellerPolicyRepository implements SellerPolicyRepository {

    private final JdbcClient jdbc;
    private final JsonCodec json;

    JdbcSellerPolicyRepository(JdbcClient jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public Optional<SellerReturnPolicy> find(String orgId) {
        return jdbc.sql("select * from seller_return_policy where org_id = :org").param("org", orgId)
                .query((rs, n) -> new SellerReturnPolicy(rs.getString("org_id"), rs.getInt("window_days"),
                        rs.getInt("restocking_fee_bps"), rs.getBoolean("accepts_returns"),
                        rs.getBoolean("refund_at_first_scan"), json.address(rs.getString("return_address")),
                        rs.getLong("version"))).optional();
    }

    @Override
    public SellerReturnPolicy upsert(SellerReturnPolicy p) {
        int rows = jdbc.sql("""
                insert into seller_return_policy (org_id, window_days, restocking_fee_bps, accepts_returns,
                                                  refund_at_first_scan, return_address, updated_at, version)
                values (:org, :window, :fee, :accepts, :firstScan, cast(:address as jsonb), :now, 0)
                on conflict (org_id) do update set window_days = excluded.window_days,
                    restocking_fee_bps = excluded.restocking_fee_bps, accepts_returns = excluded.accepts_returns,
                    refund_at_first_scan = excluded.refund_at_first_scan, return_address = excluded.return_address,
                    updated_at = excluded.updated_at, version = seller_return_policy.version + 1
                where :version < 0 or seller_return_policy.version = :version
                """)
                .param("org", p.orgId()).param("window", p.windowDays()).param("fee", p.restockingFeeBps())
                .param("accepts", p.acceptsReturns()).param("firstScan", p.refundAtFirstScan())
                .param("address", json.write(p.returnAddress())).param("now", Timestamp.from(Instant.now()))
                .param("version", p.version())
                .update();
        if (rows == 0) {
            throw new ConflictException("returns.concurrent-update", "Return policy changed; reload and retry");
        }
        return find(p.orgId()).orElseThrow();
    }
}
