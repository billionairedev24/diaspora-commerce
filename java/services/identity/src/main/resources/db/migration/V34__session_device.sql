-- S291: which browser or app a sign-in came from, for the staff console's sessions table ("Chrome on macOS").
-- The BFF calls identity server-to-server, so the first-party sign-in passes the end user's User-Agent and IP with the
-- sign-in request; they travel on the one-time login ticket and are recorded against the authorization (the sign-in /
-- refresh token) when it is created. Only a SHA-256 hash of the IP is kept, the User-Agent is cut to 255 characters.
alter table login_ticket
    add column user_agent varchar(255),
    add column ip_hash    char(64);

create table session_device (
    authorization_id varchar(100) primary key references oauth2_authorization (id) on delete cascade,
    user_agent       varchar(255),
    ip_hash          char(64),
    recorded_at      timestamptz not null default now()
);
