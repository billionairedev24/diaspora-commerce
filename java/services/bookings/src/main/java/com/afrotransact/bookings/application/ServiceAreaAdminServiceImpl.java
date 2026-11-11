package com.afrotransact.bookings.application;

import java.util.List;
import java.util.Map;

import com.afrotransact.bookings.domain.AreaPolicy;
import com.afrotransact.bookings.domain.BookingsRuleViolation;
import com.afrotransact.bookings.domain.ServiceZone;
import com.afrotransact.bookings.port.ServiceAreaConfigRepository;
import com.afrotransact.bookings.port.ServiceAreaConfigRepository.AuditEntry;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ServiceAreaAdminServiceImpl implements ServiceAreaAdminService {

    private final ServiceAreaConfigRepository config;
    private final PermissionChecker permissions;

    ServiceAreaAdminServiceImpl(ServiceAreaConfigRepository config, PermissionChecker permissions) {
        this.config = config;
        this.permissions = permissions;
    }

    @Override
    @Transactional(readOnly = true)
    public AreaPolicy policy() {
        staff();
        return config.policy();
    }

    @Override
    @Transactional
    public AreaPolicy savePolicy(AreaPolicy policy, String reason) {
        Actor actor = staff();
        config.savePolicy(policy);
        config.audit(actor.subject(), "policy", reasonOf(reason), policy);
        return config.policy();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ServiceZone> zones() {
        staff();
        return config.zones();
    }

    @Override
    @Transactional
    public ServiceZone saveZone(ServiceZone zone, String reason) {
        Actor actor = staff();
        config.saveZone(zone);
        config.audit(actor.subject(), "zone:" + zone.key(), reasonOf(reason), zone);
        return zone;
    }

    @Override
    @Transactional
    public void deleteZone(String key, String reason) {
        Actor actor = staff();
        String why = reasonOf(reason);
        if (!config.deleteZone(key)) {
            throw new NotFoundException("bookings.zone-not-found", "Zone " + key + " not found");
        }
        config.audit(actor.subject(), "zone:" + key, why, Map.of("deleted", true));
    }

    @Override
    @Transactional(readOnly = true)
    public List<AuditEntry> audit(int limit) {
        staff();
        return config.auditTrail(Math.clamp(limit, 1, 200));
    }

    /** Staff only (principal type STAFF, or a platform administrator holding every permission). */
    private Actor staff() {
        Actor actor = permissions.require(BookingsPermissions.SERVICE_AREAS_MANAGE);
        if (!BookingsAccess.isStaff(actor) && !actor.permissions().contains("*")) {
            throw new ForbiddenActionException("bookings.service-areas-staff-only",
                    "Only staff can change service-area settings");
        }
        return actor;
    }

    private static String reasonOf(String reason) {
        if (reason == null || reason.trim().length() < 5) {
            throw new BookingsRuleViolation("bookings.reason-required", "Give a reason for the change",
                    Map.of("reason", "At least 5 characters"));
        }
        return reason.trim();
    }
}
