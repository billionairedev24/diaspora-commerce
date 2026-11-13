package com.afrotransact.retail.tenant;

import com.afrotransact.platform.test.tenant.TenantIsolationMatrix;
import org.junit.jupiter.api.Test;

/** Route drift guard: every HTTP route of this service has an isolation class in the matrix. */
class TenantIsolationMatrixTest {

    @Test
    void everyRouteIsClassified() {
        TenantIsolationMatrix.assertComplete("com.afrotransact.retail", "tenant-isolation-matrix.txt");
    }
}
