# Sign-up and two-step verification (MFA)

Applies to identity (`java/services/identity`), the hosted pages, the first-party API behind gateway-bff, the web
account pages and the customer and driver apps.

## The rule

**A code is only ever asked of, or sent to, a person who enrolled a factor.** Nobody is texted or emailed a one-time
code at sign-in because of a phone number or email on file. If someone has not set up two-step verification, sign-in is
the password (plus the usual lockout and throttling). There is no SMS fallback and no "email a code to everyone".

Factors (code-based): **authenticator app** (TOTP) and **email codes** (a 6-digit code to the account's verified
email). SMS is a third factor in code but **off** (see [SMS](#sms-is-off-switching-it-on-later)). Passkeys are a
separate first factor (phishing-resistant, they skip the prompt) and are not counted as a second factor. Recovery codes
(10, single use) back up whichever factor is on.

## Flows

### Sign-up (hosted page, and the apps' "create account", which open the hosted page)

1. Create account: name, email, phone (optional), password.
2. Confirm email: 6-digit code emailed to the address.
3. **Secure your account** (`/login/mfa/setup`): offered right after the email is verified. Two methods with plain
   explanations: *Authenticator app* (QR code and setup key, confirm with a code) and *Email codes* (we email a first
   code to the verified address to prove it arrives; confirming turns them on). Recovery codes are shown once (copy,
   download). **Skip for now** continues straight to the app; the choice is remembered and the reminder is gentle
   (see [Reminder](#reminder-for-people-who-skip)).
4. Continue to the app that started sign-up, signed in.

### Sign-in

| Person | Result after the password |
|---|---|
| No factor enrolled, MFA not mandatory | Signed in. Nothing is sent. |
| Authenticator only | Authenticator code. |
| Email codes only | The code is emailed straight away (nothing else could answer it); enter it. |
| Both | Authenticator code by default; "Email me a code instead" sends one (30 s between sends, 5 per hour). |
| Trusted browser (30 days) or passkey sign-in | No prompt. |
| MFA **mandatory** and nothing enrolled | **Forced setup** (`MFA_ENROLMENT_REQUIRED`): no code is sent; the person sets up the authenticator or email codes, sees recovery codes once, and only then gets a session. Cannot be skipped. |

Recovery code works in place of any factor. Forgotten password (`/login/forgot`) is unchanged and never reveals whether
MFA is on: the reset code goes to the email (and a verified phone) and signing in afterwards follows the table above.

### Who is mandatory

* **Staff** (any platform staff role): always. First sign-in without a factor forces setup. Staff can switch methods
  but never turn the last one off.
* **Everyone else**: when their portal's switch is on in the identity admin console, *Authentication* settings:
  `mfaRequiredAccount` (customers), `mfaRequiredSeller`, `mfaRequiredProvider`. The switch now means **"must set up a
  second step at next sign-in"**: nobody is sent a code before they enrol; people who already enrolled answer it as
  before. People who are already signed in are asked at their next sign-in.

### Reminder for people who skip

When nothing is enrolled and MFA is not mandatory, identity reports `setupSuggested` (account security page banner,
`GET /v1/account/security` → `mfa.setupSuggested`) and `mfaSetupSuggested` on the first-party `AUTHENTICATED` step (the
apps may show a dismissable prompt, never a blocking one). "Skip for now" and "Not now"
(`POST /v1/account/mfa/prompt/dismiss`) stop it for `MfaPolicy.SETUP_REMINDER_INTERVAL` (14 days). Owner decision: the
interval.

### Turning things off, trusted browsers

* Turning a factor **off needs the password again**; accounts without a password (social only) are not asked. A wrong
  password is counted by sign-in protection (lockout applies).
* When MFA is mandatory for the person, the last factor cannot be turned off (`403 identity.mfa-required`).
* "Trust this browser for 30 days" is unchanged; turning the last factor off forgets trusted browsers and recovery codes.

## Email codes: how they work

`VerificationPurpose.SIGN_IN_EMAIL` and `EMAIL_OTP_ENROLMENT` through the same code pipeline as the sign-up email code:
6 digits, hashed at rest (SHA-256 with the code id), 10 minutes, at most 5 wrong tries then the code is dead, single use,
constant-time compare, resend at most once per 30 s and 5 per hour per person and purpose, delivered by the outbox event
`identity.verification.requested` (notifications renders the email; locally Mailpit). Codes are only ever sent to the
account's own verified email, inside a valid pending sign-in (hosted session or first-party challenge), so they cannot
be used to bomb an address. Sign-in attempts also feed the per-account and per-IP lockout. Audit log (`access_change_log`):
`mfa.email-otp.enabled`, `mfa.email-otp.disabled`, `mfa.email-otp.used`, `mfa.totp.enabled`, `mfa.totp.disabled`,
`mfa.recovery-code.used`. Table `mfa_email_otp` (user_id, enabled, enrolled_at, last_used_at); `mfa_setup_prompt` holds
the reminder state.

## Recovering an account

1. Lost the authenticator or no email access to codes: sign in with a **recovery code**.
2. No recovery codes: there is **no admin reset of a second factor yet** (owner decision: add `identity:admin` "reset second factor", step-up protected and audited). Until then support removes the row (`mfa_totp` / `mfa_email_otp` / `mfa_recovery_code` of the user) after identity checks; the person then signs in with the password and sets a new factor (forced setup if MFA is mandatory for them).
3. Locked out by too many wrong codes: wait for the lockout window (`Retry-After`), or use "Forgot password".
4. Last resort for local/dev: delete the row from `mfa_totp` / `mfa_email_otp` for the user.

## SMS is off: switching it on later

`at.identity.mfa.sms-enabled` (env `IDENTITY_MFA_SMS_ENABLED`, default **false**). While off: no SMS option in any page or
API response (`smsAvailable=false`, `sms=false`), `POST /v1/me/mfa/sms` answers 409 `identity.sms-unavailable`, and
existing `mfa_sms` rows are **ignored at sign-in** (a person whose only factor was SMS counts as having none: password
only, or forced setup where mandatory). The code and rows are kept. To switch on: set the flag to `true` on identity,
redeploy; existing SMS enrolments work again, the UI shows the SMS row, and phone-on-file is still **never** used as an
implicit factor. The flag also needs the notifications SMS provider configured (Twilio).

Migration note (V40): no data is changed. People who relied on the phone-on-file fallback (staff) will be forced through
setup at their next sign-in.

## Where to look

Identity: `SignInServiceImpl.challenge`, `MfaRequirement`, `DefaultMfaPolicy`, `SecondFactorEnrolmentImpl`,
`MfaSetupPageController`, `FirstPartySignInServiceImpl` (`MFA_ENROLMENT_REQUIRED`, `/v1/auth/mfa/enrol/*`,
`/v1/auth/mfa/email`), `MfaController` (`/v1/me/mfa/*`). Contracts: `contracts/openapi/identity.yaml`,
`gateway-bff.yaml` (`/v1/auth/native/mfa/*`, `/v1/account/mfa/*`). Tests: `SignInServiceImplTest`, `EmailOtpServiceTest`,
`MfaRulesTest`, `SignUpMfaFlowIT`.
