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
import com.afrotransact.content.domain.market.City;
import com.afrotransact.content.domain.market.Country;
import com.afrotransact.content.domain.market.LocationStatus;
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
    private static final String DRAFT_COUNTRY_COLUMNS = COUNTRY_COLUMNS + ", version";
    private static final String REGION_COLUMNS = """
            country, code, name, kind, marketplace, services, food, pickup, tax_registration,
            zip_prefixes::text as zip_prefixes, planned, every_city""";
    private static final String DRAFT_REGION_COLUMNS = REGION_COLUMNS + ", version";
    private static final String CITY_COLUMNS = "country, region, name, status";

    private final JdbcClient jdbc;
    private final JsonCodec json;

    JdbcMarketRepository(JdbcClient jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public List<Country> draftCountries() {
        List<CityRow> cities = jdbc.sql("select " + CITY_COLUMNS + " from market_city order by country, region, position")
                .query(JdbcMarketRepository::city).list();
        List<Region> regions = jdbc.sql("select " + DRAFT_REGION_COLUMNS + " from market_region order by country, name")
                .query(this::region).list();
        List<CountryRow> rows = jdbc.sql("select " + DRAFT_COUNTRY_COLUMNS + " from market_country order by sort_order, name")
                .query(JdbcMarketRepository::country).list();
        return assemble(rows, withCities(regions, cities));
    }

    @Override
    public Optional<Country> draftCountry(String code) {
        String c = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        List<CityRow> cities = jdbc.sql("select " + CITY_COLUMNS + " from market_city where country = :c order by region, position")
                .param("c", c).query(JdbcMarketRepository::city).list();
        List<Region> regions = withCities(jdbc.sql("select " + DRAFT_REGION_COLUMNS
                        + " from market_region where country = :c order by name")
                .param("c", c).query(this::region).list(), cities);
        return jdbc.sql("select " + DRAFT_COUNTRY_COLUMNS + " from market_country where code = :c").param("c", c)
                .query(JdbcMarketRepository::country).optional()
                .map(row -> row.toCountry(regions));
    }

    @Override
    public boolean saveDraftCountry(Country country, String actor, Instant now) {
        return jdbc.sql("""
                update market_country set status = :status, launch_note = :note, updated_at = :now, updated_by = :actor,
                    version = version + 1
                where code = :code and version = :version
                """)
                .param("status", country.status().name())
                .param("note", country.launchNote())
                .param("now", Timestamps.of(now))
                .param("actor", actor)
                .param("code", country.code())
                .param("version", country.version())
                .update() == 1;
    }

    @Override
    public boolean saveDraftRegion(Region r, String actor, Instant now) {
        Capabilities c = r.capabilities();
        int updated = jdbc.sql("""
                update market_region set marketplace = :m, services = :s, food = :f, pickup = :p,
                    tax_registration = :t, planned = :planned, every_city = :every, version = version + 1
                where country = :country and code = :code and version = :version
                """)
                .param("m", c.marketplace()).param("s", c.services()).param("f", c.food())
                .param("p", c.pickup()).param("t", c.taxRegistration())
                .param("planned", r.planned()).param("every", r.everyCity())
                .param("country", r.country()).param("code", r.code()).param("version", r.version())
                .update();
        if (updated != 1) {
            return false;
        }
        replaceCities(r, actor, now);
        touchCountry(r.country(), actor, now);
        return true;
    }

    @Override
    public void insertDraftRegion(Region r, String actor, Instant now) {
        Capabilities c = r.capabilities();
        jdbc.sql("""
                insert into market_region (country, code, name, kind, marketplace, services, food, pickup,
                    tax_registration, zip_prefixes, planned, every_city)
                values (:country, :code, :name, :kind, :m, :s, :f, :p, :t, cast(:zips as jsonb), :planned, :every)
                """)
                .param("country", r.country()).param("code", r.code()).param("name", r.name())
                .param("kind", r.kind().name())
                .param("m", c.marketplace()).param("s", c.services()).param("f", c.food())
                .param("p", c.pickup()).param("t", c.taxRegistration())
                .param("zips", json.write(r.zipPrefixes()))
                .param("planned", r.planned()).param("every", r.everyCity())
                .update();
        replaceCities(r, actor, now);
        touchCountry(r.country(), actor, now);
    }

    private void replaceCities(Region r, String actor, Instant now) {
        jdbc.sql("delete from market_city where country = :country and region = :region")
                .param("country", r.country()).param("region", r.code()).update();
        int position = 0;
        for (City city : r.cities()) {
            jdbc.sql("""
                    insert into market_city (country, region, name, status, position, updated_at, updated_by)
                    values (:country, :region, :name, :status, :position, :now, :actor)
                    """)
                    .param("country", r.country()).param("region", r.code()).param("name", city.name())
                    .param("status", city.status().name()).param("position", position++)
                    .param("now", Timestamps.of(now)).param("actor", actor)
                    .update();
        }
    }

    private void touchCountry(String country, String actor, Instant now) {
        jdbc.sql("update market_country set updated_at = :now, updated_by = :actor where code = :code")
                .param("now", Timestamps.of(now)).param("actor", actor).param("code", country).update();
    }

    @Override
    public int latestVersion() {
        return jdbc.sql("select coalesce(max(version), 0) from market_publication").query(Integer.class).single();
    }

    @Override
    public MarketSnapshot snapshot(int version) {
        Publication pub = jdbc.sql("select version, published_at, published_by, note from market_publication where version = :v")
                .param("v", version).query(JdbcMarketRepository::publication).single();
        List<CityRow> cities = jdbc.sql("select " + CITY_COLUMNS
                        + " from market_city_published where version = :v order by country, region, position")
                .param("v", version).query(JdbcMarketRepository::city).list();
        List<Region> regions = withCities(jdbc.sql("select " + REGION_COLUMNS
                        + " from market_region_published where version = :v order by country, name")
                .param("v", version).query(this::region).list(), cities);
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
                    pickup, tax_registration, zip_prefixes, planned, every_city)
                select :v, country, code, name, kind, marketplace, services, food, pickup, tax_registration,
                    zip_prefixes, planned, every_city
                from market_region
                """).param("v", version).update();
        jdbc.sql("""
                insert into market_city_published (version, country, region, name, status, position)
                select :v, country, region, name, status, position from market_city
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

    private static List<Region> withCities(List<Region> regions, List<CityRow> cities) {
        if (cities.isEmpty()) {
            return regions;
        }
        Map<String, List<City>> byRegion = new HashMap<>();
        cities.forEach(c -> byRegion.computeIfAbsent(c.country() + "/" + c.region(), k -> new ArrayList<>())
                .add(new City(c.name(), c.status())));
        return regions.stream().map(r -> {
            List<City> listed = byRegion.get(r.country() + "/" + r.code());
            return listed == null ? r : new Region(r.country(), r.code(), r.name(), r.kind(), r.capabilities(),
                    r.zipPrefixes(), r.planned(), r.everyCity(), listed, r.version());
        }).toList();
    }

    private Region region(ResultSet rs, int row) throws SQLException {
        return new Region(rs.getString("country"), rs.getString("code"), rs.getString("name"),
                RegionKind.valueOf(rs.getString("kind")),
                new Capabilities(rs.getBoolean("marketplace"), rs.getBoolean("services"), rs.getBoolean("food"),
                        rs.getBoolean("pickup"), rs.getBoolean("tax_registration")),
                json.strings(rs.getString("zip_prefixes")), rs.getBoolean("planned"), rs.getBoolean("every_city"),
                List.of(), hasColumn(rs, "version") ? rs.getLong("version") : 0);
    }

    private static CityRow city(ResultSet rs, int row) throws SQLException {
        return new CityRow(rs.getString("country"), rs.getString("region"), rs.getString("name"),
                LocationStatus.valueOf(rs.getString("status")));
    }

    private static CountryRow country(ResultSet rs, int row) throws SQLException {
        return new CountryRow(rs.getString("code"), rs.getString("name"), MarketStatus.valueOf(rs.getString("status")),
                rs.getString("launch_note"), rs.getString("currency"), rs.getInt("sort_order"),
                hasColumn(rs, "version") ? rs.getLong("version") : 0);
    }

    private static boolean hasColumn(ResultSet rs, String column) throws SQLException {
        int n = rs.getMetaData().getColumnCount();
        for (int i = 1; i <= n; i++) {
            if (column.equalsIgnoreCase(rs.getMetaData().getColumnLabel(i))) {
                return true;
            }
        }
        return false;
    }

    private static Publication publication(ResultSet rs, int row) throws SQLException {
        return new Publication(rs.getInt("version"), Timestamps.instant(rs, "published_at"),
                rs.getString("published_by"), rs.getString("note"));
    }

    private record CountryRow(String code, String name, MarketStatus status, String launchNote, String currency,
                              int sortOrder, long version) {

        Country toCountry(List<Region> regions) {
            return new Country(code, name, status, launchNote, currency, sortOrder, regions, version);
        }
    }

    private record CityRow(String country, String region, String name, LocationStatus status) {
    }
}
