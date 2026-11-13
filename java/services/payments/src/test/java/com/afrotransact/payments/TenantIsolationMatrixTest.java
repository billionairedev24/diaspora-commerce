package com.afrotransact.payments;

import com.afrotransact.platform.test.tenant.TenantIsolationMatrix;
import org.junit.jupiter.api.Test;

class TenantIsolationMatrixTest {

    @Test
    void everyRouteHasAnIsolationClassAndItsCrossTenantTest() {
        TenantIsolationMatrix.assertComplete("com.afrotransact.payments", "tenant-isolation-matrix.txt");
    }
}
