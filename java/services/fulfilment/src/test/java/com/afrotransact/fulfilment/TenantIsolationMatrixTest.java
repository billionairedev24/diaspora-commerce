package com.afrotransact.fulfilment;

import com.afrotransact.platform.test.tenant.TenantIsolationMatrix;
import org.junit.jupiter.api.Test;

/** Route drift guard: every fulfilment route is classified in tenant-isolation-matrix.txt. */
class TenantIsolationMatrixTest {

    @Test
    void everyRouteHasAnIsolationClass() {
        TenantIsolationMatrix.assertComplete("com.afrotransact.fulfilment", "tenant-isolation-matrix.txt");
    }
}
