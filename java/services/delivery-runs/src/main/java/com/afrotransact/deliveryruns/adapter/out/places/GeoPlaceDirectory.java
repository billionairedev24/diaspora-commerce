package com.afrotransact.deliveryruns.adapter.out.places;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Pattern;

import com.afrotransact.deliveryruns.port.PlaceDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriBuilder;
import tools.jackson.databind.JsonNode;

/**
 * Places from geo-service's world gazetteer ({@code GET /v1/places/...}: every country, its states / provinces and
 * all their cities), so staff can cover any city of a state. Countries and regions are cached (reference data); when
 * geo cannot answer, the design's built-in list answers instead.
 */
public class GeoPlaceDirectory implements PlaceDirectory {

    private static final Logger LOG = LoggerFactory.getLogger(GeoPlaceDirectory.class);
    /** Region codes coverage rows can store ({@code AreaKey}). */
    private static final Pattern REGION = Pattern.compile("^[A-Z0-9]{1,3}$");

    private record Country(String code, String name, String regionLabel) {
    }

    private record Region(String code, String name) {
    }

    private final RestClient geo;
    private final BuiltInPlaceDirectory builtIn = new BuiltInPlaceDirectory();
    private volatile List<Country> countries;
    private final Map<String, List<Region>> regions = new ConcurrentHashMap<>();

    public GeoPlaceDirectory(String baseUrl, Duration timeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        this.geo = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    @Override
    public List<Place> search(String q, int limit) {
        String t = q == null ? "" : q.trim();
        if (t.length() < 2) {
            return List.of();
        }
        int n = Math.max(1, Math.min(limit, 50));
        Optional<JsonNode> r = get("search", b -> b.path("/v1/places/search").queryParam("q", t)
                .queryParam("limit", n).build());
        if (r.isEmpty()) {
            return builtIn.search(q, limit);
        }
        List<Place> out = new ArrayList<>();
        for (JsonNode m : r.get().path("items")) {
            String type = m.path("type").asString("");
            String country = m.path("country").asString("");
            String countryName = m.path("countryName").asString(country);
            String region = m.path("region").asString("");
            String name = m.path("name").asString("");
            switch (type) {
                case "COUNTRY" -> out.add(new Place(country, country, name, name, "Country", type));
                case "REGION" -> {
                    if (REGION.matcher(region).matches()) {
                        out.add(new Place(country + "/" + region, region, name, "All of " + name,
                                regionLabel(country) + " · " + countryName, type));
                    }
                }
                case "CITY" -> {
                    if (REGION.matcher(region).matches()) {
                        out.add(new Place(country + "/" + region + "/" + name, name, name, name + ", " + region,
                                m.path("regionName").asString(region) + " · " + countryName, type));
                    }
                }
                default -> {
                    // unknown match type: skip
                }
            }
        }
        return out;
    }

    @Override
    public List<Place> browse(String country, String region) {
        if (country == null || country.isBlank()) {
            List<Country> all = countries();
            if (all.isEmpty()) {
                return builtIn.browse(null, null);
            }
            return all.stream().map(c -> new Place(c.code(), c.code(), c.name(), c.name(), c.regionLabel(), "COUNTRY"))
                    .toList();
        }
        String c = country.trim().toUpperCase(Locale.ROOT);
        if (region == null || region.isBlank()) {
            List<Region> list = regions(c);
            if (list.isEmpty()) {
                return builtIn.browse(c, null);
            }
            String cn = countryName(c);
            return list.stream().map(r -> new Place(c + "/" + r.code(), r.code(), r.name(), r.name(), cn, "REGION"))
                    .toList();
        }
        String rc = region.trim().toUpperCase(Locale.ROOT);
        Optional<JsonNode> r = get("cities", b -> b.path("/v1/places/countries/{c}/regions/{r}/cities")
                .build(c, rc));
        if (r.isEmpty()) {
            return builtIn.browse(c, rc);
        }
        String rn = r.get().path("regionName").asString(rc);
        List<Place> out = new ArrayList<>();
        for (JsonNode x : r.get().path("items")) {
            String city = x.asString("").trim();
            if (!city.isEmpty() && city.length() <= 80 && !city.contains("/")) {
                out.add(new Place(c + "/" + rc + "/" + city, city, city, city + ", " + rc, rn, "CITY"));
            }
        }
        return out;
    }

    @Override
    public String countryOfRegion(String region) {
        return builtIn.countryOfRegion(region);
    }

    @Override
    public String countryName(String country) {
        String c = country == null ? "" : country.trim().toUpperCase(Locale.ROOT);
        return countries().stream().filter(x -> x.code().equals(c)).map(Country::name).findFirst()
                .orElseGet(() -> builtIn.countryName(country));
    }

    @Override
    public String regionName(String country, String region) {
        String c = country == null ? "" : country.trim().toUpperCase(Locale.ROOT);
        String r = region == null ? "" : region.trim().toUpperCase(Locale.ROOT);
        return regions(c).stream().filter(x -> x.code().equals(r)).map(Region::name).findFirst()
                .orElseGet(() -> builtIn.regionName(country, region));
    }

    @Override
    public String regionLabel(String country) {
        String c = country == null ? "" : country.trim().toUpperCase(Locale.ROOT);
        return countries().stream().filter(x -> x.code().equals(c)).map(Country::regionLabel).findFirst()
                .orElseGet(() -> builtIn.regionLabel(country));
    }

    private List<Country> countries() {
        List<Country> cached = countries;
        if (cached != null) {
            return cached;
        }
        Optional<JsonNode> r = get("countries", b -> b.path("/v1/places/countries").build());
        if (r.isEmpty()) {
            return List.of();
        }
        List<Country> out = new ArrayList<>();
        for (JsonNode c : r.get().path("items")) {
            out.add(new Country(c.path("code").asString(""), c.path("name").asString(""),
                    c.path("regionLabel").asString("Region")));
        }
        countries = List.copyOf(out);
        return countries;
    }

    private List<Region> regions(String country) {
        if (country.isEmpty()) {
            return List.of();
        }
        List<Region> cached = regions.get(country);
        if (cached != null) {
            return cached;
        }
        Optional<JsonNode> r = get("regions", b -> b.path("/v1/places/countries/{c}/regions").build(country));
        if (r.isEmpty()) {
            return List.of();
        }
        List<Region> out = new ArrayList<>();
        for (JsonNode x : r.get().path("items")) {
            String code = x.path("code").asString("").toUpperCase(Locale.ROOT);
            if (REGION.matcher(code).matches()) {
                out.add(new Region(code, x.path("name").asString(code)));
            }
        }
        List<Region> list = List.copyOf(out);
        regions.put(country, list);
        return list;
    }

    private Optional<JsonNode> get(String what, Function<UriBuilder, URI> uri) {
        try {
            return Optional.ofNullable(geo.get().uri(uri).retrieve().body(JsonNode.class));
        } catch (RestClientException e) {
            LOG.debug("geo places/{} unavailable, using the built-in list: {}", what, e.getMessage());
            return Optional.empty();
        }
    }
}
