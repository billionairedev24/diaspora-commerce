-- Sign-up and MFA: email codes as a second factor, and the "Secure your account" reminder.
--
-- mfa_email_otp: one row per person who turned email codes on (codes go to the verified account email).
-- mfa_setup_prompt: when the optional "Secure your account" prompt was last skipped, so it is only repeated gently.
--
-- SMS: no data change. Existing mfa_sms rows are kept but ignored while at.identity.mfa.sms-enabled=false (default),
-- so nobody is texted at sign-in. People whose only factor was SMS are treated as having no factor (password only,
-- or forced enrolment where MFA is mandatory for them).
create table mfa_email_otp (
    user_id      uuid        primary key references user_account (id) on delete cascade,
    enabled      boolean     not null default true,
    enrolled_at  timestamptz not null,
    last_used_at timestamptz
);

create table mfa_setup_prompt (
    user_id       uuid        primary key references user_account (id) on delete cascade,
    dismissed_at  timestamptz not null,
    dismiss_count integer     not null default 1
);
