package com.afrotransact.catalog.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import com.afrotransact.catalog.domain.BusinessDays;
import com.afrotransact.catalog.domain.ChangeRequestStatus;
import com.afrotransact.catalog.domain.ExemptionStatus;
import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.port.ChangeRequestRepository;
import com.afrotransact.catalog.port.GtinExemptionRepository;
import com.afrotransact.catalog.port.ProductRepository;
import com.afrotransact.platform.security.PermissionChecker;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ReviewQueueServiceImpl implements ReviewQueueService {

    private final ChangeRequestRepository changeRequests;
    private final GtinExemptionRepository exemptions;
    private final ProductRepository products;
    private final PermissionChecker permissions;
    private final Clock clock;

    ReviewQueueServiceImpl(ChangeRequestRepository changeRequests, GtinExemptionRepository exemptions,
                           ProductRepository products, PermissionChecker permissions, Clock clock) {
        this.changeRequests = changeRequests;
        this.exemptions = exemptions;
        this.products = products;
        this.permissions = permissions;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public PageView<QueueItem> queue(Kind kind, int page, int size) {
        permissions.require(CatalogPermissions.MODERATE);
        int p = Math.max(page, 0);
        int s = Math.clamp(size, 1, 100);
        int window = (p + 1) * s;
        Instant now = Instant.now(clock);
        List<QueueItem> merged = new ArrayList<>();
        long total = 0;
        if (kind == null || kind == Kind.CHANGE_REQUEST) {
            ChangeRequestRepository.Page found = changeRequests.search(new ChangeRequestRepository.Query(
                    ChangeRequestStatus.PENDING, null, null, true, 0, window));
            total += found.total();
            found.items().forEach(r -> merged.add(new QueueItem(Kind.CHANGE_REQUEST, r.id(), r.atin(), title(r.productId()),
                    r.type().name(), r.requestedByOrg(), r.requestedAt(), r.slaDueAt(), overdue(r.slaDueAt(), now),
                    BusinessDays.hoursBetween(r.requestedAt(), now))));
        }
        if (kind == null || kind == Kind.GTIN_EXEMPTION) {
            GtinExemptionRepository.Page found = exemptions.search(ExemptionStatus.PENDING, 0, window);
            total += found.total();
            found.items().forEach(r -> merged.add(new QueueItem(Kind.GTIN_EXEMPTION, r.id(), r.productAtin(),
                    title(r.productId()), "No barcode", r.requestedByOrg(), r.requestedAt(), r.slaDueAt(),
                    overdue(r.slaDueAt(), now), BusinessDays.hoursBetween(r.requestedAt(), now))));
        }
        merged.sort(Comparator.comparing(QueueItem::createdAt).thenComparing(QueueItem::id));
        int from = Math.min(p * s, merged.size());
        return new PageView<>(merged.subList(from, Math.min(from + s, merged.size())), total, p, s);
    }

    private String title(UUID productId) {
        return products.findById(productId).map(Product::title).orElse(null);
    }

    private static boolean overdue(Instant due, Instant now) {
        return due != null && now.isAfter(due);
    }
}
