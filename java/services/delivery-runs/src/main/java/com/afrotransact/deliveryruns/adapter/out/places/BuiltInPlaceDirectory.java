package com.afrotransact.deliveryruns.adapter.out.places;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.afrotransact.deliveryruns.port.PlaceDirectory;

/**
 * The design's built-in place list ({@code at-preorder.js} GEO): countries, regions and main cities of the markets
 * pre-orders run in. The offline fallback of {@link GeoPlaceDirectory}; ranking as in the design (prefix matches
 * first).
 */
class BuiltInPlaceDirectory implements PlaceDirectory {

    /** country|name|region label ; region code=name=city|city… ; … */
    private static final String DATA = """
            US|United States|State;AL=Alabama=Birmingham|Montgomery|Huntsville|Mobile|Tuscaloosa;AK=Alaska=Anchorage|Fairbanks|Juneau;AZ=Arizona=Phoenix|Tucson|Mesa|Chandler|Scottsdale|Flagstaff;AR=Arkansas=Little Rock|Fayetteville|Fort Smith|Jonesboro;CA=California=Los Angeles|San Diego|San Jose|San Francisco|Oakland|Sacramento|Fresno|Daly City;CO=Colorado=Denver|Colorado Springs|Aurora|Fort Collins|Boulder;CT=Connecticut=Hartford|New Haven|Bridgeport|Stamford;DE=Delaware=Wilmington|Dover|Newark;DC=District of Columbia=Washington;FL=Florida=Miami|Orlando|Tampa|Jacksonville|Tallahassee|Fort Lauderdale;GA=Georgia=Atlanta|Savannah|Augusta|Columbus|Macon|Athens;HI=Hawaii=Honolulu|Hilo;ID=Idaho=Boise|Idaho Falls|Pocatello;IL=Illinois=Chicago|Aurora|Naperville|Rockford|Springfield|Peoria;IN=Indiana=Indianapolis|Fort Wayne|Evansville|South Bend;IA=Iowa=Des Moines|Cedar Rapids|Davenport|Iowa City;KS=Kansas=Wichita|Overland Park|Kansas City|Topeka;KY=Kentucky=Louisville|Lexington|Bowling Green;LA=Louisiana=New Orleans|Baton Rouge|Shreveport|Lafayette;ME=Maine=Portland|Lewiston|Bangor;MD=Maryland=Baltimore|Silver Spring|Columbia|Germantown|Bowie;MA=Massachusetts=Boston|Worcester|Springfield|Lowell|Cambridge;MI=Michigan=Detroit|Grand Rapids|Lansing|Ann Arbor|Flint;MN=Minnesota=Minneapolis|Saint Paul|Rochester|Duluth|Brooklyn Park;MS=Mississippi=Jackson|Gulfport|Hattiesburg;MO=Missouri=Kansas City|St. Louis|Springfield|Columbia;MT=Montana=Billings|Missoula|Bozeman;NE=Nebraska=Omaha|Lincoln;NV=Nevada=Las Vegas|Henderson|Reno;NH=New Hampshire=Manchester|Nashua|Concord;NJ=New Jersey=Newark|Jersey City|Paterson|Edison|Trenton|Elizabeth;NM=New Mexico=Albuquerque|Las Cruces|Santa Fe;NY=New York=New York City|Bronx|Brooklyn|Buffalo|Rochester|Albany|Syracuse;NC=North Carolina=Charlotte|Raleigh|Greensboro|Durham|Fayetteville;ND=North Dakota=Fargo|Bismarck|Grand Forks;OH=Ohio=Columbus|Cleveland|Cincinnati|Toledo|Akron|Dayton;OK=Oklahoma=Oklahoma City|Tulsa|Norman;OR=Oregon=Portland|Salem|Eugene;PA=Pennsylvania=Philadelphia|Pittsburgh|Allentown|Harrisburg;RI=Rhode Island=Providence|Warwick;SC=South Carolina=Columbia|Charleston|Greenville;SD=South Dakota=Sioux Falls|Rapid City;TN=Tennessee=Nashville|Memphis|Knoxville|Chattanooga;TX=Texas=Houston|Dallas|Austin|San Antonio|Fort Worth|El Paso|Lubbock|Amarillo|Midland|Odessa|Laredo|Corpus Christi|Waco|Tyler;UT=Utah=Salt Lake City|Provo|Ogden;VT=Vermont=Burlington|Montpelier;VA=Virginia=Virginia Beach|Richmond|Arlington|Alexandria|Norfolk;WA=Washington=Seattle|Spokane|Tacoma|Kent;WV=West Virginia=Charleston|Huntington|Morgantown;WI=Wisconsin=Milwaukee|Madison|Green Bay;WY=Wyoming=Cheyenne|Casper
            CA|Canada|Province;ON=Ontario=Toronto|Ottawa|Mississauga|Brampton|Hamilton;QC=Quebec=Montreal|Quebec City|Gatineau;BC=British Columbia=Vancouver|Surrey|Victoria;AB=Alberta=Calgary|Edmonton;MB=Manitoba=Winnipeg;SK=Saskatchewan=Saskatoon|Regina;NS=Nova Scotia=Halifax;NB=New Brunswick=Moncton|Fredericton;NL=Newfoundland and Labrador=St. John's;PE=Prince Edward Island=Charlottetown;NT=Northwest Territories=Yellowknife;YT=Yukon=Whitehorse;NU=Nunavut=Iqaluit
            GB|United Kingdom|Nation;ENG=England=London|Manchester|Birmingham|Leeds|Liverpool;SCT=Scotland=Glasgow|Edinburgh|Aberdeen;WLS=Wales=Cardiff|Swansea;NIR=Northern Ireland=Belfast
            NG|Nigeria|State;LA=Lagos=Lagos|Ikeja|Lekki;FC=Federal Capital Territory=Abuja;RI=Rivers=Port Harcourt;OY=Oyo=Ibadan;KN=Kano=Kano;EN=Enugu=Enugu;AN=Anambra=Onitsha|Awka
            GH|Ghana|Region;AA=Greater Accra=Accra|Tema;AH=Ashanti=Kumasi;WR=Western=Takoradi;NR=Northern=Tamale
            KE|Kenya|County;NBO=Nairobi=Nairobi|Westlands|Karen;MSA=Mombasa=Mombasa;KSM=Kisumu=Kisumu;NKR=Nakuru=Nakuru|Naivasha
            ZA|South Africa|Province;GP=Gauteng=Johannesburg|Pretoria|Soweto;WC=Western Cape=Cape Town|Stellenbosch;KZN=KwaZulu-Natal=Durban|Pietermaritzburg
            JM|Jamaica|Parish;KIN=Kingston=Kingston;AND=Saint Andrew=Half Way Tree|Constant Spring;JAM=Saint James=Montego Bay
            IE|Ireland|County;D=Dublin=Dublin|Swords|Tallaght;CO=Cork=Cork;G=Galway=Galway
            """;

    private record Region(String code, String name, List<String> cities) {
    }

    private record Country(String code, String name, String regionLabel, List<Region> regions) {
    }

    /** A candidate with its rank (0 best). */
    private record Ranked(Place place, int rank) {
    }

    private final List<Country> countries = new ArrayList<>();
    private final Map<String, String> countryOfRegion = new LinkedHashMap<>();

    BuiltInPlaceDirectory() {
        for (String line : DATA.strip().split("\n")) {
            String[] parts = line.split(";");
            String[] head = parts[0].split("\\|");
            List<Region> regions = new ArrayList<>();
            for (int i = 1; i < parts.length; i++) {
                String[] r = parts[i].split("=", 3);
                regions.add(new Region(r[0], r[1], List.of(r[2].split("\\|"))));
                countryOfRegion.putIfAbsent(r[0], head[0]);
            }
            countries.add(new Country(head[0], head[1], head[2], regions));
        }
    }

    @Override
    public List<Place> search(String q, int limit) {
        String t = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        if (t.length() < 2) {
            return List.of();
        }
        List<Ranked> out = new ArrayList<>();
        for (Country c : countries) {
            String cn = c.name().toLowerCase(Locale.ROOT);
            if (cn.contains(t)) {
                out.add(new Ranked(new Place(c.code(), c.code(), c.name(), c.name(), "Country", "COUNTRY"),
                        cn.startsWith(t) ? 0 : 2));
            }
            for (Region r : c.regions()) {
                String rn = r.name().toLowerCase(Locale.ROOT);
                boolean codeMatch = r.code().toLowerCase(Locale.ROOT).equals(t);
                if (rn.contains(t) || codeMatch) {
                    out.add(new Ranked(new Place(c.code() + "/" + r.code(), r.code(), r.name(), "All of " + r.name(),
                            c.regionLabel() + " · " + c.name(), "REGION"), rn.startsWith(t) || codeMatch ? 0 : 2));
                }
                for (String city : r.cities()) {
                    String x = city.toLowerCase(Locale.ROOT);
                    if (x.contains(t)) {
                        out.add(new Ranked(new Place(c.code() + "/" + r.code() + "/" + city, city, city,
                                city + ", " + r.code(), r.name() + " · " + c.name(), "CITY"), x.startsWith(t) ? 1 : 3));
                    }
                }
            }
        }
        return out.stream()
                .sorted(Comparator.comparingInt(Ranked::rank).thenComparing(x -> x.place().label()))
                .limit(Math.max(1, Math.min(limit, 50)))
                .map(Ranked::place).toList();
    }

    @Override
    public List<Place> browse(String country, String region) {
        if (country == null || country.isBlank()) {
            return countries.stream().sorted(Comparator.comparing(Country::name))
                    .map(c -> new Place(c.code(), c.code(), c.name(), c.name(), c.regionLabel(), "COUNTRY")).toList();
        }
        Optional<Country> c = country(country);
        if (c.isEmpty()) {
            return List.of();
        }
        if (region == null || region.isBlank()) {
            return c.get().regions().stream().sorted(Comparator.comparing(Region::name))
                    .map(r -> new Place(c.get().code() + "/" + r.code(), r.code(), r.name(), r.name(), c.get().name(),
                            "REGION")).toList();
        }
        return region(c.get(), region).map(r -> r.cities().stream().sorted()
                .map(x -> new Place(c.get().code() + "/" + r.code() + "/" + x, x, x, x + ", " + r.code(), r.name(),
                        "CITY")).toList()).orElse(List.of());
    }

    @Override
    public String countryName(String country) {
        return country(country).map(Country::name).orElse(country);
    }

    @Override
    public String regionName(String country, String region) {
        return country(country).flatMap(c -> region(c, region)).map(Region::name).orElse(region);
    }

    @Override
    public String regionLabel(String country) {
        return country(country).map(Country::regionLabel).orElse("State / region");
    }

    private Optional<Country> country(String code) {
        String c = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        return countries.stream().filter(x -> x.code().equals(c)).findFirst();
    }

    private static Optional<Region> region(Country c, String code) {
        String r = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        return c.regions().stream().filter(x -> x.code().equals(r)).findFirst();
    }

    @Override
    public String countryOfRegion(String region) {
        return region == null ? "" : countryOfRegion.getOrDefault(region.trim().toUpperCase(Locale.ROOT), "");
    }
}
