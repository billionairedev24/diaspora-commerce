package com.afrotransact.seller.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.seller.domain.Application;
import com.afrotransact.seller.domain.ApplicationCase;
import com.afrotransact.seller.domain.ApplicationData;
import com.afrotransact.seller.domain.AutoAssignMode;
import com.afrotransact.seller.domain.EscalationChain;
import com.afrotransact.seller.domain.EscalationTarget;
import com.afrotransact.seller.domain.OrgKind;
import com.afrotransact.seller.domain.ReviewAction;
import com.afrotransact.seller.domain.ReviewSettings;
import com.afrotransact.seller.domain.ReviewStage;
import com.afrotransact.seller.domain.SellerForbidden;
import com.afrotransact.seller.domain.SellerRuleViolation;
import com.afrotransact.seller.domain.SellerStateConflict;
import com.afrotransact.seller.domain.TimelineEntry;
import com.afrotransact.seller.port.ApplicationCaseRepository;
import com.afrotransact.seller.port.ApplicationRepository;
import com.afrotransact.seller.port.CallerProfile;
import com.afrotransact.seller.port.CallerProfileProvider;
import com.afrotransact.seller.port.ReviewSettingsRepository;
import com.afrotransact.seller.port.StaffDirectory;
import com.afrotransact.seller.port.StaffDirectory.StaffPerson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Assignment eligibility, stage transitions and the escalation check, on a fixed clock with in-memory ports. */
class ReviewAssignmentServiceImplTest {

    private static final Instant T0 = Instant.parse("2026-10-12T08:00:00Z");

    private final ApplicationRepository apps = mock(ApplicationRepository.class);
    private final AccessGuard access = mock(AccessGuard.class);
    private final DomainEventPublisher events = mock(DomainEventPublisher.class);
    private final CallerProfileProvider profiles = mock(CallerProfileProvider.class);
    private final Map<String, ApplicationCase> store = new HashMap<>();
    private final Map<String, Integer> load = new HashMap<>();
    private final MemSettings settingsRepo = new MemSettings();
    private final MemDirectory directory = new MemDirectory();
    private Instant now = T0;
    private final Clock clock = new Clock() {
        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    };
    private ReviewAssignmentServiceImpl service;

    private final StaffPerson rita = new StaffPerson("rita", "Rita Okoro");
    private final StaffPerson amina = new StaffPerson("amina", "Amina Bello");
    private final StaffPerson adam = new StaffPerson("adam", "Adam Ade");
    private final StaffPerson sue = new StaffPerson("sue", "Sue Supervisor");
    private final Application submitted = Application.start("a1", "APP-1", "org1", "applicant", OrgKind.SELLER,
            ApplicationData.empty(), Set.of(), T0).submit(T0);

    @BeforeEach
    void setUp() {
        ApplicationCaseRepository cases = new ApplicationCaseRepository() {
            @Override
            public Optional<ApplicationCase> find(String id) {
                return Optional.ofNullable(store.get(id));
            }

            @Override
            public ApplicationCase save(ApplicationCase c) {
                store.put(c.applicationId(), c);
                return c;
            }

            @Override
            public List<String> escalationCandidates(int limit) {
                return new ArrayList<>(store.keySet());
            }

            @Override
            public Map<String, Integer> openLoad(ReviewStage stage) {
                return load;
            }
        };
        service = new ReviewAssignmentServiceImpl(apps, cases, settingsRepo, directory, access, profiles, events,
                clock);
        directory.review = List.of(rita, amina, adam);
        directory.approve = List.of(rita, amina, adam);
        directory.supervisors = List.of(sue);
        when(apps.lockById("a1")).thenReturn(Optional.of(submitted));
        when(apps.findById("a1")).thenReturn(Optional.of(submitted));
        when(profiles.current()).thenReturn(CallerProfile.empty());
        store.put("a1", ApplicationCase.idle("a1", T0).enter(ReviewStage.REVIEW, T0));
    }

    private Actor actor(String id, String... perms) {
        Actor a = new Actor(id, PrincipalType.STAFF, Optional.empty(), Set.of(), Set.of(perms));
        when(access.staffAny(any(String[].class))).thenReturn(a);
        return a;
    }

    // ---------------------------------------------------------------- claim / assign / release

    @Test
    void anEligibleReviewerClaimsAnUnassignedCaseAndTheTimelineNamesThem() {
        actor("amina", "applications:review");
        ApplicationCaseView v = service.claim("a1", "Amina Bello");
        assertThat(v.assigneeName()).isEqualTo("Amina Bello");
        assertThat(v.holder()).isEqualTo("with Amina Bello");
        assertThat(v.mine()).isTrue();
        ArgumentCaptor<TimelineEntry> t = ArgumentCaptor.forClass(TimelineEntry.class);
        verify(apps).appendTimeline(t.capture());
        assertThat(t.getValue().action()).isEqualTo(ReviewAction.ASSIGNED);
        assertThat(t.getValue().actorName()).isEqualTo("Amina Bello");
        assertThat(t.getValue().note()).contains("Amina Bello");
    }

    @Test
    void claimingAgainChangesNothing() {
        actor("amina", "applications:review");
        service.claim("a1", "Amina Bello");
        service.claim("a1", "Amina Bello");
        verify(apps, times(1)).appendTimeline(any());
    }

    @Test
    void aCaseWithSomeoneElseCannotBeClaimedAndNamesWhoHasIt() {
        actor("amina", "applications:review");
        service.claim("a1", "Amina Bello");
        actor("adam", "applications:review");
        assertThatThrownBy(() -> service.claim("a1", "Adam Ade")).isInstanceOf(SellerStateConflict.class)
                .hasMessageContaining("Amina Bello");
    }

    @Test
    void claimNeedsTheStagePermissionNotTheApplicantAndNotTheFirstReviewerForApproval() {
        actor("rita", "applications:approve");
        assertThatThrownBy(() -> service.claim("a1", "Rita")).isInstanceOf(SellerRuleViolation.class)
                .hasMessageContaining("applications:review");
        actor("applicant", "applications:review");
        assertThatThrownBy(() -> service.claim("a1", "Applicant")).hasMessageContaining("applicant");

        Application awaiting = submitted.review(ReviewAction.APPROVE, "rita", "OK", null, null, T0);
        when(apps.lockById("a1")).thenReturn(Optional.of(awaiting));
        store.put("a1", ApplicationCase.idle("a1", T0).enter(ReviewStage.APPROVAL, T0));
        actor("rita", "applications:review", "applications:approve");
        assertThatThrownBy(() -> service.claim("a1", "Rita")).hasMessageContaining("different person");
        actor("adam", "applications:approve");
        assertThat(service.claim("a1", "Adam Ade").assigneeName()).isEqualTo("Adam Ade");
    }

    @Test
    void aSupervisorAssignsToAnEligiblePersonOnly() {
        actor("sue", "applications:assign");
        ApplicationCaseView v = service.assign("a1", "amina", "please take this", "Sue Supervisor");
        assertThat(v.assigneeName()).isEqualTo("Amina Bello");
        assertThat(v.canAssign()).isTrue();
        assertThat(v.mine()).isFalse();
        verify(events).publish(eq(SellerEvents.APPLICATION_ASSIGNED), any(), eq("a1"), any());
        assertThatThrownBy(() -> service.assign("a1", "sue", null, "Sue")).isInstanceOf(SellerRuleViolation.class)
                .hasMessageContaining("applications:review");
        assertThatThrownBy(() -> service.assign("a1", "applicant", null, "Sue"))
                .isInstanceOf(SellerRuleViolation.class);
    }

    @Test
    void aReviewerWithoutSupervisorRightsCannotAssignOthersAtReview() {
        actor("amina", "applications:review");
        assertThatThrownBy(() -> service.assign("a1", "adam", null, "Amina")).isInstanceOf(SellerForbidden.class);
    }

    @Test
    void takeOverMovesTheCaseToTheTakerAndNamesTheFormerHolder() {
        actor("amina", "applications:review");
        service.claim("a1", "Amina Bello");
        actor("rita", "applications:assign", "applications:review");
        ApplicationCaseView v = service.assign("a1", "rita", null, "Rita Okoro");
        assertThat(v.assigneeName()).isEqualTo("Rita Okoro");
        ArgumentCaptor<TimelineEntry> t = ArgumentCaptor.forClass(TimelineEntry.class);
        verify(apps, times(2)).appendTimeline(t.capture());
        assertThat(t.getAllValues().getLast().note()).contains("Taken over by Rita Okoro")
                .contains("was with Amina Bello");
    }

    @Test
    void releaseReturnsTheCaseToThePoolAndRepeatsAreQuiet() {
        actor("amina", "applications:review");
        service.claim("a1", "Amina Bello");
        ApplicationCaseView v = service.release("a1", null, "Amina Bello");
        assertThat(v.holder()).isEqualTo("any reviewer");
        service.release("a1", null, "Amina Bello");
        verify(apps, times(2)).appendTimeline(any());
    }

    // ---------------------------------------------------------------- transitions

    @Test
    void afterStepOneTheCaseGoesToTheApprovalPoolUnassignedAndApproversAreNotified() {
        actor("amina", "applications:review");
        service.claim("a1", "Amina Bello");
        Application after = submitted.review(ReviewAction.APPROVE, "amina", "OK", null, null, T0);
        now = T0.plusSeconds(3600);
        service.transitioned(submitted, after, ReviewAction.APPROVE, "amina", "Amina Bello", null);
        ApplicationCase c = store.get("a1");
        assertThat(c.stage()).isEqualTo(ReviewStage.APPROVAL);
        assertThat(c.assigned()).isFalse();
        assertThat(c.stageEnteredAt()).isEqualTo(now);
        ArgumentCaptor<AssignmentEventPayload> p = ArgumentCaptor.forClass(AssignmentEventPayload.class);
        verify(events).publish(eq(SellerEvents.APPLICATION_ASSIGNED), any(), eq("a1"), p.capture());
        assertThat(p.getValue().reason()).isEqualTo("READY_FOR_APPROVAL");
        assertThat(p.getValue().recipientUserIds()).containsExactlyInAnyOrder("amina".equals("x") ? "" : "rita",
                "adam").doesNotContain("amina", "applicant");
    }

    @Test
    void sendBackReturnsToTheReviewPoolRecordsWhoAndWhyAndNotifiesThePreviousReviewer() {
        Application awaiting = submitted.review(ReviewAction.APPROVE, "amina", "OK", null, null, T0);
        Application back = awaiting.decide(ReviewAction.SEND_BACK, "adam", "FLAW", null, T0);
        store.put("a1", ApplicationCase.idle("a1", T0).enter(ReviewStage.APPROVAL, T0));
        service.transitioned(awaiting, back, ReviewAction.SEND_BACK, "adam", "Adam Ade", "The TIN was not checked");
        ApplicationCase c = store.get("a1");
        assertThat(c.stage()).isEqualTo(ReviewStage.REVIEW);
        assertThat(c.assigned()).isFalse();
        assertThat(c.returnCount()).isEqualTo(1);
        assertThat(c.returnedByName()).isEqualTo("Adam Ade");
        assertThat(c.returnNote()).isEqualTo("The TIN was not checked");
        ArgumentCaptor<AssignmentEventPayload> p = ArgumentCaptor.forClass(AssignmentEventPayload.class);
        verify(events).publish(eq(SellerEvents.APPLICATION_ASSIGNED), any(), eq("a1"), p.capture());
        assertThat(p.getValue().reason()).isEqualTo("SENT_BACK");
        assertThat(p.getValue().recipientUserIds()).contains("amina");
    }

    @Test
    void sendBackCanPreferTheOriginalReviewerWhenTheOwnerTurnsItOn() {
        settingsRepo.value = settings(AutoAssignMode.NONE, true, EscalationChain.class.cast(null));
        Application awaiting = submitted.review(ReviewAction.APPROVE, "amina", "OK", null, null, T0);
        Application back = awaiting.decide(ReviewAction.SEND_BACK, "adam", "FLAW", null, T0);
        service.transitioned(awaiting, back, ReviewAction.SEND_BACK, "adam", "Adam Ade", "recheck");
        assertThat(store.get("a1").assigneeId()).isEqualTo("amina");
    }

    @Test
    void requestInfoClearsTheAssignmentAndLeavesNoStage() {
        actor("amina", "applications:review");
        service.claim("a1", "Amina Bello");
        Application info = submitted.review(ReviewAction.REQUEST_INFO, "amina", "DOC", null, List.of("ID"), T0);
        service.transitioned(submitted, info, ReviewAction.REQUEST_INFO, "amina", "Amina Bello", null);
        ApplicationCase c = store.get("a1");
        assertThat(c.stage()).isNull();
        assertThat(c.assigned()).isFalse();
        verify(events, never()).publish(any(), any(), any(), any());
    }

    @Test
    void leastLoadedAutoAssignPicksTheLightestEligiblePerson() {
        settingsRepo.value = settings(AutoAssignMode.LEAST_LOADED, false, null);
        load.put("rita", 3);
        load.put("amina", 1);
        service.transitioned(submitted, submitted, ReviewAction.SUBMITTED, "applicant", null, null);
        assertThat(store.get("a1").assigneeId()).isEqualTo("adam"); // nobody has fewer than zero
        load.put("adam", 2);
        store.remove("a1");
        service.transitioned(submitted, submitted, ReviewAction.SUBMITTED, "applicant", null, null);
        assertThat(store.get("a1").assigneeId()).isEqualTo("amina");
    }

    @Test
    void roundRobinTakesTurnsAndSkipsTheApplicant() {
        settingsRepo.value = settings(AutoAssignMode.ROUND_ROBIN, false, null);
        directory.review = List.of(adam, new StaffPerson("applicant", "The Applicant"), amina, rita);
        List<String> order = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            store.remove("a1");
            service.transitioned(submitted, submitted, ReviewAction.SUBMITTED, "applicant", null, null);
            order.add(store.get("a1").assigneeId());
        }
        assertThat(order).containsExactly("adam", "amina", "rita", "adam");
    }

    // ---------------------------------------------------------------- escalation

    private ReviewSettings withChains() {
        EscalationChain chain = new EscalationChain(new EscalationTarget(null, List.of("sue")),
                new EscalationTarget("platform.operations_manager", null));
        return settings(AutoAssignMode.NONE, false, chain);
    }

    @Test
    void nothingHappensBeforeTheSlaAndDueSoonIsOnlyAState() {
        settingsRepo.value = withChains();
        now = T0.plus(Duration.ofHours(19)); // 19 of 24 h: due soon, not overdue, and untaken for 19 h > 4 h
        // untaken for longer than unassignedAfterHours escalates even before the SLA ends
        assertThat(service.escalateIfDue("a1", now)).isTrue();
        store.put("a1", store.get("a1").assign("amina", "Amina Bello", "amina", "Amina Bello", T0));
        store.put("a1", ApplicationCase.idle("a1", T0).enter(ReviewStage.REVIEW, T0)
                .assign("amina", "Amina Bello", "amina", "Amina Bello", T0));
        assertThat(service.escalateIfDue("a1", now)).isFalse();
        Actor viewer = actor("amina", "applications:review");
        ApplicationCaseView v = service.viewFor(submitted, store.get("a1"), viewer, settingsRepo.value);
        assertThat(v.slaState()).isEqualTo("DUE_SOON");
    }

    @Test
    void overdueRaisesLevelOneThenLevelTwoAndNeverTwice() {
        settingsRepo.value = withChains();
        store.put("a1", ApplicationCase.idle("a1", T0).enter(ReviewStage.REVIEW, T0)
                .assign("amina", "Amina Bello", "amina", "Amina Bello", T0));
        now = T0.plus(Duration.ofHours(25));
        assertThat(service.escalateIfDue("a1", now)).isTrue();
        assertThat(store.get("a1").escalationLevel()).isEqualTo(1);
        assertThat(store.get("a1").escalationTargetNames()).containsExactly("Sue Supervisor");
        assertThat(service.escalateIfDue("a1", now)).as("second run, same moment").isFalse();
        verify(events, times(1)).publish(eq(SellerEvents.APPLICATION_ESCALATED), any(), eq("a1"), any());
        now = T0.plus(Duration.ofHours(49)); // SLA 24 h + level 2 after 24 h
        directory.roles.put("platform.operations_manager", List.of(new StaffPerson("olu", "Olu Manager")));
        assertThat(service.escalateIfDue("a1", now)).isTrue();
        assertThat(store.get("a1").escalationLevel()).isEqualTo(2);
        assertThat(store.get("a1").escalationTargetNames()).containsExactly("Olu Manager");
        assertThat(service.escalateIfDue("a1", now.plusSeconds(60))).isFalse();
        verify(events, times(2)).publish(eq(SellerEvents.APPLICATION_ESCALATED), any(), eq("a1"), any());
    }

    @Test
    void aCaseNobodyHasTakenEscalatesAfterTheUnassignedWindow() {
        settingsRepo.value = withChains();
        assertThat(service.escalateIfDue("a1", T0.plus(Duration.ofHours(3)))).isFalse();
        assertThat(service.escalateIfDue("a1", T0.plus(Duration.ofHours(5)))).isTrue();
        assertThat(store.get("a1").escalationReason()).contains("Nobody has taken it");
    }

    @Test
    void noTargetsMeansNoEscalationYetAndRetriesLater() {
        settingsRepo.value = ReviewSettings.defaults();
        assertThat(service.escalateIfDue("a1", T0.plus(Duration.ofHours(30)))).isFalse();
        assertThat(store.get("a1").escalationLevel()).isZero();
    }

    @Test
    void movingToTheNextStageDeEscalates() {
        settingsRepo.value = withChains();
        service.escalateIfDue("a1", T0.plus(Duration.ofHours(30)));
        assertThat(store.get("a1").escalationLevel()).isEqualTo(1);
        Application after = submitted.review(ReviewAction.APPROVE, "amina", "OK", null, null, T0);
        service.transitioned(submitted, after, ReviewAction.APPROVE, "amina", "Amina Bello", null);
        assertThat(store.get("a1").escalationLevel()).isZero();
        assertThat(store.get("a1").stageEnteredAt()).isEqualTo(now);
    }

    @Test
    void manualEscalationNeedsAReasonAndTheHolderOrASupervisor() {
        settingsRepo.value = withChains();
        actor("sue", "applications:assign");
        assertThatThrownBy(() -> service.escalate("a1", " ", "Sue")).isInstanceOf(SellerRuleViolation.class);
        assertThat(service.escalate("a1", "Applicant is chasing", "Sue Supervisor").escalationLevel()).isEqualTo(1);
        actor("adam", "applications:review");
        assertThatThrownBy(() -> service.escalate("a1", "x", "Adam")).isInstanceOf(SellerForbidden.class);
    }

    // ---------------------------------------------------------------- permission matrix (what the viewer sees and may do)

    @Test
    void viewersSeeWhoHasItEvenOutsideTheirPurviewButCannotAct() {
        store.put("a1", ApplicationCase.idle("a1", T0).enter(ReviewStage.APPROVAL, T0)
                .assign("adam", "Adam Ade", "adam", "Adam Ade", T0));
        Application awaiting = submitted.review(ReviewAction.APPROVE, "amina", "OK", null, null, T0);
        Actor reviewerOnly = new Actor("rita", PrincipalType.STAFF, Optional.empty(), Set.of(),
                Set.of("applications:review"));
        now = T0.plus(Duration.ofHours(2));
        ApplicationCaseView v = service.viewFor(awaiting, store.get("a1"), reviewerOnly, ReviewSettings.defaults());
        assertThat(v.holder()).isEqualTo("with Adam Ade");
        assertThat(v.stage()).isEqualTo("APPROVAL");
        assertThat(v.waitingSeconds()).isEqualTo(7200);
        assertThat(v.slaState()).isEqualTo("ON_TIME");
        assertThat(v.canClaim() || v.canAssign() || v.canRelease() || v.canTakeOver()).isFalse();
        Actor supervisor = new Actor("sue", PrincipalType.STAFF, Optional.empty(), Set.of(),
                Set.of("applications:assign"));
        ApplicationCaseView sv = service.viewFor(awaiting, store.get("a1"), supervisor, ReviewSettings.defaults());
        assertThat(sv.canAssign()).isTrue();
        assertThat(sv.canRelease()).isTrue();
        assertThat(sv.canTakeOver()).as("a supervisor without the approve permission cannot hold the case").isFalse();
        assertThat(sv.canClaim()).isFalse();
    }

    @Test
    void anUnassignedCaseReadsAsAnyReviewerAndAnyApprover() {
        Actor viewer = new Actor("x", PrincipalType.STAFF, Optional.empty(), Set.of(), Set.of("applications:assign"));
        ApplicationCaseView review = service.viewFor(submitted, store.get("a1"), viewer, ReviewSettings.defaults());
        assertThat(review.holder()).isEqualTo("any reviewer");
        Application awaiting = submitted.review(ReviewAction.APPROVE, "amina", "OK", null, null, T0);
        store.put("a1", ApplicationCase.idle("a1", T0).enter(ReviewStage.APPROVAL, T0));
        assertThat(service.viewFor(awaiting, store.get("a1"), viewer, ReviewSettings.defaults()).holder())
                .isEqualTo("any approver");
    }

    private static ReviewSettings settings(AutoAssignMode mode, boolean back, EscalationChain chain) {
        ReviewSettings d = ReviewSettings.defaults();
        return new ReviewSettings(d.reviewSlaHours(), d.approvalSlaHours(), d.dueSoonPercent(),
                d.unassignedAfterHours(), d.level2AfterHours(), mode, false, back, chain, chain, Map.of(), null, null,
                null);
    }

    private static final class MemSettings implements ReviewSettingsRepository {
        private ReviewSettings value = ReviewSettings.defaults();

        @Override
        public ReviewSettings load() {
            return value;
        }

        @Override
        public ReviewSettings lock() {
            return value;
        }

        @Override
        public void save(ReviewSettings s) {
            value = s;
        }

        @Override
        public void audit(String actorId, String actorName, String reason, ReviewSettings before,
                          ReviewSettings after) {
        }

        @Override
        public List<SettingsChange> audit(int limit) {
            return List.of();
        }
    }

    private static final class MemDirectory implements StaffDirectory {
        private List<StaffPerson> review = List.of();
        private List<StaffPerson> approve = List.of();
        private List<StaffPerson> supervisors = List.of();
        private final Map<String, List<StaffPerson>> roles = new HashMap<>();

        @Override
        public List<StaffPerson> withPermission(String permission) {
            return switch (permission) {
                case "applications:review" -> review;
                case "applications:approve" -> approve;
                default -> supervisors;
            };
        }

        @Override
        public List<StaffPerson> withRole(String roleKey) {
            return roles.getOrDefault(roleKey, List.of());
        }

        @Override
        public List<StaffPerson> people(List<String> ids) {
            List<StaffPerson> all = new ArrayList<>(review);
            all.addAll(approve);
            all.addAll(supervisors);
            return all.stream().filter(p -> ids.contains(p.id())).distinct().toList();
        }
    }
}
