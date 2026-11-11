package com.afrotransact.orders.port;

import java.util.List;

/** Who staff are: the people holding a permission or role, and names for ids. Backed by identity-service. */
public interface StaffDirectory {

    /** Enabled staff holding {@code permission}. */
    List<StaffPerson> withPermission(String permission);

    /** Enabled staff holding the role. */
    List<StaffPerson> withRole(String roleKey);

    /** The enabled accounts among {@code userIds}, with names (unknown or disabled ids are missing). */
    List<StaffPerson> people(List<String> userIds);

    record StaffPerson(String id, String name) {
    }
}
