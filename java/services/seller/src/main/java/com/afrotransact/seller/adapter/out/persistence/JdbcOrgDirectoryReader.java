package com.afrotransact.seller.adapter.out.persistence;

import java.util.List;
import java.util.Set;

import com.afrotransact.seller.domain.OrgStatus;
import com.afrotransact.seller.port.OrgDirectoryEntry;
import com.afrotransact.seller.port.OrgDirectoryReader;
import com.afrotransact.seller.port.Page;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcOrgDirectoryReader implements OrgDirectoryReader {

    private final JdbcClient jdbc;

    JdbcOrgDirectoryReader(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Page<OrgDirectoryEntry> page(int page, int size, Set<OrgStatus> statuses) {
        List<String> wanted = statuses.stream().map(OrgStatus::name).sorted().toList();
        String where = wanted.isEmpty() ? "" : " where status in (:statuses)";
        var rows = jdbc.sql("""
                select id, coalesce(nullif(trim(display_name), ''), nullif(trim(legal_name), '')) as name, kind, status,
                       updated_at
                from organisation""" + where + " order by id limit :size offset :offset")
                .param("size", size).param("offset", (long) page * size);
        var count = jdbc.sql("select count(*) from organisation" + where);
        if (!wanted.isEmpty()) {
            rows = rows.param("statuses", wanted);
            count = count.param("statuses", wanted);
        }
        List<OrgDirectoryEntry> items = rows.query((rs, n) -> new OrgDirectoryEntry(rs.getString("id"),
                rs.getString("name"), rs.getString("kind"), rs.getString("status"), Sql.instant(rs, "updated_at")))
                .list();
        return new Page<>(items, page, size, count.query(Long.class).single());
    }
}
