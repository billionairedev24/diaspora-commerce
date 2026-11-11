package com.afrotransact.seller.domain;

import java.util.List;

/** Who one escalation level goes to: everyone holding a role and/or specific people. */
public record EscalationTarget(String roleKey, List<String> userIds) {

    public static final EscalationTarget NONE = new EscalationTarget(null, List.of());

    public EscalationTarget {
        roleKey = roleKey == null || roleKey.isBlank() ? null : roleKey.trim();
        userIds = userIds == null ? List.of() : userIds.stream().filter(u -> u != null && !u.isBlank())
                .map(String::trim).distinct().toList();
    }

    public boolean isEmpty() {
        return roleKey == null && userIds.isEmpty();
    }
}
