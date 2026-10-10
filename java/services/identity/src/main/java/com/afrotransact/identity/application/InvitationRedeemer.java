package com.afrotransact.identity.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;

import com.afrotransact.identity.application.IdentityEventPayloads.InvitationAccepted;
import com.afrotransact.identity.domain.IdentityRuleViolation;
import com.afrotransact.identity.domain.InvitationStatus;
import com.afrotransact.identity.domain.OrgInvitation;
import com.afrotransact.identity.domain.Organization;
import com.afrotransact.identity.domain.TeamPolicy;
import com.afrotransact.identity.domain.UserAccount;
import com.afrotransact.identity.port.AccessChangeLog;
import com.afrotransact.identity.port.OrgInvitationRepository;
import com.afrotransact.identity.port.OrganizationRepository;
import com.afrotransact.platform.events.DomainEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Checks and redeems single-use invitation tokens, for a signed-in user ({@link OrgInvitationService#accept}) and for
 * a sign-up from the invitation link ({@link AccountService#signUp}). The invitee's email must match the invitation.
 */
@Component
class InvitationRedeemer {

    private static final String FIELD = "invitationToken";

    private final OrgInvitationRepository invitations;
    private final OrganizationRepository orgs;
    private final MembershipWriter writer;
    private final TeamPolicy policy;
    private final AccessChangeLog changeLog;
    private final DomainEventPublisher events;
    private final Clock clock;

    InvitationRedeemer(OrgInvitationRepository invitations, OrganizationRepository orgs, MembershipWriter writer,
                       TeamPolicy policy, AccessChangeLog changeLog, DomainEventPublisher events, Clock clock) {
        this.invitations = invitations;
        this.orgs = orgs;
        this.writer = writer;
        this.policy = policy;
        this.changeLog = changeLog;
        this.events = events;
        this.clock = clock;
    }

    /**
     * The pending invitation for {@code token}, which must be addressed to {@code email}; throws
     * {@link IdentityRuleViolation} (422) otherwise. Writes nothing, so sign-up can check before creating the account.
     */
    OrgInvitation requirePending(String token, String email) {
        OrgInvitation inv = invitations.findByTokenHash(policy.hashToken(token)).orElseThrow(() ->
                invalid("identity.invitation-invalid", "This invitation link isn't valid"));
        InvitationStatus status = inv.statusAt(Instant.now(clock));
        switch (status) {
            case PENDING -> {
                // checked below
            }
            case EXPIRED -> throw invalid("identity.invitation-expired",
                    "This invitation has expired. Ask the team owner to invite you again");
            case ACCEPTED -> throw invalid("identity.invitation-used", "This invitation was already accepted");
            default -> throw invalid("identity.invitation-revoked", "This invitation was withdrawn");
        }
        if (!inv.email().equals(UserAccount.normaliseEmail(email))) {
            throw new IdentityRuleViolation("identity.invitation-email-mismatch",
                    "This invitation was sent to a different email address",
                    Map.of("email", "Use the email address the invitation was sent to"));
        }
        return inv;
    }

    /** Adds the user to the organisation with the invited role (a member keeps their role) and closes the invitation. */
    AcceptedInvitation redeem(OrgInvitation inv, UserAccount user) {
        Organization org = orgs.findById(inv.orgId()).orElseThrow(() ->
                invalid("identity.invitation-invalid", "This organisation no longer exists"));
        String existing = orgs.memberRole(org.id(), user.id()).orElse(null);
        String actor = user.id().toString();
        if (existing == null) {
            writer.set(org, user, null, inv.roleKey(), actor, "invitation " + inv.id());
        }
        Instant now = Instant.now(clock);
        invitations.update(inv.accepted(user.id(), now));
        changeLog.append(actor, "org.invitation.accepted", user.id(), inv.roleKey(), org.id(), inv.email());
        events.publish(IdentityEvents.INVITATION_ACCEPTED, IdentityEvents.AGGREGATE, actor,
                new InvitationAccepted(inv.id().toString(), org.id(), inv.email(), inv.roleKey(), actor,
                        existing != null, now));
        return new AcceptedInvitation(inv.id(), org.id(), org.name(), org.kind(),
                existing == null ? inv.roleKey() : existing, user.id(), existing != null);
    }

    private static IdentityRuleViolation invalid(String code, String message) {
        return new IdentityRuleViolation(code, message, Map.of(FIELD, message));
    }
}
