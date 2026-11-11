package com.afrotransact.bookings.port;

import java.util.List;

import com.afrotransact.bookings.domain.AreaPolicy;
import com.afrotransact.bookings.domain.ServiceZone;

/** Persistence port for the staff-set service-area configuration: metro zones, radius policy and its audit trail. */
public interface ServiceAreaConfigRepository {

    List<ServiceZone> zones();

    void saveZone(ServiceZone zone);

    /** @return true when a zone was removed */
    boolean deleteZone(String key);

    AreaPolicy policy();

    void savePolicy(AreaPolicy policy);

    /** Records who changed what and why (a reason is mandatory). */
    void audit(String actor, String subject, String reason, Object change);

    List<AuditEntry> auditTrail(int limit);

    /** One audited change. */
    record AuditEntry(String at, String actor, String subject, String reason) {
    }
}
