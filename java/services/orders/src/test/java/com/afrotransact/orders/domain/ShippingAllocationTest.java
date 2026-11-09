package com.afrotransact.orders.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The delivery charge allocation, against the vectors gateway-bff's estimate is tested with too. */
class ShippingAllocationTest {

    /** Shared with go/services/gateway-bff (shipping_alloc_test.go): the estimate and the order must agree. */
    static final Path VECTORS = Path.of("..", "..", "..", "tools", "checkout", "shipping-allocation-vectors.json");

    @Test
    void everySharedVectorAllocatesIdentically() throws Exception {
        JsonNode root = JsonMapper.builder().build().readTree(Files.readString(VECTORS));
        assertThat(root.path("cases").size()).isGreaterThanOrEqualTo(10);
        for (JsonNode c : root.path("cases")) {
            long[] weights = new long[c.path("weightsMinor").size()];
            long[] expected = new long[weights.length];
            for (int i = 0; i < weights.length; i++) {
                weights[i] = c.path("weightsMinor").get(i).asLong();
                expected[i] = c.path("expectedMinor").get(i).asLong();
            }
            long[] actual = ShippingAllocation.parse(c.path("method").asString())
                    .allocate(c.path("totalMinor").asLong(), weights);
            assertThat(actual).as(c.path("name").asString()).containsExactly(expected);
        }
    }

    @Test
    void anExemptOnlyPackageKeepsItsWholeDeliveryOnExemptLinesAndAMixedPackageSplitsIt() {
        long[] exemptOnly = ShippingAllocation.PROPORTIONAL.allocate(499, new long[] {2500, 1500});
        assertThat(exemptOnly[0] + exemptOnly[1]).isEqualTo(499);
        // mixed: the exempt line (1000) carries a quarter, the taxable line (3000) three quarters
        assertThat(ShippingAllocation.PROPORTIONAL.allocate(800, new long[] {1000, 3000})).containsExactly(200, 600);
        // the previous behaviour, kept as the rollback
        assertThat(ShippingAllocation.FIRST_LINE.allocate(800, new long[] {1000, 3000})).containsExactly(800, 0);
    }

    @Test
    void theAmountsAlwaysAddUpToTheCharge() {
        for (long total : new long[] {1, 7, 599, 1234, 99_999}) {
            long[] weights = {333, 1, 999, 250, 0, 17};
            assertThat(Arrays.stream(ShippingAllocation.PROPORTIONAL.allocate(total, weights)).sum())
                    .isEqualTo(total);
        }
    }

    @Test
    void theSettingDefaultsToProportionalAndRejectsUnknownMethods() {
        assertThat(ShippingAllocation.parse(null)).isEqualTo(ShippingAllocation.PROPORTIONAL);
        assertThat(ShippingAllocation.parse(" first_line ")).isEqualTo(ShippingAllocation.FIRST_LINE);
        assertThatThrownBy(() -> ShippingAllocation.parse("BY_WEIGHT")).isInstanceOf(IllegalArgumentException.class);
    }
}
