package com.afrotransact.orders;

import com.afrotransact.platform.test.tenant.TenantIsolationMatrix;
import org.junit.jupiter.api.Test;

/** Route drift guard: every orders route is classified in tenant-isolation-matrix.txt. */
class TenantIsolationMatrixTest {

    @Test
    void everyRouteHasAnIsolationClass() {
        TenantIsolationMatrix.assertComplete("com.afrotransact.orders", "tenant-isolation-matrix.txt");
    }
}
