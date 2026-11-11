package com.afrotransact.seller.application;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.afrotransact.platform.security.Actor;
import com.afrotransact.seller.domain.EscalationChain;
import com.afrotransact.seller.domain.EscalationTarget;
import com.afrotransact.seller.domain.ReviewSettings;
import com.afrotransact.seller.domain.SellerRuleViolation;
import com.afrotransact.seller.port.CallerProfileProvider;
import com.afrotransact.seller.port.ReviewSettingsRepository;
import com.afrotransact.seller.port.StaffDirectory;
import com.afrotransact.seller.port.StaffDirectory.StaffPerson;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ReviewSettingsServiceImpl implements ReviewSettingsService {

    private static final int MIN_REASON = 3;

    private final ReviewSettingsRepository repo;
    private final StaffDirectory directory;
    private final AccessGuard access;
    private final CallerProfileProvider profiles;
    private final Clock clock;

    ReviewSettingsServiceImpl(ReviewSettingsRepository repo, StaffDirectory directory, AccessGuard access,
                              CallerProfileProvider profiles, Clock clock) {
        this.repo = repo;
        this.directory = directory;
        this.access = access;
        this.profiles = profiles;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public Current current() {
        access.staffAny(SellerPermissions.APPLICATIONS_REVIEW, SellerPermissions.APPLICATIONS_APPROVE,
                SellerPermissions.APPLICATIONS_ASSIGN);
        return describe(repo.load());
    }

    @Override
    @Transactional
    public Current update(ReviewSettings next, String reason, String actorName) {
        Actor actor = access.staff(SellerPermissions.APPLICATIONS_ASSIGN);
        Map<String, String> problems = new LinkedHashMap<>(next.problems());
        if (reason == null || reason.trim().length() < MIN_REASON) {
            problems.put("reason", "Say why you are changing this");
        }
        checkTargets("reviewChain", next.reviewChain(), problems);
        checkTargets("approvalChain", next.approvalChain(), problems);
        if (!problems.isEmpty()) {
            throw new SellerRuleViolation("seller.invalid-review-settings", "Fix the highlighted settings", problems);
        }
        ReviewSettings before = repo.lock();
        String name = actorName != null && !actorName.isBlank() ? actorName.trim()
                : profiles.current().fullName() == null ? "Unknown user" : profiles.current().fullName();
        ReviewSettings saved = new ReviewSettings(next.reviewSlaHours(), next.approvalSlaHours(),
                next.dueSoonPercent(), next.unassignedAfterHours(), next.level2AfterHours(), next.autoAssign(),
                next.enforceAssignee(), next.sendBackToReviewer(), next.reviewChain(), next.approvalChain(),
                before.roundRobinCursor(), actor.subject(), name, Instant.now(clock));
        repo.save(saved);
        repo.audit(actor.subject(), name, reason.trim(), before, saved);
        return describe(saved);
    }

    /** Every target must exist: a role with enabled holders, people who are enabled staff. */
    private void checkTargets(String field, EscalationChain chain, Map<String, String> problems) {
        for (int level = 1; level <= 2; level++) {
            EscalationTarget t = chain.at(level);
            if (t.roleKey() != null && directory.withRole(t.roleKey()).isEmpty()) {
                problems.putIfAbsent(field, "Level " + level + ": nobody holds the role " + t.roleKey());
            }
            if (!t.userIds().isEmpty() && directory.people(t.userIds()).size() != t.userIds().size()) {
                problems.putIfAbsent(field, "Level " + level + ": one of the people is not an enabled staff member");
            }
        }
    }

    private Current describe(ReviewSettings s) {
        return new Current(s, names(s.reviewChain().level1()), names(s.reviewChain().level2()),
                names(s.approvalChain().level1()), names(s.approvalChain().level2()), repo.audit(10));
    }

    private List<String> names(EscalationTarget t) {
        return t.userIds().isEmpty() ? List.of() : directory.people(t.userIds()).stream().map(StaffPerson::name)
                .toList();
    }
}
