package com.afrotransact.catalog.it;

import java.util.List;

import com.afrotransact.catalog.port.SellingApprovals;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * The integration tests run without seller-service: every organisation may sell everything, so the tests that are not
 * about selling approvals are unaffected. The gate, the holds and the pause/restore sweeps are covered by
 * {@code SellingApprovalsIT}, which swaps this stub for a switchable one. Registered for the test classpath only.
 */
@AutoConfiguration
public class StubSellingApprovalsConfiguration {

    @Bean
    @Primary
    SellingApprovals everyoneMaySell() {
        return (orgId, query) -> new SellingApprovals.Status("SELL", null, List.of(), List.of());
    }
}
