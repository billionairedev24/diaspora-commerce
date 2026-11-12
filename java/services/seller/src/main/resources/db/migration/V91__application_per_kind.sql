-- A current seller account may apply to offer services (and the other way round) without a new login:
-- one application per applicant AND kind (SELLER, PROVIDER). Each kind is its own organisation, as before.
alter table application drop constraint application_applicant_user_id_key;
alter table application add constraint application_applicant_kind_key unique (applicant_user_id, kind);
