package com.afrotransact.content.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.afrotransact.content.domain.market.Capabilities;
import com.afrotransact.content.domain.market.Country;
import com.afrotransact.content.domain.market.MarketSnapshot;
import com.afrotransact.content.domain.market.MarketStatus;
import com.afrotransact.content.domain.market.Region;
import com.afrotransact.content.domain.market.RegionKind;
import com.afrotransact.content.port.MarketRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcMarketRepository implements MarketRepository {

    private static final String COUNTRY_COLUMNS = "code, name, status, launch_note, currency, sort_order";
    private static final String REGION_COLUMNS = """
            country, code, name, kind, marketplace, services, food, pickup, tax_registration,
            zip_prefixes::text as zip_prefixes""";

    private final JdbcClient jdbc;
    private final JsonCodec json;

    JdbcMarketRepository(JdbcClient jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public List<Country> draftCountries() {
        List<Region> regions = jdbc.sql("select " + REGION_COLUMNS + " from market_region order by country, name")
                .query(this::region).list();
        List<CountryRow> rows = jdbc.sql("select " + COUNTRY_COLUMNS + " from market_country order by sort_order, name")
                .query(JdbcMarketRepository::country).list();
        return assemble(rows, regions);
    }

    @Override
    public Optional<Country> draftCountry(String code) {
        String c = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        List<Region> regions = jdbc.sql("select " + REGION_COLUMNS + " from market_region where country = :c order by name")
                .param("c", c).query(this::region).list();
        return jdbc.sql("select " + COUNTRY_COLUMNS + " from market_country where code = :c").param("c", c)
                .query(JdbcMarketRepository::country).optional()
                .map(row -> row.toCountry(regions));
    }

    @Override
    public void saveDraftCountry(Country country, String actor, Instant now) {
        jdbc.sql("""
                update market_country set status = :status, launch_note = :note, updated_at = :now, updated_by = :actor
                where code = :code
                """)
                .param("status", country.status().name())
                .param("note", country.launchNote())
                .param("now", Timestamps.of(now))
                .param("actor", actor)
                .param("code", country.code())
                .update();
    }

    @Override
    public void saveDraftRegion(Region r, String actor, Instant now) {
        Capabilities c = r.capabilities();
        jdbc.sql("""
                update market_region set marketplace = :m, services = :s, food = :f, pickup = :p,
                    tax_registration = :t
                where country = :country and code = :code
                """)
                .param("m", c.marketplace()).param("s", c.services()).param("f", c.food())
                .param("p", c.pickup()).param("t", c.taxRegistration())
                .param("country", r.country()).param("code", r.code())
                .update();
        jdbc.sql("update market_country set updated_at = :now, updated_by = :actor where code = :code")
                .param("now", Timestamps.of(now)).param("actor", actor).param("code", r.country()).update();
    }

    @Override
    public int latestVersion() {
        return jdbc.sql("select coalesce(max(version), 0) from market_publication").query(Integer.class).single();
    }

    @Override
    public MarketSnapshot snapshot(int version) {
        Publication pub = jdbc.sql("select version, published_at, published_by, note from market_publication where version = :v")
                .param("v", version).query(JdbcMarketRepository::publication).single();
        List<Region> regions = jdbc.sql("select " + REGION_COLUMNS
                        + " from market_region_published where version = :v order by country, name")
                .param("v", version).query(this::region).list();
        List<CountryRow> rows = jdbc.sql("select " + COUNTRY_COLUMNS
                        + " from market_country_published where version = :v order by sort_order, name")
                .param("v", version).query(JdbcMarketRepository::country).list();
        return new MarketSnapshot(version, pub.publishedAt(), pub.publishedBy(), assemble(rows, regions));
    }

    @Override
    public boolean publish(int version, String actor, String note, Instant now) {
        try {
            jdbc.sql("insert into market_publication (version, published_at, published_by, note) values (:v, :at, :by, :note)")
                    .param("v", version).param("at", Timestamps.of(now)).param("by", actor).param("note", note)
                    .update();
        } catch (DuplicateKeyException ex) {
            return false;
        }
        jdbc.sql("""
                insert into market_country_published (version, code, name, status, launch_note, currency, sort_order)
                select :v, code, name, status, launch_note, currency, sort_order from market_country
                """).param("v", version).update();
        jdbc.sql("""
                insert into market_region_published (version, country, code, name, kind, marketplace, services, food,
                    pickup, tax_registration, zip_prefixes)
                select :v, country, code, name, kind, marketplace, services, food, pickup, tax_registration,
                    zip_prefixes
                from market_region
                """).param("v", version).update();
        return true;
    }

    @Override
    public List<Publication> publications(int limit) {
        return jdbc.sql("select version, published_at, published_by, note from market_publication order by version desc limit :l")
                .param("l", limit).query(JdbcMarketRepository::publication).list();
    }

    private static List<Country> assemble(List<CountryRow> rows, List<Region> regions) {
        Map<String, List<Region>> byCountry = new HashMap<>();
        regions.forEach(r -> byCountry.computeIfAbsent(r.country(), k -> new ArrayList<>()).add(r));
        return rows.stream().map(row -> row.toCountry(byCountry.getOrDefault(row.code(), List.of()))).toList();
    }

    private Region region(ResultSet rs, int row) throws SQLException {
        return new Region(rs.getString("country"), rs.getString("code"), rs.getString("name"),
                RegionKind.valueOf(rs.getString("kind")),
                new Capabilities(rs.getBoolean("marketplace"), rs.getBoolean("services"), rs.getBoolean("food"),
                        rs.getBoolean("pickup"), rs.getBoolean("tax_registration")),
                json.strings(rs.getString("zip_prefixes")));
    }

    private static CountryRow country(ResultSet rs, int row) throws SQLException {
        return new CountryRow(rs.getString("code"), rs.getString("name"), MarketStatus.valueOf(rs.getString("status")),
                rs.getString("launch_note"), rs.getString("currency"), rs.getInt("sort_order"));
    }

    private static Publication publication(ResultSet rs, int row) throws SQLException {
        return new Publication(rs.getInt("version"), Timestamps.instant(rs, "published_at"),
                rs.getString("published_by"), rs.getString("note"));
    }

    private record CountryRow(String code, String name, MarketStatus status, String launchNote, String currency,
                              int sortOrder) {

        Country toCountry(List<Region> regions) {
            return new Country(code, name, status, launchNote, currency, sortOrder, regions);
        }
    }
}
