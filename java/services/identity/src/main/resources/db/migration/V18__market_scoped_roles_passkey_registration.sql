-- S248: platform (staff) roles scoped to markets. A platform role without rows here is platform-wide; with rows it
-- covers only those markets (content's market codes, ISO 3166-1 alpha-2). identity.role.granted / revoked carry the
-- market so authz-sync writes market-scoped staff grants (market:<code>#<relation>) instead of platform-wide ones.
create table user_role_market (
    user_id    uuid         not null,
    role_key   varchar(80)  not null,
    market     varchar(2)   not null check (market ~ '^[A-Z]{2}$'),
    granted_at timestamptz  not null default now(),
    granted_by varchar(100) not null,
    primary key (user_id, role_key, market),
    foreign key (user_id, role_key) references user_role (user_id, role_key) on delete cascade
);

-- S016: passkey registration over the first-party API and the mobile apps (bearer token, no browser session). One
-- WebAuthn creation ceremony per row: the challenge the client signs, the WebAuthn user handle it was issued for and
-- the relying party; good for one registration within five minutes.
create table passkey_registration (
    id             uuid primary key,
    user_id        uuid         not null references user_account (id) on delete cascade,
    challenge      varchar(200) not null,
    user_handle    varchar(200) not null,
    rp_id          varchar(253) not null,
    label          varchar(64),
    created_at     timestamptz  not null,
    expires_at     timestamptz  not null,
    completed_at   timestamptz
);
create index passkey_registration_expiry_idx on passkey_registration (expires_at);
