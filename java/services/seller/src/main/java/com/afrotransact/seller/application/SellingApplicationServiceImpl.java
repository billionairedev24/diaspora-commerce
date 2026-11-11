package com.afrotransact.seller.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.web.error.NotFoundException;
import com.afrotransact.seller.application.SellingViews.SellingMyApplication;
import com.afrotransact.seller.application.SellingViews.SellingDocumentOnFile;
import com.afrotransact.seller.application.SellingViews.SellingDocumentSlot;
import com.afrotransact.seller.domain.ApplicationStatus;
import com.afrotransact.seller.domain.ReviewAction;
import com.afrotransact.seller.domain.SellerRuleViolation;
import com.afrotransact.seller.domain.SellerStateConflict;
import com.afrotransact.seller.domain.SellingApplication;
import com.afrotransact.seller.domain.SellingApproval;
import com.afrotransact.seller.domain.SellingApprovalStatus;
import com.afrotransact.seller.domain.SellingDocument;
import com.afrotransact.seller.domain.SellingDocumentKind;
import com.afrotransact.seller.domain.SellingDocuments;
import com.afrotransact.seller.domain.SellingLevel;
import com.afrotransact.seller.domain.SellingRule;
import com.afrotransact.seller.domain.SellingScope;
import com.afrotransact.seller.domain.SellingScopeType;
import com.afrotransact.seller.domain.TimelineEntry;
import com.afrotransact.seller.port.CallerProfileProvider;
import com.afrotransact.seller.port.SellingApplicationRepository;
import com.afrotransact.seller.port.SellingApprovalRepository;
import com.afrotransact.seller.port.SellingRuleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class SellingApplicationServiceImpl implements SellingApplicationService {

    private static final Set<String> APPLIERS = Set.of("owner", "admin", "catalog");
    private static final int MAX_NOTE = 1000;
    private static final int MAX_DOCUMENTS = 20;

    private final SellingRuleRepository rules;
    private final SellingApprovalRepository approvals;
    private final SellingApplicationRepository applications;
    private final SellingApprovalService approvalService;
    private final SellingReviewService review;
    private final AccessGuard access;
    private final CallerProfileProvider profiles;
    private final SellerSettings settings;
    private final Clock clock;

    SellingApplicationServiceImpl(SellingRuleRepository rules, SellingApprovalRepository approvals,
                                  SellingApplicationRepository applications, SellingApprovalService approvalService,
                                  SellingReviewService review, AccessGuard access, CallerProfileProvider profiles,
                                  SellerSettings settings, Clock clock) {
        this.rules = rules;
        this.approvals = approvals;
        this.applications = applications;
        this.approvalService = approvalService;
        this.review = review;
        this.access = access;
        this.profiles = profiles;
        this.settings = settings;
        this.clock = clock;
    }

    @Override
    @Transactional
    public SellingMyApplication apply(String orgId, ApplyCommand cmd) {
        Actor actor = access.orgRole(orgId, APPLIERS, null);
        if (settings.isRetail(orgId)) {
            throw new SellerStateConflict("seller.selling-not-needed", "AfroTransact Retail sells everywhere");
        }
        SellingScope scope = scope(cmd.scopeType(), cmd.scopeKey());
        SellingRule rule = rules.find(scope).filter(SellingRule::active).orElseThrow(() -> new NotFoundException(
                "seller.selling-rule-not-found", "There is no approval for that"));
        if (rule.level() != SellingLevel.APPLY) {
            throw new SellerRuleViolation("seller.selling-not-needed", rule.level() == SellingLevel.SELL
                    ? "You can sell here without approval" : "This is not available, so there is nothing to apply for",
                    Map.of("scopeKey", "Not an approval"));
        }
        if (rule.capability() != null && rule.scope().type() != SellingScopeType.CAPABILITY) {
            throw new SellerRuleViolation("seller.selling-use-capability",
                    "Apply for the " + rule.capability().toLowerCase(Locale.ROOT).replace('_', ' ')
                    + " capability: one approval covers this category", Map.of("scopeKey", "Apply for the capability"));
        }
        Optional<SellingApplication> open = applications.findOpen(orgId, scope);
        if (open.isPresent()) {
            return view(open.get(), rule);
        }
        Instant now = Instant.now(clock);
        LocalDate today = now.atZone(ZoneOffset.UTC).toLocalDate();
        SellingApproval approval = approvals.find(orgId, scope).orElse(null);
        if (approval != null && approval.status() == SellingApprovalStatus.NOT_AVAILABLE
                && (approval.reapplyAfter() == null || today.isBefore(approval.reapplyAfter()))) {
            throw new SellerStateConflict("seller.selling-not-available", rule.label() + " is not available to you."
                    + " This cannot be appealed." + (approval.reapplyAfter() == null ? "" : " You can apply again from "
                    + approval.reapplyAfter() + "."));
        }
        List<SellingDocument> docs = documents(cmd.documents());
        SellingDocuments.require(rule.documents(), docs, today);
        String kind = approval != null && approval.valid(now) ? "RENEWAL" : "NEW";
        SellingApplication app = SellingApplication.submit(Ids.sellingApplication(), applications.nextReference(),
                orgId, actor.subject(), profiles.current().fullName(), rule, kind, "SELLER", docs, note(cmd.note()),
                now);
        if (!applications.insert(app)) {
            return view(applications.findOpen(orgId, scope).orElseThrow(), rule);
        }
        applications.appendTimeline(new TimelineEntry(UUID.randomUUID(), app.id(), ReviewAction.SUBMITTED, null,
                ApplicationStatus.PENDING_REVIEW, actor.subject(), null, app.note(), List.of(), now,
                app.applicantName(), null));
        approvalService.recordSubmitted(app);
        review.opened(app);
        return view(app, rule);
    }

    @Override
    @Transactional
    public SellingMyApplication resubmit(String orgId, String applicationId, ResubmitCommand cmd) {
        access.orgRole(orgId, APPLIERS, null);
        SellingApplication current = applications.lockById(applicationId).filter(a -> a.orgId().equals(orgId))
                .orElseThrow(() -> notFound(applicationId));
        SellingRule rule = rules.find(current.scope()).orElseThrow(() -> notFound(applicationId));
        Instant now = Instant.now(clock);
        List<SellingDocument> docs = cmd.documents().isEmpty() ? current.documents() : documents(cmd.documents());
        SellingDocuments.require(rule.documents(), docs, now.atZone(ZoneOffset.UTC).toLocalDate());
        SellingApplication next = applications.update(current.resubmit(docs, note(cmd.note()), now));
        applications.appendTimeline(new TimelineEntry(UUID.randomUUID(), next.id(), ReviewAction.RESUBMITTED,
                ApplicationStatus.INFO_REQUESTED, ApplicationStatus.PENDING_REVIEW, next.applicantUserId(), null,
                next.note(), List.of(), now, next.applicantName(), null));
        approvalService.recordSubmitted(next);
        review.opened(next);
        return view(next, rule);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SellingMyApplication> list(String orgId) {
        access.orgRoleOrStaff(orgId, SellingStatusServiceImpl.MEMBERS, SellerPermissions.APPLICATIONS_REVIEW,
                SellerPermissions.APPLICATIONS_APPROVE);
        return applications.forOrg(orgId).stream().map(a -> view(a, rules.find(a.scope()).orElse(null))).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public SellingMyApplication get(String orgId, String applicationId) {
        access.orgRoleOrStaff(orgId, SellingStatusServiceImpl.MEMBERS, SellerPermissions.APPLICATIONS_REVIEW,
                SellerPermissions.APPLICATIONS_APPROVE);
        SellingApplication a = applications.findById(applicationId).filter(x -> x.orgId().equals(orgId))
                .orElseThrow(() -> notFound(applicationId));
        return view(a, rules.find(a.scope()).orElse(null));
    }

    // ---------------------------------------------------------------- helpers

    private static SellingScope scope(String type, String key) {
        try {
            SellingScopeType t = SellingScopeType.valueOf(type == null ? "" : type.trim().toUpperCase(Locale.ROOT));
            return new SellingScope(t, key == null ? "" : key.trim());
        } catch (IllegalArgumentException e) {
            throw new SellerRuleViolation("seller.invalid-selling-scope", "Choose what you are applying for",
                    Map.of("scopeType", "Unknown scope"));
        }
    }

    private static List<SellingDocument> documents(List<DocumentInput> inputs) {
        if (inputs.size() > MAX_DOCUMENTS) {
            throw new SellerRuleViolation("seller.selling-documents-invalid", "At most " + MAX_DOCUMENTS
                    + " documents", Map.of("documents", "Too many"));
        }
        return inputs.stream().map(d -> {
            SellingDocumentKind kind;
            try {
                kind = SellingDocumentKind.valueOf(d.kind() == null ? "" : d.kind().trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new SellerRuleViolation("seller.selling-documents-invalid", "Unknown document type "
                        + d.kind(), Map.of("documents", "Unknown document type"));
            }
            return new SellingDocument(UUID.randomUUID().toString(), kind, d.assetId(), d.fileName(), d.issuedOn(),
                    d.expiresOn(), d.namesAfroTransact(), d.note());
        }).toList();
    }

    private static String note(String note) {
        if (note == null || note.isBlank()) {
            return null;
        }
        if (note.strip().length() > MAX_NOTE) {
            throw new SellerRuleViolation("seller.invalid-note", "Keep the note under " + MAX_NOTE + " characters",
                    Map.of("note", "Too long"));
        }
        return note.strip();
    }

    private static NotFoundException notFound(String id) {
        return new NotFoundException("seller.selling-application-not-found", "Application " + id + " not found");
    }

    /** The seller's view: statuses in plain words, nothing internal (no reviewer, no flags, no step). */
    static SellingMyApplication view(SellingApplication a, SellingRule rule) {
        String words;
        String message;
        switch (a.status()) {
            case INFO_REQUESTED -> {
                words = "Needs your attention";
                message = "We need more from you: " + String.join(", ", a.requestedItems())
                        + (a.decisionNote() == null ? "" : ". " + a.decisionNote());
            }
            case APPROVED -> {
                words = "Approved";
                message = "You are approved. Listings in " + a.scopeLabel() + " go live after the usual checks.";
            }
            case REJECTED -> {
                words = a.disqualified() ? "Not available" : "Not approved";
                message = a.disqualified()
                        ? "This cannot be appealed" + (a.reapplyAfter() == null ? "" : ". You can apply again from "
                        + a.reapplyAfter()) + (a.decisionNote() == null ? "" : ". " + a.decisionNote())
                        : "Not approved" + (a.decisionNote() == null ? "" : ": " + a.decisionNote())
                        + ". You can apply again.";
            }
            default -> {
                words = "With our team";
                message = "We aim to answer within 2 business days.";
            }
        }
        List<SellingDocumentSlot> required = rule == null ? List.of() : rule.documents().stream().map(SellingDocumentSlot::of).toList();
        return new SellingMyApplication(a.id(), a.reference(), a.scope().type().name(), a.scope().key(), a.scopeLabel(),
                a.kind(), a.status().name(), words, message, a.requestedItems(), a.disqualified(), a.reapplyAfter(),
                a.documents().stream().map(SellingDocumentOnFile::of).toList(), required, a.submittedAt(), a.updatedAt());
    }
}
