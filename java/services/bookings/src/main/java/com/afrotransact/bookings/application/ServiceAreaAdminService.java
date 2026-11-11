package com.afrotransact.bookings.application;

import java.util.List;

import com.afrotransact.bookings.domain.AreaPolicy;
import com.afrotransact.bookings.domain.ServiceZone;
import com.afrotransact.bookings.port.ServiceAreaConfigRepository.AuditEntry;

/** Staff settings of service areas: the radius policy and the metro zones. Every change needs a reason. */
public interface ServiceAreaAdminService {

    AreaPolicy policy();

    AreaPolicy savePolicy(AreaPolicy policy, String reason);

    List<ServiceZone> zones();

    ServiceZone saveZone(ServiceZone zone, String reason);

    void deleteZone(String key, String reason);

    List<AuditEntry> audit(int limit);
}
