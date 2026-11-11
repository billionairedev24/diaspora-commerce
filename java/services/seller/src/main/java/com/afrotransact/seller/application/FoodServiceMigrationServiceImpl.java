package com.afrotransact.seller.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.seller.domain.ApplicationStatus;
import com.afrotransact.seller.domain.OrgMember;
import com.afrotransact.seller.domain.ReviewAction;
import com.afrotransact.seller.domain.SellingApplication;
import com.afrotransact.seller.domain.SellingRule;
import com.afrotransact.seller.domain.SellingScope;
import com.afrotransact.seller.domain.TimelineEntry;
import com.afrotransact.seller.port.BakeryClaims;
import com.afrotransact.seller.port.OrganisationRepository;
import com.afrotransact.seller.port.SellingApplicationRepository;
import com.afrotransact.seller.port.SellingApprovalRepository;
import com.afrotransact.seller.port.SellingRuleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

@Service
class FoodServiceMigrationServiceImpl implements FoodServiceMigrationService {

    private static final Logger LOG = LoggerFactory.getLogger(FoodServiceMigrationServiceImpl.class);
    static final String NOTE = "Carried over from the bakery answer on a listing. No permit is on file: ask the seller "
            + "for the food permit and the insurance before approving.";

    private final BakeryClaims claims;
    private final SellingRuleRepository rules;
    private final SellingApplicationRepository applications;
    private final SellingApprovalRepository approvals;
    private final SellingApprovalService approvalService;
    private final SellingReviewService review;
    private final OrganisationRepository orgs;
    private final SellerSettings settings;
    private final TransactionOperations tx;
    private final Clock clock;

    FoodServiceMigrationServiceImpl(BakeryClaims claims, SellingRuleRepository rules,
                                    SellingApplicationRepository applications, SellingApprovalRepository approvals,
                                    SellingApprovalService approvalService, SellingReviewService review,
                                    OrganisationRepository orgs, SellerSettings settings, TransactionOperations tx,
                                    Clock clock) {
        this.claims = claims;
        this.rules = rules;
        this.applications = applications;
        this.approvals = approvals;
        this.approvalService = approvalService;
        this.review = review;
        this.orgs = orgs;
        this.settings = settings;
        this.tx = tx;
        this.clock = clock;
    }

    @Override
    public int run() {
        SellingScope scope = SellingScope.capability("FOOD_SERVICE");
        Optional<SellingRule> rule = rules.find(scope);
        if (rule.isEmpty()) {
            return 0;
        }
        int created = 0;
        for (String orgId : claims.organisations()) {
            try {
                if (Boolean.TRUE.equals(tx.execute(s -> create(orgId, rule.get())))) {
                    created++;
                }
            } catch (RuntimeException e) {
                LOG.warn("Food service migration of {} failed; it is retried on the next run", orgId, e);
            }
        }
        if (created > 0) {
            LOG.info("Created {} food service application(s) from the bakery answer", created);
        }
        return created;
    }

    private boolean create(String orgId, SellingRule rule) {
        if (settings.isRetail(orgId) || orgs.findById(orgId).isEmpty()) {
            return false;
        }
        if (approvals.find(orgId, rule.scope()).isPresent() || !applications.latest(orgId, List.of(rule.scope()))
                .isEmpty()) {
            return false; // already asked, approved or decided: never a second application from the old flag
        }
        String owner = orgs.members(orgId).stream().filter(OrgMember::isOwner).map(OrgMember::userId).findFirst()
                .orElse(null);
        if (owner == null) {
            return false;
        }
        Instant now = Instant.now(clock);
        SellingApplication app = SellingApplication.submit(Ids.sellingApplication(), applications.nextReference(),
                orgId, owner, null, rule, "NEW", "MIGRATION", List.of(), NOTE, now);
        if (!applications.insert(app)) {
            return false;
        }
        applications.appendTimeline(new TimelineEntry(UUID.randomUUID(), app.id(), ReviewAction.SUBMITTED, null,
                ApplicationStatus.PENDING_REVIEW, "system", null, NOTE, List.of(), now, "Bakery flag migration",
                null));
        approvalService.recordSubmitted(app);
        review.opened(app);
        return true;
    }
}
