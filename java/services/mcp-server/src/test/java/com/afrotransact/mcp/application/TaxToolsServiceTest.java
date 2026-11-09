package com.afrotransact.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.afrotransact.mcp.domain.CallerProfile;
import com.afrotransact.mcp.domain.ToolDescriptor;
import com.afrotransact.mcp.port.Downstream;
import com.afrotransact.mcp.port.TaxApi;
import org.junit.jupiter.api.Test;

class TaxToolsServiceTest {

    private static final McpCaller STAFF = new McpCaller(new CallerProfile("staff-1", "console", "STAFF", null,
            Set.of("tax:read")), "token");

    private final FakeGuard guard = new FakeGuard();
    private final FakeTax tax = new FakeTax();
    private final TaxToolsService tools = new TaxToolsServiceImpl(guard, tax,
            Clock.fixed(Instant.parse("2026-11-09T12:00:00Z"), ZoneOffset.UTC));

    @Test
    void theQueueDefaultsToWhatWaitsForReviewAndNormalisesTheStatus() {
        assertThat(tools.reviewQueue(STAFF, null, null, null).status()).isEqualTo("OK");
        assertThat(tools.reviewQueue(STAFF, " approved ", " cashew ", -4).status()).isEqualTo("OK");
        assertThat(tax.calls).containsExactly("queue PENDING_REVIEW null 0 25", "queue APPROVED cashew 0 25");
        assertThat(guard.required).containsOnly(ToolCatalog.TAX_REVIEW_QUEUE);
    }

    @Test
    void anUnknownStatusIsRejectedBeforeTaxReturnsIsAsked() {
        ToolResult r = tools.reviewQueue(STAFF, "ALL", null, null);
        assertThat(r.status()).isEqualTo("REJECTED");
        assertThat(r.message()).contains("PENDING_REVIEW", "APPROVED");
        assertThat(tax.calls).isEmpty();
    }

    @Test
    void theShadowReportCoversTheLastThirtyDaysUnlessAskedOtherwise() {
        assertThat(tools.shadowReport(STAFF, null, null).status()).isEqualTo("OK");
        assertThat(tools.shadowReport(STAFF, 7, " avalara ").status()).isEqualTo("OK");
        assertThat(tax.calls).containsExactly("shadow 2026-10-10 2026-11-09 null 10", "shadow 2026-11-02 2026-11-09 AVALARA 10");
        assertThat(guard.required).containsOnly(ToolCatalog.TAX_SHADOW_REPORT);
    }

    @Test
    void theShadowReportRefusesOddWindowsAndProviders() {
        assertThat(tools.shadowReport(STAFF, 45, null).status()).isEqualTo("REJECTED");
        assertThat(tools.shadowReport(STAFF, null, "PAYPAL").status()).isEqualTo("REJECTED");
        assertThat(tax.calls).isEmpty();
    }

    @Test
    void aMissingTaxServiceOrPermissionBecomesAMessageNotAGuess() {
        tax.next = Downstream.of(Downstream.Status.UNAVAILABLE);
        ToolResult unavailable = tools.reviewQueue(STAFF, null, null, null);
        assertThat(unavailable.status()).isEqualTo("UNAVAILABLE");
        assertThat(unavailable.message()).contains("do not guess");
        tax.next = Downstream.of(Downstream.Status.FORBIDDEN);
        assertThat(tools.shadowReport(STAFF, null, null).status()).isEqualTo("FORBIDDEN");
    }

    @Test
    void bothToolsAreReadOnlyStaffToolsNeedingTaxRead() {
        for (String name : List.of(ToolCatalog.TAX_REVIEW_QUEUE, ToolCatalog.TAX_SHADOW_REPORT)) {
            ToolDescriptor d = ToolCatalog.find(name).orElseThrow();
            assertThat(d.requiredPermission()).isEqualTo(McpPermissions.TAX_READ);
            assertThat(d.readOnly()).isTrue();
        }
        assertThat(McpPermissions.TAX_READ).isEqualTo("tax:read");
    }

    private static final class FakeGuard implements ToolGuardService {
        private final List<String> required = new ArrayList<>();

        @Override
        public List<ToolDescriptor> visibleTools(CallerProfile caller) {
            return List.of();
        }

        @Override
        public Admission admit(CallerProfile caller, String toolName) {
            return null;
        }

        @Override
        public void require(CallerProfile caller, String toolName) {
            required.add(toolName);
        }

        @Override
        public void audit(CallerProfile caller, String toolName, String canonicalArguments, String outcome,
                          Duration duration) {
        }
    }

    private static final class FakeTax implements TaxApi {
        private Downstream next = Downstream.ok(Map.of());
        private final List<String> calls = new ArrayList<>();

        @Override
        public Downstream reviewQueue(String status, String query, int page, int size, String bearerToken) {
            calls.add("queue " + status + " " + query + " " + page + " " + size);
            return next;
        }

        @Override
        public Downstream shadowReport(String from, String to, String provider, int size, String bearerToken) {
            calls.add("shadow " + from + " " + to + " " + provider + " " + size);
            return next;
        }
    }
}
