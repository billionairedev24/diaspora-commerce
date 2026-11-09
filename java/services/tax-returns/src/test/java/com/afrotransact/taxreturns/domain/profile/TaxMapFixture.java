package com.afrotransact.taxreturns.domain.profile;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Loads {@code tools/taxonomy/category_tax_map.json} and {@code scenarios.json} (copied onto the test classpath under
 * {@code taxonomy/} by the pom's testResources entry, straight from the repository) into domain profiles.
 */
public final class TaxMapFixture {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };

    private TaxMapFixture() {
    }

    public static Map<String, Object> read(String name) {
        try (InputStream in = TaxMapFixture.class.getResourceAsStream("/taxonomy/" + name)) {
            if (in == null) {
                throw new IllegalStateException("taxonomy/" + name + " is not on the test classpath; "
                        + "check the testResources entry of java/services/tax-returns/pom.xml");
            }
            return JSON.readValue(in, MAP);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    public static String ruleVersion(Map<String, Object> map) {
        return (String) map.get("ruleVersion");
    }

    @SuppressWarnings("unchecked")
    public static List<TaxProfile> profiles(Map<String, Object> map) {
        Map<String, FactDefinition> defs = new LinkedHashMap<>();
        for (Object o : (List<Object>) map.get("facts")) {
            Map<String, Object> f = (Map<String, Object>) o;
            List<FactDefinition.Choice> choices = new ArrayList<>();
            if (f.get("choices") != null) {
                for (Object c : (List<Object>) f.get("choices")) {
                    Map<String, Object> cm = (Map<String, Object>) c;
                    choices.add(new FactDefinition.Choice((String) cm.get("value"), (String) cm.get("label")));
                }
            }
            defs.put((String) f.get("id"), new FactDefinition((String) f.get("id"), (String) f.get("label"),
                    FactKind.valueOf((String) f.get("kind")), (String) f.get("unit"),
                    FactOwner.valueOf((String) f.get("owner")), (String) f.get("help"),
                    Boolean.TRUE.equals(f.get("evidence")), choices));
        }
        Map<String, String> departments = new LinkedHashMap<>();
        for (Object o : (List<Object>) map.get("departments")) {
            Map<String, Object> d = (Map<String, Object>) o;
            departments.put((String) d.get("code"), (String) d.get("name"));
        }
        List<TaxProfile> out = new ArrayList<>();
        for (Object o : (List<Object>) map.get("categories")) {
            Map<String, Object> c = (Map<String, Object>) o;
            List<ProfileFact> facts = new ArrayList<>();
            for (Object fo : (List<Object>) c.get("facts")) {
                Map<String, Object> ref = (Map<String, Object>) fo;
                List<String> narrowed = ref.get("choices") == null ? List.of() : (List<String>) ref.get("choices");
                facts.add(new ProfileFact(defs.get((String) ref.get("fact")), Boolean.TRUE.equals(ref.get("required")),
                        Condition.parse(ref.get("requiredWhen")), narrowed));
            }
            List<DecisionRow> rows = new ArrayList<>();
            for (Object ro : (List<Object>) c.get("decisionTable")) {
                Map<String, Object> r = (Map<String, Object>) ro;
                Map<String, String> codes = new LinkedHashMap<>();
                ((Map<String, Object>) r.get("providerCodes")).forEach((k, v) -> codes.put(k, (String) v));
                rows.add(new DecisionRow((String) r.get("id"), ((Number) r.get("scenario")).intValue(),
                        Condition.parse(r.get("when")), (String) r.get("condition"),
                        Outcome.valueOf((String) r.get("outcome")), (String) r.get("internalTaxCode"), codes,
                        (String) r.get("rateDisplay"), (String) r.get("note")));
            }
            List<StatusReason> reasons = new ArrayList<>();
            for (Object so : (List<Object>) c.get("statusReasons")) {
                Map<String, Object> s = (Map<String, Object>) so;
                reasons.add(new StatusReason(ProfileStatus.valueOf((String) s.get("status")),
                        s.get("decision") == null ? 0 : ((Number) s.get("decision")).intValue(), (String) s.get("text")));
            }
            out.add(new TaxProfile((String) c.get("code"), (String) c.get("name"), (String) c.get("department"),
                    departments.get((String) c.get("department")), ProfileStatus.valueOf((String) c.get("status")),
                    reasons, (String) c.get("defaultOutcome"), (List<String>) c.get("stripeCodes"),
                    (List<String>) c.get("conflictingCodes"), (List<String>) c.get("factGroups"), facts, rows,
                    (String) c.get("note")));
        }
        return out;
    }
}
