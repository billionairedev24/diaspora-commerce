package com.afrotransact.payments.application;

import java.util.List;

import com.afrotransact.payments.domain.MembershipSettings;
import com.afrotransact.payments.port.MembershipSettingsRepository;

/** The membership billing rules staff set (refund window, retry schedule), each change with a reason. */
public interface MembershipSettingsService {

    /** Staff with payments:read, or a calling service. */
    MembershipSettings get();

    /** payments:configure; a reason of at least 15 characters; the version the editor loaded. */
    MembershipSettings update(UpdateCommand command);

    List<MembershipSettingsRepository.Change> history(int limit);

    record UpdateCommand(int refundWindowDays, List<Integer> retryDays, String reason, long expectedVersion) {
    }
}
