// GENERATED from contracts/openapi/identity.yaml by openapi-typescript — do not edit by hand.
// Regenerate with: pnpm --filter @afrotransact/customer-app generate
/* eslint-disable */

export interface paths {
    "/v1/accounts": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Create an account (customers, sellers and providers use the same form)
         * @description Adds account.customer, plus the seller/provider applicant role when intent is sell/provide. A 6-digit code is emailed (10-minute expiry). With invitationToken (organisation invite link, same email) the account starts verified, joins the organisation and no code is sent.
         */
        post: operations["signUp"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/accounts/verification": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Confirm the email address with the 6-digit code */
        post: operations["verify_1"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/accounts/verification/resend": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Send a new code (at most 1 per 30 seconds and 5 per hour; 429 with Retry-After otherwise) */
        post: operations["resend"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/admin/access-log": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Append-only access change log (who changed what and when) */
        get: operations["log"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/admin/data-protection": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Current data-protection key and TOTP secrets per key (platform.super_admin) */
        get: operations["dataProtectionStatus"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/admin/data-protection/rotate": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Re-encrypt every TOTP secret under the current key (platform.super_admin)
         * @description Run after deploying a new IDENTITY_DATA_KEY with the old key in IDENTITY_DATA_KEYS_PREVIOUS. Online and idempotent; audited in the access change log.
         */
        post: operations["rotateDataProtectionKey"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/admin/identity-providers": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** All providers with their switch and configuration state (access:read) */
        get: operations["all"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/admin/identity-providers/{providerId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        /** Enable or disable a provider (access:admin) */
        put: operations["set"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/admin/module-access": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Module access matrix of a portal */
        get: operations["matrix"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/admin/module-access/{portal}/{moduleKey}/{roleKey}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        /** Set one cell of the module access matrix */
        put: operations["setModuleAccess"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/admin/permissions": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Permission catalogue */
        get: operations["permissions"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/admin/roles": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Roles of a portal with their permissions (capabilities tab) */
        get: operations["roles"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/admin/roles/{roleKey}/permissions/{permissionKey}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        /** Add a permission to a role */
        put: operations["addRolePermission"];
        post?: never;
        /** Remove a permission from a role (holders lose it on their next token) */
        delete: operations["removeRolePermission"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/admin/users": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** People: search users by name or email */
        get: operations["users"];
        put?: never;
        /** Provision a staff or driver account with initial roles */
        post: operations["create"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/admin/users/{userId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** A user's roles, direct grants and effective permissions */
        get: operations["user"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/admin/users/{userId}/disable": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Disable an account; all its sessions end immediately */
        post: operations["disable"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/admin/users/{userId}/enable": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Re-enable an account */
        post: operations["enable"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/admin/users/{userId}/permissions/{permissionKey}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        /** Grant a permission directly (for example delivery:operate) */
        put: operations["grant"];
        post?: never;
        /** Revoke a direct permission grant (takes effect on the user's next token) */
        delete: operations["revoke"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/admin/users/{userId}/roles/{roleKey}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        /** Assign an account or platform role */
        put: operations["assignRole"];
        post?: never;
        /** Remove a role (takes effect on the user's next token) */
        delete: operations["removeRole"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/auth/mfa/sms": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Text a sign-in code for an MFA challenge */
        post: operations["sms"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/auth/mfa/verify": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Verify an authenticator, text-message or recovery code (returns a login ticket) */
        post: operations["verify"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/auth/passkey/options": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Start a passkey sign-in (WebAuthn assertion options) */
        post: operations["passkeyOptions"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/auth/passkey/verify": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Verify a passkey assertion (returns a login ticket; passkeys need no second factor) */
        post: operations["passkeyVerify"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/auth/password": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Sign in with email and password (returns an MFA challenge or a login ticket) */
        post: operations["password"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/identity-providers": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Enabled social providers for sign-in screens (public) */
        get: operations["enabled"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/internal/orgs/{orgId}/owners": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Owner accounts of a seller or provider organisation */
        get: operations["owners"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/internal/users/{userId}/contact": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** How to reach a user: email, verified phone, push tokens, locale, time zone, market, channel switches */
        get: operations["contact"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/invitations/accept": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Accept an invitation as the signed-in user
         * @description The account email must be the invited one (422 identity.invitation-email-mismatch). Expired, used or revoked tokens: 422 identity.invitation-expired / -used / -revoked. People without an account sign up with POST /v1/accounts and invitationToken instead. Publishes identity.role.granted and identity.invitation.accepted.
         */
        post: operations["accept"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/invitations/lookup": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Look up an invitation link (public)
         * @description Organisation, role, invited email and status for the accept / sign-up page. 404 for unknown tokens. The token is sent in the body so it stays out of access logs.
         */
        post: operations["lookup"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/me": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Profile, roles, effective permissions, organisations and landing */
        get: operations["me"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        /** Complete or edit the profile (social sign-ups add their phone and accept terms here) */
        patch: operations["update"];
        trace?: never;
    };
    "/v1/me/active-org": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        /** Choose the organisation to act for (token org claim from the next token) */
        put: operations["selectOrg"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/me/addresses": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** List saved addresses (default first) */
        get: operations["list_1"];
        put?: never;
        /** Add an address; the first one becomes the default */
        post: operations["add"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/me/addresses/{id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post?: never;
        /** Remove an address (the next most recent becomes default if needed) */
        delete: operations["remove"];
        options?: never;
        head?: never;
        /** Edit an address */
        patch: operations["update_1"];
        trace?: never;
    };
    "/v1/me/addresses/{id}/default": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Make an address the default */
        post: operations["makeDefault"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/me/devices": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Devices registered for push notifications */
        get: operations["list"];
        put?: never;
        /** Register or refresh a push token (call on every app start and token refresh) */
        post: operations["register"];
        /** Unregister a push token (sign-out); succeeds when it is not registered */
        delete: operations["unregister"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/me/devices/{deviceId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post?: never;
        /** Remove a registered device */
        delete: operations["remove_1"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/me/linked-accounts": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Linked Google / Apple / Facebook accounts */
        get: operations["linked"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/me/linked-accounts/{provider}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post?: never;
        /** Unlink a social account (refused for the last sign-in method) */
        delete: operations["unlink"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/me/mfa": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Enrolled factors and whether MFA is required / may be turned off (never for staff) */
        get: operations["status"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/me/mfa/recovery-codes": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Regenerate recovery codes (old ones stop working) */
        post: operations["regenerate"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/me/mfa/sms": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Text a code to the account phone to turn on SMS sign-in codes */
        post: operations["startSms"];
        /** Turn off SMS sign-in codes (403 for staff) */
        delete: operations["disableSms"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/me/mfa/sms/confirm": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Confirm SMS sign-in codes; returns recovery codes the first time */
        post: operations["confirmSms"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/me/mfa/totp": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Start authenticator-app enrolment (QR otpauth URI) */
        post: operations["startTotp"];
        /** Turn off the authenticator app (403 for staff) */
        delete: operations["disableTotp"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/me/mfa/totp/confirm": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Confirm the authenticator app with a code; returns recovery codes the first time */
        post: operations["confirmTotp"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/me/notification-preferences": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Notification switches (defaults: email, SMS and push on; marketing off) */
        get: operations["get"];
        /** Replace the notification switches */
        put: operations["put"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/me/passkeys": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Registered passkeys (register at /webauthn/register on the authorization server) */
        get: operations["passkeys"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/me/passkeys/{credentialId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post?: never;
        /** Remove a passkey (refused for the last sign-in method) */
        delete: operations["removePasskey"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/me/password": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        /** Change password (requires the current password) */
        put: operations["changePassword"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/me/phone/verification": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Text a 6-digit code to the account's phone number (S234)
         * @description Independent of MFA. 10-minute expiry, 5 wrong attempts, at most 1 code per 30 seconds and 5 per hour (429 + Retry-After). 422 identity.phone-missing without a phone; 409 identity.phone-already-verified. Published as identity.verification.requested (channel sms, purpose PHONE_VERIFICATION).
         */
        post: operations["startPhoneVerification"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/me/phone/verification/confirm": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /**
         * Confirm the phone number with the SMS code
         * @description 422 identity.invalid-code / code-expired / too-many-attempts / code-used / phone-changed. Changing the phone with PATCH /v1/me resets phoneVerified. Publishes identity.user.phone-verified.
         */
        post: operations["confirmPhoneVerification"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/me/roles/provider-applicant": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Offer services: adds account.provider_applicant to this account (no second sign-up) */
        post: operations["startProviding"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/me/roles/seller-applicant": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Start selling: adds account.seller_applicant to this account (no second sign-up) */
        post: operations["startSelling"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/me/sessions": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Active sessions (one per signed-in app or device); current marks this device */
        get: operations["sessions"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/me/sessions/revoke-others": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Sign out other devices */
        post: operations["revokeOthers"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/me/sessions/{sessionId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post?: never;
        /** Sign out one session (its refresh token stops working immediately) */
        delete: operations["revoke_1"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/orgs/{orgId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        /** Create or update an organisation and its approval status */
        put: operations["upsert"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/orgs/{orgId}/invitations": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** Pending (unexpired) invitations */
        get: operations["pendingInvitations"];
        put?: never;
        /**
         * Invite someone by email with an organisation role
         * @description Publishes identity.invitation.created with a single-use token (7 days) for notifications-service to email. Inviting the same email again replaces the open invitation. 409 identity.already-member when the email already belongs to a member.
         */
        post: operations["invite"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/orgs/{orgId}/invitations/{invitationId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post?: never;
        /** Revoke a pending invitation (publishes identity.invitation.revoked) */
        delete: operations["revokeInvitation"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/orgs/{orgId}/members": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /**
         * Member directory: names, email, role, status and join date
         * @description Members of the (approved) organisation whose role has team:manage or at least VIEW on the Team & roles module; staff and services with orgs:read or orgs:manage.
         */
        get: operations["members"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/v1/orgs/{orgId}/members/{userId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        /**
         * Change a member's organisation role (seller.* / provider.*)
         * @description team:manage members change other members' roles (not their own; only owners grant or change the owner role). The seller service and staff (orgs:manage) may also add a member. The last owner can't be demoted (422 identity.last-owner). Publishes identity.role.revoked for the old role and identity.role.granted for the new one.
         */
        put: operations["setMember"];
        post?: never;
        /**
         * Remove a member
         * @description The last owner can't be removed (422 identity.last-owner); only owners remove owners. Publishes identity.role.revoked.
         */
        delete: operations["removeMember"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
}
export type webhooks = Record<string, never>;
export interface components {
    schemas: {
        UpsertOrgRequest: {
            /** @enum {string} */
            kind: "SELLER" | "PROVIDER";
            name: string;
            /** @enum {string} */
            status: "PENDING" | "APPROVED" | "SUSPENDED" | "REJECTED";
        };
        Organization: {
            /** @example org_3k2j9x8q7w6e5r4t3y2u */
            id?: string;
            kind?: string;
            name?: string;
            status?: string;
            market?: string;
            /** Format: date-time */
            updatedAt?: string;
        };
        SetMemberRequest: {
            /** @example seller.fulfilment */
            roleKey: string;
        };
        /** @description currentPassword is required when the account has one */
        ChangePasswordRequest: {
            currentPassword?: string;
            newPassword: string;
            confirmPassword: string;
        };
        NotificationPreferencesRequest: {
            email: boolean;
            sms: boolean;
            push: boolean;
            marketingOptIn: boolean;
            /** @example fr-CA */
            locale?: string;
            /** @example America/Chicago */
            timeZone?: string;
        };
        NotificationPreferences: {
            email?: boolean;
            sms?: boolean;
            push?: boolean;
            marketingOptIn?: boolean;
            /** Format: date-time */
            marketingOptInAt?: string;
            locale?: string;
            timeZone?: string;
            /** Format: date-time */
            updatedAt?: string;
        };
        SelectOrgRequest: {
            orgId: string;
        };
        /** @description Profile, roles, effective permissions, organisations and where to land */
        Me: {
            /** Format: uuid */
            id?: string;
            firstName?: string;
            lastName?: string;
            email?: string;
            emailVerified?: boolean;
            phone?: string;
            /** @description The phone was confirmed with an SMS code; reset when it changes */
            phoneVerified?: boolean;
            smsConsent?: boolean;
            /** Format: date-time */
            termsAcceptedAt?: string;
            principalType?: string;
            orgId?: string;
            roles?: string[];
            permissions?: string[];
            orgs?: components["schemas"]["Org"][];
            /** @enum {string} */
            landing?: "shop" | "seller-onboarding" | "seller-center" | "provider-onboarding" | "provider-hub" | "staff-console" | "driver-app";
            profileComplete?: boolean;
        };
        Org: {
            id?: string;
            kind?: string;
            name?: string;
            status?: string;
            role?: string;
        };
        /** @description People tab row: roles, direct grants, effective access */
        UserAccess: {
            /** Format: uuid */
            id?: string;
            name?: string;
            email?: string;
            enabled?: boolean;
            emailVerified?: boolean;
            principalType?: string;
            roles?: string[];
            directGrants?: string[];
            orgs?: components["schemas"]["Org"][];
            effectivePermissions?: string[];
            driverAppAccess?: boolean;
        };
        Role: {
            key?: string;
            portal?: string;
            name?: string;
            description?: string;
            permissions?: string[];
        };
        SetModuleAccessRequest: {
            /** @enum {string} */
            level: "none" | "view" | "edit" | "approve" | "full";
        };
        ModuleAccess: {
            portal?: string;
            module?: string;
            role?: string;
            level?: string;
        };
        SetProviderEnabledRequest: {
            enabled: boolean;
        };
        IdentityProviderAdmin: {
            id?: string;
            displayName?: string;
            enabled?: boolean;
            configured?: boolean;
            /** Format: date-time */
            updatedAt?: string;
            updatedBy?: string;
        };
        InviteMemberRequest: {
            /** Format: email */
            email: string;
            /** @example seller.catalog */
            roleKey: string;
        };
        /** @description An invitation to join an organisation */
        OrgInvitation: {
            /** Format: uuid */
            id?: string;
            orgId?: string;
            orgName?: string;
            /** @enum {string} */
            orgKind?: "SELLER" | "PROVIDER";
            email?: string;
            roleKey?: string;
            roleLabel?: string;
            /** @enum {string} */
            status?: "PENDING" | "ACCEPTED" | "REVOKED" | "EXPIRED";
            /** @description User id (or client id) of the inviter */
            invitedBy?: string;
            /** Format: date-time */
            createdAt?: string;
            /** Format: date-time */
            expiresAt?: string;
        };
        RevokedSessions: {
            /** Format: int32 */
            revoked?: number;
        };
        PhoneCodeSent: {
            /** @description Masked phone, e.g. •••• 0142 */
            sentTo?: string;
            /** Format: date-time */
            expiresAt?: string;
        };
        CodeRequest: {
            code: string;
        };
        PhoneVerification: {
            /** @description E.164 */
            phone?: string;
            phoneVerified?: boolean;
            /** Format: date-time */
            verifiedAt?: string;
        };
        /** @description Render otpauthUri as a QR code; secret is for manual entry */
        TotpSetup: {
            secret?: string;
            otpauthUri?: string;
        };
        /** @description Shown once; empty when existing recovery codes were kept */
        RecoveryCodes: {
            recoveryCodes?: string[];
        };
        CodeSent: {
            /** Format: date-time */
            codeExpiresAt?: string;
        };
        RegisterDeviceRequest: {
            /** @enum {string} */
            platform: "ios" | "android" | "web";
            /** @description FCM registration token or APNs device token */
            token: string;
            /** @enum {string} */
            app: "customer" | "driver";
            /** @example Ada's iPhone */
            deviceName?: string;
            /**
             * @description Push transport; default apns for ios, fcm for android and web
             * @enum {string}
             */
            provider?: "fcm" | "apns";
        };
        Device: {
            /** Format: uuid */
            id?: string;
            /** @enum {string} */
            app?: "customer" | "driver";
            /** @enum {string} */
            platform?: "ios" | "android" | "web";
            /** @enum {string} */
            provider?: "fcm" | "apns";
            /** @description Last 6 characters of the token */
            tokenSuffix?: string;
            deviceName?: string;
            /** Format: date-time */
            createdAt?: string;
            /** Format: date-time */
            lastSeenAt?: string;
        };
        /** @description Fill from address autocomplete (geo service); verified = geo validation passed */
        AddressRequest: {
            label?: string;
            fullName: string;
            phone: string;
            phoneRegion?: string;
            line1: string;
            line2?: string;
            city: string;
            state?: string;
            postalCode: string;
            country?: string;
            /** Format: double */
            latitude?: number;
            /** Format: double */
            longitude?: number;
            instructions?: string;
            verified?: boolean;
            makeDefault?: boolean;
        };
        Address: {
            /** Format: uuid */
            id?: string;
            label?: string;
            fullName?: string;
            phone?: string;
            line1?: string;
            line2?: string;
            city?: string;
            state?: string;
            postalCode?: string;
            country?: string;
            /** Format: double */
            latitude?: number;
            /** Format: double */
            longitude?: number;
            instructions?: string;
            verified?: boolean;
            isDefault?: boolean;
        };
        InvitationTokenRequest: {
            token: string;
        };
        /** @description What an invite link is for; shown before signing in or up */
        InvitationPreview: {
            orgName?: string;
            /** @enum {string} */
            orgKind?: "SELLER" | "PROVIDER";
            email?: string;
            roleKey?: string;
            roleLabel?: string;
            /** @enum {string} */
            status?: "PENDING" | "ACCEPTED" | "REVOKED" | "EXPIRED";
            /** Format: date-time */
            expiresAt?: string;
        };
        AcceptedInvitation: {
            /** Format: uuid */
            invitationId?: string;
            orgId?: string;
            orgName?: string;
            /** @enum {string} */
            orgKind?: "SELLER" | "PROVIDER";
            /** @description The member's role now */
            roleKey?: string;
            /** Format: uuid */
            userId?: string;
            /** @description Was already a member; the role was left unchanged */
            alreadyMember?: boolean;
        };
        PasswordSignInRequest: {
            email: string;
            password: string;
            /** @description Token from an earlier verification with trustDevice=true; skips the second factor for 30 days */
            trustedDeviceToken?: string;
            /**
             * @description End user's IP address (throttling, audit)
             * @example 203.0.113.7
             */
            clientIp: string;
            /** @description End user's User-Agent */
            userAgent?: string;
        };
        SecondFactorOptions: {
            totp?: boolean;
            sms?: boolean;
            /** @description Masked phone, e.g. •••• 0142 */
            smsHint?: string;
            recoveryCode?: boolean;
        };
        /** @description MFA_REQUIRED: continue with challengeId and one of factors. AUTHENTICATED: redeem loginTicket (single use, 60 s) on /oauth2/authorize. */
        SignInStepResponse: {
            /** @enum {string} */
            status?: "MFA_REQUIRED" | "AUTHENTICATED";
            /** Format: uuid */
            challengeId?: string;
            /** Format: date-time */
            challengeExpiresAt?: string;
            factors?: components["schemas"]["SecondFactorOptions"];
            /** Format: uuid */
            userId?: string;
            loginTicket?: string;
            /** Format: date-time */
            loginTicketExpiresAt?: string;
            trustedDeviceToken?: string;
            /** Format: date-time */
            trustedDeviceExpiresAt?: string;
        };
        PasskeyVerifyRequest: {
            /** Format: uuid */
            challengeId: string;
            /** @description PublicKeyCredential from navigator.credentials.get (JSON with base64url fields: id, rawId, type, response.{authenticatorData, clientDataJSON, signature, userHandle}, clientExtensionResults, authenticatorAttachment) */
            credential: Record<string, never>;
            clientIp: string;
            userAgent?: string;
        };
        PasskeyOptionsRequest: {
            clientIp: string;
            userAgent?: string;
        };
        PasskeyOptionsResponse: {
            /** Format: uuid */
            challengeId?: string;
            /** Format: date-time */
            expiresAt?: string;
            /** @description PublicKeyCredentialRequestOptions for navigator.credentials.get (challenge base64url, rpId, timeout ms, userVerification=required, no allowCredentials: discoverable passkeys) */
            publicKey?: Record<string, never>;
        };
        MfaVerifyRequest: {
            /** Format: uuid */
            challengeId: string;
            /** @enum {string} */
            factor: "TOTP" | "SMS" | "RECOVERY_CODE";
            code: string;
            /** @description Return a trustedDeviceToken (30 days) to skip the second factor next time */
            trustDevice?: boolean;
            clientIp: string;
            userAgent?: string;
        };
        MfaSmsRequest: {
            /** Format: uuid */
            challengeId: string;
            clientIp: string;
            userAgent?: string;
        };
        SmsCodeResponse: {
            sentTo?: string;
            /** Format: date-time */
            expiresAt?: string;
        };
        CreateStaffUserRequest: {
            firstName: string;
            lastName: string;
            /** Format: email */
            email: string;
            phone: string;
            phoneRegion?: string;
            password: string;
            roles: string[];
        };
        ReasonRequest: {
            reason?: string;
        };
        /** @description remaining counts TOTP secrets per key id after the run; rotation is complete when it lists only keyId */
        DataProtectionRotation: {
            keyId?: string;
            /** Format: int32 */
            resealed?: number;
            /** Format: int32 */
            skipped?: number;
            /** Format: int32 */
            failed?: number;
            remaining?: {
                [key: string]: number;
            };
        };
        /** @description Unified sign-up. The role is never a form field: intent decides it. */
        SignUpRequest: {
            firstName: string;
            lastName: string;
            /** Format: email */
            email: string;
            /**
             * @description Mobile number, E.164 (+17132490142) or national with phoneRegion
             * @example +17132490142
             */
            phone: string;
            /**
             * @description ISO 3166 region from the country picker, used for national-format numbers
             * @example US
             */
            phoneRegion?: string;
            password: string;
            confirmPassword: string;
            termsAccepted: boolean;
            /** @description Separate, optional, unticked SMS marketing/notification consent */
            smsConsent?: boolean;
            /**
             * @description Entry point: shop (default), sell or provide
             * @enum {string}
             */
            intent?: "shop" | "sell" | "provide";
            /** @description Organisation invitation token from the invite link (token query parameter). The email must be the invited one; the account starts verified and joins the organisation. */
            invitationToken?: string;
        };
        SignUpResponse: {
            /** Format: uuid */
            userId?: string;
            email?: string;
            /** @description false when the sign-up came from an invitation link */
            verificationRequired?: boolean;
            /**
             * Format: date-time
             * @description null when no code was sent
             */
            codeExpiresAt?: string;
        };
        VerifyEmailRequest: {
            /** Format: email */
            email: string;
            code: string;
        };
        ResendCodeRequest: {
            /** Format: email */
            email: string;
        };
        CodeSentResponse: {
            /** Format: date-time */
            codeExpiresAt?: string;
        };
        /** @description Null fields are left unchanged */
        UpdateProfileRequest: {
            firstName?: string;
            lastName?: string;
            phone?: string;
            phoneRegion?: string;
            termsAccepted?: boolean;
            smsConsent?: boolean;
        };
        /** @description One member of the organisation's team */
        OrgMember: {
            /** Format: uuid */
            userId?: string;
            firstName?: string;
            lastName?: string;
            /**
             * @description First and last name
             * @example Adaeze Okafor
             */
            name?: string;
            email?: string;
            /** @example seller.admin */
            roleKey?: string;
            /**
             * @description Role display name
             * @example Admin
             */
            roleLabel?: string;
            /** @enum {string} */
            status?: "ACTIVE" | "DISABLED";
            /**
             * Format: date-time
             * @description When the membership was created
             */
            joinedAt?: string;
        };
        OrgMemberList: {
            orgId?: string;
            items?: components["schemas"]["OrgMember"][];
        };
        OrgInvitationList: {
            orgId?: string;
            items?: components["schemas"]["OrgInvitation"][];
        };
        Session: {
            id?: string;
            clientId?: string;
            clientName?: string;
            /** Format: date-time */
            signedInAt?: string;
            /** Format: date-time */
            lastActiveAt?: string;
            /** Format: date-time */
            expiresAt?: string;
            current?: boolean;
        };
        Passkey: {
            credentialId?: string;
            label?: string;
            /** Format: date-time */
            createdAt?: string;
            /** Format: date-time */
            lastUsedAt?: string;
        };
        MfaStatus: {
            totp?: boolean;
            sms?: boolean;
            smsPhone?: string;
            /** Format: int32 */
            recoveryCodesLeft?: number;
            /** Format: int32 */
            passkeys?: number;
            required?: boolean;
            mayDisable?: boolean;
        };
        LinkedAccount: {
            provider?: string;
            email?: string;
            /** Format: date-time */
            linkedAt?: string;
            canUnlink?: boolean;
        };
        LinkedAccounts: {
            accounts?: components["schemas"]["LinkedAccount"][];
            hasPassword?: boolean;
            /** Format: int32 */
            passkeys?: number;
            /** @description Start linking by visiting this path on the authorization server while signed in: /oauth2/authorization/{provider} */
            linkPathTemplate?: string;
        };
        /** @description false = drop the channel (except security messages) */
        ChannelPreferences: {
            email?: boolean;
            sms?: boolean;
            push?: boolean;
        };
        PushTarget: {
            token?: string;
            /**
             * @description Push transport
             * @enum {string}
             */
            platform?: "fcm" | "apns";
            /** @enum {string} */
            app?: "customer" | "driver";
            /** @enum {string} */
            os?: "ios" | "android" | "web";
        };
        UserContact: {
            userId?: string;
            firstName?: string;
            email?: string;
            emailVerified?: boolean;
            /** @description E.164 */
            phone?: string;
            /** @description false: the number was never confirmed with a code; do not text it */
            phoneVerified?: boolean;
            /** @description BCP 47, null = default */
            locale?: string;
            /** @description IANA zone, null = market zone */
            timeZone?: string;
            /** @description ISO country, e.g. US */
            market?: string;
            pushTokens?: components["schemas"]["PushTarget"][];
            preferences?: components["schemas"]["ChannelPreferences"];
            marketingOptIn?: boolean;
        };
        OrgOwners: {
            orgId?: string;
            ownerUserIds?: string[];
        };
        IdentityProvider: {
            id?: string;
            displayName?: string;
            authorizationPath?: string;
        };
        UserPage: {
            items?: components["schemas"]["UserAccess"][];
            /** Format: int32 */
            page?: number;
            /** Format: int32 */
            size?: number;
            /** Format: int64 */
            total?: number;
        };
        Permission: {
            key?: string;
            module?: string;
            description?: string;
        };
        /** @description Rows: modules; columns: roles; cells: none/view/edit/approve/full */
        ModuleMatrix: {
            portal?: string;
            modules?: components["schemas"]["ModuleRow"][];
            roles?: components["schemas"]["Role"][];
        };
        ModuleRow: {
            key?: string;
            label?: string;
            levels?: {
                [key: string]: string;
            };
        };
        /** @description secretsByKey counts TOTP secrets per key id; 'plaintext' counts legacy rows not sealed yet */
        DataProtectionStatus: {
            currentKeyId?: string;
            secretsByKey?: {
                [key: string]: number;
            };
        };
        AccessChange: {
            /** Format: int64 */
            id?: number;
            /** Format: date-time */
            at?: string;
            actorId?: string;
            action?: string;
            /** Format: uuid */
            targetUserId?: string;
            targetRole?: string;
            subject?: string;
            detail?: string;
        };
        AccessChangePage: {
            items?: components["schemas"]["AccessChange"][];
            /** Format: int32 */
            page?: number;
            /** Format: int32 */
            size?: number;
        };
        UnregisterDeviceRequest: {
            token: string;
        };
    };
    responses: never;
    parameters: never;
    requestBodies: never;
    headers: never;
    pathItems: never;
}
export type $defs = Record<string, never>;
export interface operations {
    signUp: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["SignUpRequest"];
            };
        };
        responses: {
            /** @description Created */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["SignUpResponse"];
                };
            };
        };
    };
    verify_1: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["VerifyEmailRequest"];
            };
        };
        responses: {
            /** @description No Content */
            204: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    resend: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["ResendCodeRequest"];
            };
        };
        responses: {
            /** @description Accepted */
            202: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["CodeSentResponse"];
                };
            };
        };
    };
    log: {
        parameters: {
            query?: {
                userId?: string;
                page?: number;
                size?: number;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["AccessChangePage"];
                };
            };
        };
    };
    dataProtectionStatus: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["DataProtectionStatus"];
                };
            };
        };
    };
    rotateDataProtectionKey: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["DataProtectionRotation"];
                };
            };
        };
    };
    all: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["IdentityProviderAdmin"][];
                };
            };
        };
    };
    set: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                providerId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["SetProviderEnabledRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["IdentityProviderAdmin"];
                };
            };
        };
    };
    matrix: {
        parameters: {
            query?: {
                portal?: string;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ModuleMatrix"];
                };
            };
        };
    };
    setModuleAccess: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                portal: string;
                moduleKey: string;
                roleKey: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["SetModuleAccessRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ModuleAccess"];
                };
            };
        };
    };
    permissions: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Permission"][];
                };
            };
        };
    };
    roles: {
        parameters: {
            query?: {
                portal?: string;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Role"][];
                };
            };
        };
    };
    addRolePermission: {
        parameters: {
            query?: {
                reason?: string;
            };
            header?: never;
            path: {
                roleKey: string;
                permissionKey: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Role"];
                };
            };
        };
    };
    removeRolePermission: {
        parameters: {
            query?: {
                reason?: string;
            };
            header?: never;
            path: {
                roleKey: string;
                permissionKey: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Role"];
                };
            };
        };
    };
    users: {
        parameters: {
            query?: {
                q?: string;
                page?: number;
                size?: number;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["UserPage"];
                };
            };
        };
    };
    create: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["CreateStaffUserRequest"];
            };
        };
        responses: {
            /** @description Created */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["UserAccess"];
                };
            };
        };
    };
    user: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                userId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["UserAccess"];
                };
            };
        };
    };
    disable: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                userId: string;
            };
            cookie?: never;
        };
        requestBody?: {
            content: {
                "application/json": components["schemas"]["ReasonRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["UserAccess"];
                };
            };
        };
    };
    enable: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                userId: string;
            };
            cookie?: never;
        };
        requestBody?: {
            content: {
                "application/json": components["schemas"]["ReasonRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["UserAccess"];
                };
            };
        };
    };
    grant: {
        parameters: {
            query?: {
                reason?: string;
            };
            header?: never;
            path: {
                userId: string;
                permissionKey: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["UserAccess"];
                };
            };
        };
    };
    revoke: {
        parameters: {
            query?: {
                reason?: string;
            };
            header?: never;
            path: {
                userId: string;
                permissionKey: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["UserAccess"];
                };
            };
        };
    };
    assignRole: {
        parameters: {
            query?: {
                reason?: string;
            };
            header?: never;
            path: {
                userId: string;
                roleKey: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["UserAccess"];
                };
            };
        };
    };
    removeRole: {
        parameters: {
            query?: {
                reason?: string;
            };
            header?: never;
            path: {
                userId: string;
                roleKey: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["UserAccess"];
                };
            };
        };
    };
    sms: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["MfaSmsRequest"];
            };
        };
        responses: {
            /** @description Accepted */
            202: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["SmsCodeResponse"];
                };
            };
        };
    };
    verify: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["MfaVerifyRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["SignInStepResponse"];
                };
            };
        };
    };
    passkeyOptions: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["PasskeyOptionsRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["PasskeyOptionsResponse"];
                };
            };
        };
    };
    passkeyVerify: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["PasskeyVerifyRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["SignInStepResponse"];
                };
            };
        };
    };
    password: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["PasswordSignInRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["SignInStepResponse"];
                };
            };
        };
    };
    enabled: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["IdentityProvider"][];
                };
            };
        };
    };
    owners: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                orgId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["OrgOwners"];
                };
            };
        };
    };
    contact: {
        parameters: {
            query?: {
                /** @description Only push tokens of this app */
                app?: "customer" | "driver";
            };
            header?: never;
            path: {
                userId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["UserContact"];
                };
            };
        };
    };
    accept: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["InvitationTokenRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["AcceptedInvitation"];
                };
            };
        };
    };
    lookup: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["InvitationTokenRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["InvitationPreview"];
                };
            };
        };
    };
    me: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Me"];
                };
            };
        };
    };
    update: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["UpdateProfileRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Me"];
                };
            };
        };
    };
    selectOrg: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["SelectOrgRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Me"];
                };
            };
        };
    };
    list_1: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Address"][];
                };
            };
        };
    };
    add: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["AddressRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Address"];
                };
            };
        };
    };
    remove: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    update_1: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["AddressRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Address"];
                };
            };
        };
    };
    makeDefault: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Address"];
                };
            };
        };
    };
    list: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Device"][];
                };
            };
        };
    };
    register: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["RegisterDeviceRequest"];
            };
        };
        responses: {
            /** @description Created */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Device"];
                };
            };
        };
    };
    unregister: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["UnregisterDeviceRequest"];
            };
        };
        responses: {
            /** @description No Content */
            204: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    remove_1: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                deviceId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description No Content */
            204: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    linked: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["LinkedAccounts"];
                };
            };
        };
    };
    unlink: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                provider: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["LinkedAccounts"];
                };
            };
        };
    };
    status: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["MfaStatus"];
                };
            };
        };
    };
    regenerate: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["RecoveryCodes"];
                };
            };
        };
    };
    startSms: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Accepted */
            202: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["CodeSent"];
                };
            };
        };
    };
    disableSms: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description No Content */
            204: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    confirmSms: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["CodeRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["RecoveryCodes"];
                };
            };
        };
    };
    startTotp: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["TotpSetup"];
                };
            };
        };
    };
    disableTotp: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description No Content */
            204: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    confirmTotp: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["CodeRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["RecoveryCodes"];
                };
            };
        };
    };
    get: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["NotificationPreferences"];
                };
            };
        };
    };
    put: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["NotificationPreferencesRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["NotificationPreferences"];
                };
            };
        };
    };
    passkeys: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Passkey"][];
                };
            };
        };
    };
    removePasskey: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                credentialId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description No Content */
            204: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    changePassword: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["ChangePasswordRequest"];
            };
        };
        responses: {
            /** @description No Content */
            204: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    startPhoneVerification: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Accepted */
            202: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["PhoneCodeSent"];
                };
            };
        };
    };
    confirmPhoneVerification: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["CodeRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["PhoneVerification"];
                };
            };
        };
    };
    startProviding: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Me"];
                };
            };
        };
    };
    startSelling: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Me"];
                };
            };
        };
    };
    sessions: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Session"][];
                };
            };
        };
    };
    revokeOthers: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["RevokedSessions"];
                };
            };
        };
    };
    revoke_1: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                sessionId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description No Content */
            204: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    upsert: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                orgId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["UpsertOrgRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Organization"];
                };
            };
        };
    };
    pendingInvitations: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                orgId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["OrgInvitationList"];
                };
            };
        };
    };
    invite: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                orgId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["InviteMemberRequest"];
            };
        };
        responses: {
            /** @description Created */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["OrgInvitation"];
                };
            };
        };
    };
    revokeInvitation: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                orgId: string;
                invitationId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description No Content */
            204: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    members: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                orgId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["OrgMemberList"];
                };
            };
        };
    };
    setMember: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                orgId: string;
                userId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["SetMemberRequest"];
            };
        };
        responses: {
            /** @description No Content */
            204: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    removeMember: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                orgId: string;
                userId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description No Content */
            204: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
}
