package com.afrotransact.identity.application;

/** Invite a person to be staff with one platform role; {@code reason} is kept in the access change log. */
public record InviteStaffCommand(String firstName, String lastName, String email, String roleKey, String reason) {
}
