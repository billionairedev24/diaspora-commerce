package com.afrotransact.taxreturns.it;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.afrotransact.platform.security.ActorResolver;
import com.afrotransact.taxreturns.domain.returns.ItemCondition;
import com.afrotransact.taxreturns.domain.returns.RefundLineAmount;
import com.afrotransact.taxreturns.domain.returns.ReturnLabel;
import com.afrotransact.taxreturns.port.CarrierLabels;
import com.afrotransact.taxreturns.port.InventoryGateway;
import com.afrotransact.taxreturns.port.PaymentsGateway;
import com.afrotransact.taxreturns.port.ProductFacts;
import com.afrotransact.taxreturns.port.UpstreamException;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** In-memory payments, inventory, carrier-gateway and catalog for integration tests (no network). */
@TestConfiguration(proxyBeanMethods = false)
class StubUpstreams {

    @Bean
    TestActors testActors() {
        return new TestActors();
    }

    @Bean
    @Primary
    ActorResolver actorResolver(TestActors actors) {
        return actors;
    }

    @Bean
    @Primary
    FakePayments fakePayments() {
        return new FakePayments();
    }

    @Bean
    @Primary
    FakeInventory fakeInventory() {
        return new FakeInventory();
    }

    @Bean
    @Primary
    FakeCarrier fakeCarrier() {
        return new FakeCarrier();
    }

    @Bean
    @Primary
    ProductFacts fakeProductFacts() {
        Map<String, ProductFacts.Facts> known = Map.of(
                "ATJOLLOF01", new ProductFacts.Facts("ATJOLLOF01", "pantry", true),
                "ATDRESS001", new ProductFacts.Facts("ATDRESS001", "dresses", false),
                "ATPILLOW01", new ProductFacts.Facts("ATPILLOW01", "home-decor", false));
        return atins -> {
            Map<String, ProductFacts.Facts> out = new ConcurrentHashMap<>();
            for (String a : atins) {
                if (known.containsKey(a)) {
                    out.put(a, known.get(a));
                }
            }
            return out;
        };
    }

    /** Payments: charged lines per order; records refunds; can fail the next call. */
    static final class FakePayments implements PaymentsGateway {

        final Map<UUID, ChargedPayment> payments = new ConcurrentHashMap<>();
        final List<Call> refunds = new CopyOnWriteArrayList<>();
        final AtomicInteger seq = new AtomicInteger();
        volatile int failNextWithStatus;

        record Call(UUID paymentId, List<RefundLineAmount> lines, String reason, String idempotencyKey) {
        }

        @Override
        public Optional<ChargedPayment> paymentForOrder(UUID orderId) {
            return Optional.ofNullable(payments.get(orderId));
        }

        @Override
        public IssuedRefund refund(UUID paymentId, List<RefundLineAmount> lines, String reason, String key) {
            if (failNextWithStatus != 0) {
                int status = failNextWithStatus;
                failNextWithStatus = 0;
                throw new UpstreamException("payments", status, "payments.test", "payments failed (" + status + ")",
                        null);
            }
            for (Call c : refunds) {
                if (c.idempotencyKey().equals(key)) {
                    return new IssuedRefund("pref-replay-" + key, "SUCCEEDED", 0);
                }
            }
            refunds.add(new Call(paymentId, List.copyOf(lines), reason, key));
            return new IssuedRefund("pref-" + seq.incrementAndGet(), "SUCCEEDED",
                    lines.stream().mapToLong(RefundLineAmount::totalMinor).sum());
        }
    }

    /** Inventory: records return postings. */
    static final class FakeInventory implements InventoryGateway {

        final List<String> postings = new CopyOnWriteArrayList<>();

        @Override
        public void acceptReturn(String orderId, String sku, int quantity, ItemCondition condition, String reason,
                                 String idempotencyKey) {
            postings.add(sku + ":" + quantity + ":" + condition + ":" + idempotencyKey);
        }
    }

    /** Carrier-gateway: issues a label per idempotency key. */
    static final class FakeCarrier implements CarrierLabels {

        final List<LabelRequest> requests = new CopyOnWriteArrayList<>();
        final Map<String, ReturnLabel> byKey = new ConcurrentHashMap<>();

        @Override
        public ReturnLabel buyReturnLabel(LabelRequest request, String idempotencyKey) {
            return byKey.computeIfAbsent(idempotencyKey, k -> {
                requests.add(request);
                return new ReturnLabel(UUID.randomUUID().toString(), "ups", "1ZRET" + (100_000 + requests.size()),
                        "https://labels.example/" + request.reference() + ".pdf");
            });
        }
    }

    static List<String> sorted(Collection<String> values) {
        List<String> out = new ArrayList<>(values);
        out.sort(String::compareTo);
        return out;
    }
}
