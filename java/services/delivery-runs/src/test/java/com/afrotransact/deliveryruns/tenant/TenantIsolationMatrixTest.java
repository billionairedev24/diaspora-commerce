package com.afrotransact.deliveryruns.tenant;

import com.afrotransact.platform.test.tenant.TenantIsolationMatrix;
import org.junit.jupiter.api.Test;

/** Route drift guard: every delivery-runs route is declared in tenant-isolation-matrix.txt with its class. */
class TenantIsolationMatrixTest {

    @Test
    void everyRouteIsClassified() {
        TenantIsolationMatrix.assertComplete("com.afrotransact.deliveryruns", "tenant-isolation-matrix.txt");
    }
}
