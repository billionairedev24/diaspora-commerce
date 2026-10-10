-- S202: TOTP secrets are encrypted at rest (envelope encryption, AES-256-GCM; see README "Data protection").
-- secret_ciphertext holds the envelope (version | key id | wrapped data key | nonce | ciphertext + tag) and
-- secret_key_id the id of the key-encryption key it is sealed under, so rotation can find rows on older keys.
-- The legacy plaintext column is no longer written: the application seals existing rows at start-up and empties
-- it (idempotent migrator). It is dropped by a later migration once every environment has run this release.
alter table mfa_totp add column secret_ciphertext bytea;
alter table mfa_totp add column secret_key_id varchar(200);
alter table mfa_totp alter column secret drop not null;
alter table mfa_totp add constraint mfa_totp_secret_present check (secret is not null or secret_ciphertext is not null);
alter table mfa_totp add constraint mfa_totp_key_id_present check (secret_ciphertext is null or secret_key_id is not null);
create index mfa_totp_secret_key_idx on mfa_totp (secret_key_id);

comment on column mfa_totp.secret is
    'DEPRECATED legacy plaintext TOTP secret: never written, emptied at start-up by the data-protection migrator';
comment on column mfa_totp.secret_ciphertext is 'TOTP secret sealed by SecretCipher (AES-256-GCM envelope)';
comment on column mfa_totp.secret_key_id is 'Key-encryption key id of secret_ciphertext (rotation)';
