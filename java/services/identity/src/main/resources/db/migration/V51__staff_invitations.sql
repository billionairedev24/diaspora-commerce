-- Staff invitations: replace creating a staff account with a temporary password. Staff with access:admin invite a
-- person by name, work email and platform role (with a reason); the invitee opens the emailed link and sets their own
-- password, which creates the account. Only the SHA-256 of the single-use token is stored, so a stolen database cannot
-- be used to accept an invitation; the token travels once in the identity.staff-invitation.created event.
-- EXPIRED is derived from expires_at, never stored. Resending issues a new token (the old one stops working).
create table staff_invitation (
    id            uuid primary key,
    email         varchar(254) not null,
    first_name    varchar(80)  not null,
    last_name     varchar(80)  not null,
    role_key      varchar(80)  not null references role (key),
    reason        varchar(300) not null,
    token_hash    char(64)     not null,
    status        varchar(10)  not null check (status in ('PENDING', 'ACCEPTED', 'REVOKED')),
    invited_by    varchar(100) not null,
    created_at    timestamptz  not null,
    expires_at    timestamptz  not null,
    last_sent_at  timestamptz  not null,
    send_count    integer      not null default 1,
    accepted_at   timestamptz,
    accepted_user uuid references user_account (id) on delete set null,
    revoked_at    timestamptz,
    revoked_by    varchar(100),
    version       integer      not null default 0
);
create unique index staff_invitation_token_uq on staff_invitation (token_hash);
-- One open invitation per email (inviting again replaces it).
create unique index staff_invitation_pending_uq on staff_invitation (lower(email)) where status = 'PENDING';
create index staff_invitation_list_idx on staff_invitation (status, created_at desc, id);
