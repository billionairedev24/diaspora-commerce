-- S205: organisations are owned by seller-service, whose ids are strings such as org_3k2j9x8q7w6e5r4t3y2u.
-- The token "org" claim must equal that id, because every resource server compares Actor.orgId() verbatim.
-- Existing uuid ids are kept as their canonical text form, so tokens and memberships issued before stay valid.
alter table org_membership drop constraint org_membership_org_id_fkey;

alter table organization alter column id type varchar(80) using id::text;
alter table org_membership alter column org_id type varchar(80) using org_id::text;
alter table user_account alter column active_org_id type varchar(80) using active_org_id::text;

alter table org_membership
    add constraint org_membership_org_id_fkey foreign key (org_id) references organization (id) on delete cascade;

-- Market (ISO country of the seller's business, for example US) mirrored from seller.org.approved / updated.
alter table organization add column market varchar(8);
