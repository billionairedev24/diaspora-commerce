package com.afrotransact.mcp;

import com.afrotransact.platform.test.tenant.TenantIsolationMatrix;
import org.junit.jupiter.api.Test;

/** Route drift guard: every route is classified in tenant-isolation-matrix.txt. */
class TenantIsolationMatrixTest {

    @Test
    void everyRouteHasAnIsolationClass() {
        TenantIsolationMatrix.assertComplete("com.afrotransact.mcp", "tenant-isolation-matrix.txt");
    }
}
