package com.afrotransact.deliveryruns.it;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.afrotransact.deliveryruns.domain.PreorderLine;
import com.afrotransact.deliveryruns.domain.PreorderResolver.ProductFacts;
import com.afrotransact.deliveryruns.port.CatalogGateway;
import com.afrotransact.deliveryruns.port.MediaGateway;
import com.afrotransact.deliveryruns.port.OrdersGateway;
import com.afrotransact.deliveryruns.port.ProductSearch;
import com.afrotransact.deliveryruns.port.UpstreamRejectedException;

/** In-memory media, catalog and orders services for the pre-order ITs. */
public class FakeUpstreams implements MediaGateway, CatalogGateway, OrdersGateway, ProductSearch {

    private final Map<UUID, MediaAsset> assets = new ConcurrentHashMap<>();
    private final Map<String, ProductFacts> products = new ConcurrentHashMap<>();
    private final List<LatePreorderRequest> lateRequests = new ArrayList<>();
    private final AtomicInteger orderSeq = new AtomicInteger(5000);
    private volatile String rejectCode;

    /** A READY image of the given size. */
    UUID image(int width, int height) {
        UUID id = UUID.randomUUID();
        assets.put(id, new MediaAsset(id, "https://cdn.test/" + id + ".jpg", width, height, "READY", "image/jpeg"));
        return id;
    }

    UUID pendingImage() {
        UUID id = UUID.randomUUID();
        assets.put(id, new MediaAsset(id, null, 0, 0, "PENDING", "image/jpeg"));
        return id;
    }

    void product(String atin, String category, boolean shipsNow) {
        products.put(atin, new ProductFacts(atin, category, shipsNow, true));
    }

    void rejectLateOrders(String code) {
        rejectCode = code;
    }

    synchronized List<LatePreorderRequest> lateRequests() {
        return List.copyOf(lateRequests);
    }

    @Override
    public Optional<MediaAsset> asset(UUID id) {
        return Optional.ofNullable(assets.get(id));
    }

    @Override
    public Map<String, ProductFacts> facts(Collection<String> atins) {
        Map<String, ProductFacts> out = new ConcurrentHashMap<>();
        atins.forEach(a -> {
            if (products.containsKey(a)) {
                out.put(a, products.get(a));
            }
        });
        return out;
    }

    @Override
    public synchronized LatePreorderResult placeLatePreorder(LatePreorderRequest r) {
        if (rejectCode != null) {
            throw new UpstreamRejectedException(rejectCode, "Card declined", 402);
        }
        lateRequests.add(r);
        long items = r.items().stream().mapToLong(i -> 1000L * i.qty()).sum();
        String orderId = UUID.randomUUID().toString();
        List<PreorderLine> lines = r.items().stream()
                .map(i -> new PreorderLine(i.atin(), "Product " + i.atin(), "seller-1", i.qty())).toList();
        return new LatePreorderResult(orderId, "AT-" + orderSeq.incrementAndGet(),
                r.payment().name().equals("PAYMENT_LINK") ? "AWAITING_PAYMENT" : "PLACED",
                items + 799, "USD",
                r.payment().name().equals("PAYMENT_LINK") ? "https://pay.test/l/" + orderId : null, null,
                UUID.randomUUID().toString(), "Ada Okafor", "+1 806 555 0101", "ada@example.test", "1902 19th St",
                "Lubbock", "TX", "79401", lines);
    }

    @Override
    public List<Product> inCategories(Set<String> categories, String market, int limit) {
        Set<String> wanted = new HashSet<>();
        categories.forEach(c -> wanted.add(c.toLowerCase(Locale.ROOT)));
        return products.values().stream()
                .filter(p -> p.category() != null && wanted.contains(p.category().toLowerCase(Locale.ROOT)))
                .map(p -> new Product(p.atin(), "Product " + p.atin(), 1000, "USD", "Seller One"))
                .sorted(Comparator.comparing(Product::atin)).limit(limit).toList();
    }
}
