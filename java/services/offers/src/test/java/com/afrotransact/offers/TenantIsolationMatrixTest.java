package com.afrotransact.offers;

import com.afrotransact.platform.test.tenant.TenantIsolationMatrix;
import org.junit.jupiter.api.Test;

/** Route drift guard: every HTTP route is classified in tenant-isolation-matrix.txt. */
class TenantIsolationMatrixTest {

    @Test
    void everyRouteIsClassified() {
        TenantIsolationMatrix.assertComplete("com.afrotransact.offers", "tenant-isolation-matrix.txt");
    }
}
