-- Step-up proofs (S291): a staff member who re-entered their password gets a short-lived proof that the identity admin
-- API accepts (X-Step-Up header) for the most dangerous actions. Only the SHA-256 of the proof is stored, bound to the
-- user; it expires after five minutes and expired rows are deleted whenever a new proof is issued.
create table step_up_proof (
    token_hash char(64) primary key,
    user_id    uuid        not null references user_account (id) on delete cascade,
    created_at timestamptz not null,
    expires_at timestamptz not null
);
create index step_up_proof_expiry_idx on step_up_proof (expires_at);
