-- Category tax build (docs/engineering/category-tax-design.md): columns and tables only. The tree itself is loaded by
-- V20 (and, afterwards, by the staff importer), never rows staff own are written here.

-- A category node carries the tax profile it uses (the category code of tax-returns) and the gate status of that
-- profile: sellers publish only into ACTIVE categories.
alter table category
    add column tax_profile_id varchar(80),
    add column tax_status     varchar(20) check (tax_status in
        ('ACTIVE', 'NEEDS_APPROVAL', 'BLOCKED_BUNDLE', 'REFINE_CODE', 'CONFLICT_OPEN'));
create index category_tax_profile on category (tax_profile_id) where tax_profile_id is not null;

-- The tax decision attaches to the variant ATIN: the variant's product facts, its gate status, what is missing and the
-- decision summary tax-returns returned. The seller's own behaviour facts live in product.listing (sellerBehaviour).
-- sellerSku is optional on input: an empty one is generated (stable, organisation-prefixed) and flagged.
alter table product_variant
    add column seller_sku_generated boolean     not null default false,
    add column tax_facts            jsonb       not null default '{}'::jsonb,
    add column tax_status           varchar(20) not null default 'MISSING_FACTS',
    add column missing_tax_facts    jsonb       not null default '[]'::jsonb,
    add column tax_decision         jsonb;
create index product_variant_tax_status on product_variant (tax_status);

-- Proof for an exemption fact (label photo, Drug Facts). A new upload of the same fact is a new version; earlier files
-- stay for audit. The file bytes sit behind the EvidenceStore port (this table in v1, object storage later).
create table variant_tax_evidence (
    id           uuid primary key,
    variant_atin char(10)     not null,
    owner_org    varchar(80)  not null,
    fact_id      varchar(60)  not null,
    version      int          not null,
    file_name    varchar(200) not null,
    content_type varchar(80)  not null,
    bytes        bigint       not null check (bytes > 0),
    content      bytea        not null,
    uploaded_by  varchar(120) not null,
    uploaded_at  timestamptz  not null,
    unique (variant_atin, fact_id, version)
);
create index variant_tax_evidence_variant on variant_tax_evidence (variant_atin, fact_id, version desc);
