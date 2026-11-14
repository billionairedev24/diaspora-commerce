package com.afrotransact.fulfilment;

import com.afrotransact.platform.test.tenant.TenantIsolationMatrix;
import org.junit.jupiter.api.Test;

/** Route drift guard: every fulfilment route is classified in tenant-isolation-matrix.txt. */
class TenantIsolationMatrixTest {

    private static final String DRIVER_APP_FLAG = "at.fulfilment.driver-app.enabled";

    @Test
    void everyRouteHasAnIsolationClass() {
        // The driver routes only exist while the dormant driver app is enabled; the matrix must
        // still classify them so that re-enabling it cannot ship unclassified routes.
        String previous = System.getProperty(DRIVER_APP_FLAG);
        System.setProperty(DRIVER_APP_FLAG, "true");
        try {
            TenantIsolationMatrix.assertComplete("com.afrotransact.fulfilment", "tenant-isolation-matrix.txt");
        } finally {
            if (previous == null) {
                System.clearProperty(DRIVER_APP_FLAG);
            } else {
                System.setProperty(DRIVER_APP_FLAG, previous);
            }
        }
    }
}
