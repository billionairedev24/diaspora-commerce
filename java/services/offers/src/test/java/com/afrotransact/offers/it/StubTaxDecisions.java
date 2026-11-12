package com.afrotransact.offers.it;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import com.afrotransact.offers.port.TaxDecisions;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * In-memory tax-returns (no network in tests). By default every seller has a RESOLVED decision on every ATIN, so offers go
 * live as they always did; a test withholds a decision (the seller has none until {@link #resolve} is called or the "request
 * for a decision" succeeds) or makes tax-returns unreachable.
 */
@TestConfiguration(proxyBeanMethods = false)
public class StubTaxDecisions {

    private static final Set<String> WITHHELD = ConcurrentHashMap.newKeySet();
    private static final Map<String, String> REQUEST_ANSWER = new ConcurrentHashMap<>();
    private static final AtomicBoolean UNAVAILABLE = new AtomicBoolean();

    /** The seller has no stored decision on the ATIN, and a request for one is answered with {@code requestStatus}. */
    public static void withhold(String atin, String sellerOrg, String requestStatus) {
        WITHHELD.add(key(atin, sellerOrg));
        REQUEST_ANSWER.put(key(atin, sellerOrg), requestStatus);
    }

    /** tax-returns now holds a RESOLVED decision for the seller on the ATIN. */
    public static void resolve(String atin, String sellerOrg) {
        WITHHELD.remove(key(atin, sellerOrg));
        REQUEST_ANSWER.remove(key(atin, sellerOrg));
    }

    public static void unavailable(boolean down) {
        UNAVAILABLE.set(down);
    }

    public static void reset() {
        WITHHELD.clear();
        REQUEST_ANSWER.clear();
        UNAVAILABLE.set(false);
    }

    private static String key(String atin, String org) {
        return atin + "|" + org;
    }

    @Bean
    @Primary
    TaxDecisions stubTaxDecisions() {
        return new TaxDecisions() {
            @Override
            public DecisionState find(String atin, String sellerOrgId) {
                check();
                return WITHHELD.contains(key(atin, sellerOrgId))
                        ? new DecisionState("MISSING", false, List.of("No tax decision yet"))
                        : new DecisionState("RESOLVED", true, List.of());
            }

            @Override
            public DecisionState request(String atin, String sellerOrgId, Map<String, Object> sellerBehaviour) {
                check();
                String answer = REQUEST_ANSWER.get(key(atin, sellerOrgId));
                if (answer == null || "RESOLVED".equals(answer)) {
                    WITHHELD.remove(key(atin, sellerOrgId));
                    return new DecisionState("RESOLVED", true, List.of());
                }
                return new DecisionState(answer, false, List.of("A fact is missing"));
            }

            private void check() {
                if (UNAVAILABLE.get()) {
                    throw new TaxUnavailable("tax-returns is down in this test", null);
                }
            }
        };
    }
}
