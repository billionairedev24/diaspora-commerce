package com.afrotransact.inventory.adapter.out.module;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import com.afrotransact.catalog.api.LabelApi;
import com.afrotransact.inventory.port.UnitLabelClient;
import com.afrotransact.module.CallerPrincipal;
import com.afrotransact.module.CallerPrincipals;
import com.afrotransact.module.InProcessCalls;
import com.afrotransact.module.ModuleApiException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/** Inventory reads catalog labels as inventory and keeps the HTTP client's code parsing and retry rule. */
class InProcessUnitLabelClientTest {

    private static final CallerPrincipal INVENTORY = new CallerPrincipal("inventory", "inventory-service",
            Set.of("catalog:read"));

    private static UnitLabelClient client(LabelApi api) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("api", api);
        beans.addBean("callers", (CallerPrincipals) module -> INVENTORY);
        return new InProcessUnitLabelClient(beans.getBeanProvider(LabelApi.class),
                beans.getBeanProvider(CallerPrincipals.class), beans);
    }

    private static LabelApi.LabelOutput svg(String unitCode, String title) {
        return new LabelApi.LabelOutput("image/svg+xml", "l.svg", ("<svg><title>" + title + "</title></svg>")
                .getBytes(StandardCharsets.UTF_8), unitCode);
    }

    @Test
    void theCodeComesFromTheUnitCodeElseFromTheTitleAndManufacturerBarcodesHaveNone() {
        UnitLabelClient fromCode = client(new Fake() {
            @Override
            public LabelOutput unitLabel(String atin, String owner) {
                assertThat(InProcessCalls.require()).isEqualTo(INVENTORY);
                return svg(" atf3xk8p2q ", "ignored");
            }
        });
        assertThat(fromCode.unitLabelCode("AT00000000", "org")).contains("ATF3XK8P2Q");
        UnitLabelClient fromTitle = client(new Fake() {
            @Override
            public LabelOutput unitLabel(String atin, String owner) {
                return svg(null, "Rice ATF9ZZ8P2Q 1kg");
            }
        });
        assertThat(fromTitle.unitLabelCode("AT00000000", "org")).contains("ATF9ZZ8P2Q");
        UnitLabelClient manufacturer = client(new Fake() {
            @Override
            public LabelOutput unitLabel(String atin, String owner) {
                return svg(null, "0012345678905");
            }
        });
        assertThat(manufacturer.unitLabelCode("AT00000000", "org")).isEmpty();
    }

    @Test
    void retryableIsA5xxA401A403OrA429AndOtherRefusalsAreRejections() {
        for (int status : new int[] {503, 401, 403, 429}) {
            UnitLabelClient c = client(refusing(status));
            assertThatThrownBy(() -> c.render(List.of(), "PDF")).isInstanceOfSatisfying(
                    UnitLabelClient.LabelServiceException.class, e -> assertThat(e.retryable()).as("status %s", status)
                            .isTrue());
        }
        UnitLabelClient rejecting = client(refusing(422));
        assertThatThrownBy(() -> rejecting.render(List.of(), "PDF")).isInstanceOfSatisfying(
                UnitLabelClient.LabelServiceException.class, e -> assertThat(e.retryable()).isFalse());
        UnitLabelClient down = client(new Fake() {
            @Override
            public LabelOutput renderBatch(List<Label> labels, String format) {
                throw new IllegalStateException("down");
            }
        });
        assertThatThrownBy(() -> down.render(List.of(), "PDF")).isInstanceOfSatisfying(
                UnitLabelClient.LabelServiceException.class, e -> assertThat(e.retryable()).isTrue());
    }

    private static LabelApi refusing(int status) {
        return new Fake() {
            @Override
            public LabelOutput renderBatch(List<Label> labels, String format) {
                throw new ModuleApiException(status, "catalog.x", "refused");
            }
        };
    }

    private static class Fake implements LabelApi {
        @Override
        public LabelOutput unitLabel(String atin, String ownerOrgId) {
            return null;
        }

        @Override
        public LabelOutput renderBatch(List<Label> labels, String format) {
            return null;
        }
    }
}
