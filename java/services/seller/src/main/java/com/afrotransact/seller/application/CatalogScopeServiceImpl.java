package com.afrotransact.seller.application;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.web.error.NotFoundException;
import com.afrotransact.seller.domain.ApplicationData;
import com.afrotransact.seller.domain.CatalogScope;
import com.afrotransact.seller.domain.ComplianceSection;
import com.afrotransact.seller.domain.Department;
import com.afrotransact.seller.domain.Department.Requirement;
import com.afrotransact.seller.domain.DepartmentRequest;
import com.afrotransact.seller.domain.DepartmentRequestStatus;
import com.afrotransact.seller.domain.SellerRuleViolation;
import com.afrotransact.seller.domain.SellerStateConflict;
import com.afrotransact.seller.port.ApplicationRepository;
import com.afrotransact.seller.port.CatalogScopeRepository;
import com.afrotransact.seller.port.DepartmentDirectory;
import com.afrotransact.seller.port.OrganisationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class CatalogScopeServiceImpl implements CatalogScopeService {

    /** Every role of an organisation may see which departments it sells in. */
    private static final Set<String> MEMBERS = Set.of("owner", "admin", "fulfilment", "catalog", "finance", "support",
            "ads", "marketing");
    /** Who may ask for another department. */
    private static final Set<String> REQUESTERS = Set.of("owner", "admin", "catalog");
    private static final int MIN_REASON = 10;
    /** A rejection says why, in words the seller can act on. */
    private static final int MIN_REJECTION = 3;
    private static final int MAX_NOTE = 500;

    private final CatalogScopeRepository scopes;
    private final ApplicationRepository applications;
    private final OrganisationRepository orgs;
    private final DepartmentDirectory directory;
    private final AccessGuard access;
    private final SellerSettings settings;
    private final Clock clock;
    private final DomainEventPublisher events;

    CatalogScopeServiceImpl(CatalogScopeRepository scopes, ApplicationRepository applications,
                            OrganisationRepository orgs, DepartmentDirectory directory, AccessGuard access,
                            SellerSettings settings, Clock clock, DomainEventPublisher events) {
        this.scopes = scopes;
        this.applications = applications;
        this.orgs = orgs;
        this.directory = directory;
        this.access = access;
        this.settings = settings;
        this.clock = clock;
        this.events = events;
    }

    @Override
    public List<Department> departments() {
        access.current();
        return directory.departments();
    }

    @Override
    @Transactional(readOnly = true)
    public CatalogScope forOrg(String orgId) {
        Actor actor = access.current();
        boolean service = actor.type() == PrincipalType.SERVICE && actor.can(SERVICE_READ);
        if (!service) {
            access.orgRoleOrStaff(orgId, MEMBERS, SellerPermissions.APPLICATIONS_REVIEW,
                    SellerPermissions.APPLICATIONS_APPROVE, SellerPermissions.SELLERS_ENFORCE);
        }
        return scope(orgId);
    }

    @Override
    @Transactional
    public void recordFromApplication(String orgId, ApplicationData data) {
        if (settings.isRetail(orgId) || data == null || data.compliance() == null) {
            return;
        }
        Set<String> chosen = codes(data.compliance().departments());
        if (!chosen.isEmpty()) {
            scopes.add(orgId, chosen, "ONBOARDING", null, Instant.now(clock));
        }
    }

    @Override
    @Transactional
    public DepartmentRequest request(String orgId, String departmentCode, String note) {
        Actor actor = access.orgRole(orgId, REQUESTERS, null);
        requireOrg(orgId);
        if (settings.isRetail(orgId)) {
            throw new SellerStateConflict("seller.department-already-allowed", "This store sells in every department");
        }
        Department department = known(departmentCode);
        if (scopes.departments(orgId).contains(department.code())) {
            throw new SellerStateConflict("seller.department-already-allowed",
                    "Your store can already sell in " + department.name());
        }
        Optional<DepartmentRequest> open = scopes.findPending(orgId, department.code());
        if (open.isPresent()) {
            return needs(open.get(), department);
        }
        String text = note == null || note.isBlank() ? null : note.strip();
        if (text != null && text.length() > MAX_NOTE) {
            throw new SellerRuleViolation("seller.invalid-department", "Keep the note under " + MAX_NOTE + " characters");
        }
        return needs(scopes.insert(new DepartmentRequest(UUID.randomUUID(), orgId, department.code(), text,
                DepartmentRequestStatus.PENDING, actor.subject(), Instant.now(clock), null, null, null, Set.of())),
                department);
    }

    @Override
    @Transactional
    public CatalogScope setDepartments(String orgId, Set<String> chosen, String reason) {
        Actor staff = access.staff(SellerPermissions.APPLICATIONS_APPROVE);
        requireOrg(orgId);
        if (settings.isRetail(orgId)) {
            throw new SellerStateConflict("seller.retail-exempt", "AfroTransact Retail sells in every department");
        }
        if (reason == null || reason.strip().length() < MIN_REASON) {
            throw new SellerRuleViolation("seller.reason-required",
                    "Say why the departments change (at least " + MIN_REASON + " characters)");
        }
        Set<String> valid = new LinkedHashSet<>();
        chosen.forEach(c -> valid.add(known(c).code()));
        Set<String> before = scopes.departments(orgId);
        Instant now = Instant.now(clock);
        scopes.replace(orgId, valid, "STAFF", staff.subject(), now);
        events.publish(SellerEvents.DEPARTMENTS_CHANGED, SellerEvents.AGGREGATE_APPLICATION, orgId,
                DepartmentsChangedPayload.of(orgId, before, valid, staff.subject(), reason.strip(), now));
        return scope(orgId);
    }

    @Override
    @Transactional
    public DepartmentRequest decide(UUID requestId, boolean approve, String note) {
        Actor staff = access.staff(SellerPermissions.APPLICATIONS_APPROVE);
        DepartmentRequest request = scopes.findRequest(requestId).orElseThrow(() ->
                new NotFoundException("seller.department-request-not-found", "Department request not found"));
        if (request.status() != DepartmentRequestStatus.PENDING) {
            throw new SellerStateConflict("seller.department-request-decided",
                    "This request was already " + request.status().name().toLowerCase(Locale.ROOT));
        }
        String text = note == null || note.isBlank() ? null : note.strip();
        if (!approve && (text == null || text.length() < MIN_REJECTION)) {
            throw new SellerRuleViolation("seller.reason-required",
                    "Say why the request is rejected: the seller reads it");
        }
        Instant now = Instant.now(clock);
        if (approve) {
            scopes.add(request.orgId(), Set.of(request.departmentCode()), "REQUEST", staff.subject(), now);
        }
        DepartmentRequestStatus outcome = approve ? DepartmentRequestStatus.APPROVED : DepartmentRequestStatus.REJECTED;
        DepartmentRequest decided = scopes.update(request.decided(outcome, staff.subject(), text, now));
        events.publish(SellerEvents.DEPARTMENT_REQUEST_DECIDED, SellerEvents.AGGREGATE_APPLICATION, request.orgId(),
                new DepartmentRequestDecidedPayload(decided.id(), decided.orgId(), decided.departmentCode(),
                        outcome.name(), staff.subject(), text, now));
        return needs(decided, null);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DepartmentRequest> queue(DepartmentRequestStatus status) {
        access.staffAny(SellerPermissions.APPLICATIONS_REVIEW, SellerPermissions.APPLICATIONS_APPROVE);
        return scopes.queue(status == null ? DepartmentRequestStatus.PENDING : status, QUEUE_LIMIT).stream()
                .map(r -> needs(r, null)).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public DepartmentRequest request(UUID requestId) {
        access.staffAny(SellerPermissions.APPLICATIONS_REVIEW, SellerPermissions.APPLICATIONS_APPROVE);
        return needs(scopes.findRequest(requestId).orElseThrow(() ->
                new NotFoundException("seller.department-request-not-found", "Department request not found")), null);
    }

    private CatalogScope scope(String orgId) {
        if (settings.isRetail(orgId)) {
            return new CatalogScope(orgId, true, Set.of(), List.of());
        }
        requireOrg(orgId);
        return new CatalogScope(orgId, false, scopes.departments(orgId),
                scopes.requestsOf(orgId).stream().map(r -> needs(r, null)).toList());
    }

    /** The catalog department with this code (422 when it is not an active one). */
    private Department known(String code) {
        String wanted = code == null ? "" : code.trim().toLowerCase(Locale.ROOT);
        return directory.departments().stream().filter(d -> d.code().equals(wanted)).findFirst()
                .orElseThrow(() -> new SellerRuleViolation("seller.invalid-department",
                        "Choose a department from the list"));
    }

    private static Set<String> codes(List<String> chosen) {
        Set<String> out = new LinkedHashSet<>();
        for (String c : chosen) {
            if (c != null && Department.isCode(c.trim().toLowerCase(Locale.ROOT))) {
                out.add(c.trim().toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }

    /**
     * What the seller still has to provide for a request not decided against them. {@code known} is the department
     * when the caller already has it; otherwise it is looked up (a catalog outage then just shows no needs).
     */
    private DepartmentRequest needs(DepartmentRequest r, Department known) {
        if (r.status() == DepartmentRequestStatus.REJECTED) {
            return r;
        }
        Department department = known;
        if (department == null) {
            try {
                Map<String, Department> byCode = directory.departments().stream()
                        .collect(Collectors.toMap(Department::code, Function.identity(), (a, b) -> a));
                department = byCode.get(r.departmentCode());
            } catch (DepartmentDirectory.DirectoryUnavailable ex) {
                return r;
            }
        }
        if (department == null) {
            return r;
        }
        Set<Requirement> wanted = EnumSet.noneOf(Requirement.class);
        wanted.addAll(department.requires());
        Optional<ComplianceSection> compliance = applications.findByOrg(r.orgId()).map(a -> a.data())
                .map(ApplicationData::compliance);
        if (compliance.map(ComplianceSection::foodPermit).isPresent()) {
            wanted.remove(Requirement.FOOD_PERMIT);
        }
        if (compliance.map(ComplianceSection::beautyLabellingConfirmed).filter(Boolean::booleanValue).isPresent()) {
            wanted.remove(Requirement.BEAUTY_LABELLING);
        }
        return r.withNeeds(wanted);
    }

    private void requireOrg(String orgId) {
        if (!settings.isRetail(orgId) && orgs.findById(orgId).isEmpty()) {
            throw new NotFoundException("seller.org-not-found", "Organisation not found");
        }
    }
}
