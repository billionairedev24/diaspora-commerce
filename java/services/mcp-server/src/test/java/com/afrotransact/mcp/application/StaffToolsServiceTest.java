package com.afrotransact.mcp.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Set;

import com.afrotransact.mcp.domain.CallerProfile;
import com.afrotransact.mcp.domain.DefaultPiiMasker;
import com.afrotransact.mcp.port.ApplicationsApi;
import com.afrotransact.mcp.port.CatalogApi;
import com.afrotransact.mcp.port.Downstream;
import com.afrotransact.mcp.port.OrdersApi;
import com.afrotransact.mcp.port.PaymentsApi;
import com.afrotransact.mcp.port.SellerApi;
import org.junit.jupiter.api.Test;

class StaffToolsServiceTest {

    private static final McpCaller STAFF = new McpCaller(new CallerProfile("staff-1", "console", "STAFF", null,
            Set.of("applications:review")), "token");

    private final ApplicationsApi applications = mock(ApplicationsApi.class);
    private final StaffToolsService tools = new StaffToolsServiceImpl(mock(ToolGuardService.class), applications,
            mock(CatalogApi.class), mock(PaymentsApi.class), mock(OrdersApi.class), mock(SellerApi.class),
            new DefaultPiiMasker());

    /**
     * seller's applications endpoint parses {@code status} as one of its application statuses; "IN_REVIEW" (a product status) is not one
     * of them and was a 422 on every default call.
     */
    @Test
    void theApplicationQueueDefaultsToAStatusSellerKnows() {
        when(applications.queue(any(), anyInt(), anyInt(), any())).thenReturn(Downstream.ok(Map.of()));
        tools.applicationsQueue(STAFF, null, null);
        verify(applications).queue(eq("PENDING_REVIEW"), eq(0), eq(25), eq("token"));
    }
}
