package com.afrotransact.retail.adapter.out.persistence;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.platform.web.error.ConflictException;
import com.afrotransact.retail.port.LotRegistry;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code received_lot (sku, lot_no)} primary key guarantees lot numbers are unique per SKU across receipts. */
@Repository
class JdbcLotRegistry implements LotRegistry {

    private final JdbcClient jdbc;

    JdbcLotRegistry(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Set<String> existing(Collection<String> keys, UUID exceptReceiptId) {
        if (keys.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(jdbc.sql("""
                select sku || '|' || lot_no from received_lot
                where sku || '|' || lot_no in (:keys) and receipt_id <> :receipt
                """)
                .param("keys", keys).param("receipt", exceptReceiptId).query(String.class).list());
    }

    @Override
    public void register(UUID receiptId, Collection<String> keys) {
        for (String key : keys) {
            int bar = key.indexOf('|');
            try {
                jdbc.sql("insert into received_lot (sku, lot_no, receipt_id) values (:sku, :lot, :receipt)")
                        .param("sku", key.substring(0, bar)).param("lot", key.substring(bar + 1))
                        .param("receipt", receiptId).update();
            } catch (DuplicateKeyException e) {
                throw new ConflictException("retail.lot-exists", "Lot " + key.substring(bar + 1) + " of "
                        + key.substring(0, bar) + " was already received");
            }
        }
    }

    @Override
    public void release(UUID receiptId) {
        jdbc.sql("delete from received_lot where receipt_id = :receipt").param("receipt", receiptId).update();
    }
}
