-- S385, S386, S387: change requests, no-barcode exemptions, merged-page redirects and the staff-edited settings.

-- Staff-edited thresholds (one row, key "policy"); the configured defaults apply until the first save.
CREATE TABLE catalog_setting (
    key        character varying(60) PRIMARY KEY,
    value      jsonb NOT NULL,
    updated_by character varying(120),
    updated_at timestamp with time zone NOT NULL DEFAULT now(),
    version    bigint NOT NULL DEFAULT 1
);

-- A seller's request to change a page that is not theirs, report a wrong match or a duplicate page. Filing never changes the
-- page; staff decide with a reason, and the decision row is the audit trail (who, what, why, when).
CREATE TABLE product_change_request (
    id               uuid PRIMARY KEY,
    product_id       uuid NOT NULL REFERENCES product (id),
    atin             character(10) NOT NULL,
    variant_atin     character(10),
    gtin14           character(14),
    type             character varying(20) NOT NULL,
    field            character varying(60) NOT NULL DEFAULT '',
    payload          jsonb NOT NULL DEFAULT '{}'::jsonb,
    note             text,
    status           character varying(12) NOT NULL,
    requested_by_org character varying(80) NOT NULL,
    requested_by     character varying(120),
    requested_at     timestamp with time zone NOT NULL,
    sla_due_at       timestamp with time zone,
    decided_by       character varying(120),
    decided_at       timestamp with time zone,
    reason           text,
    resolution       character varying(20),
    version          bigint NOT NULL DEFAULT 0,
    CONSTRAINT product_change_request_type_check CHECK (type IN ('IMAGE_ADD', 'IMAGE_REPLACE', 'TITLE', 'ATTRIBUTE',
        'DESCRIPTION', 'CATEGORY', 'BRAND', 'NEW_VARIANT', 'WRONG_MATCH', 'DUPLICATE_PAGE')),
    CONSTRAINT product_change_request_status_check CHECK (status IN ('PENDING', 'APPLIED', 'REJECTED', 'WITHDRAWN')),
    CONSTRAINT product_change_request_decision_check CHECK (
        status = 'PENDING' OR (decided_by IS NOT NULL AND decided_at IS NOT NULL))
);
-- One open request per page, type, field and requesting organisation.
CREATE UNIQUE INDEX product_change_request_open ON product_change_request (product_id, type, field, requested_by_org)
    WHERE status = 'PENDING';
CREATE INDEX product_change_request_queue ON product_change_request (status, requested_at);
CREATE INDEX product_change_request_org ON product_change_request (requested_by_org, requested_at DESC);
CREATE INDEX product_change_request_product ON product_change_request (product_id, requested_at DESC);
CREATE INDEX product_change_request_wrong_match ON product_change_request (gtin14)
    WHERE status = 'PENDING' AND type = 'WRONG_MATCH';

-- A no-barcode item that needs staff approval before it can leave PENDING_EXEMPTION.
CREATE TABLE gtin_exemption_request (
    id               uuid PRIMARY KEY,
    product_id       uuid NOT NULL REFERENCES product (id),
    product_atin     character(10) NOT NULL,
    requested_by_org character varying(80) NOT NULL,
    seller_reason    text,
    required_because character varying(30) NOT NULL,
    status           character varying(10) NOT NULL,
    requested_at     timestamp with time zone NOT NULL,
    sla_due_at       timestamp with time zone,
    decided_by       character varying(120),
    decided_at       timestamp with time zone,
    reason           text,
    version          bigint NOT NULL DEFAULT 0,
    CONSTRAINT gtin_exemption_request_because_check CHECK (required_because IN ('BRAND_GATED_CATEGORY', 'KNOWN_GS1_BRAND')),
    CONSTRAINT gtin_exemption_request_status_check CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    CONSTRAINT gtin_exemption_request_decision_check CHECK (
        status = 'PENDING' OR (decided_by IS NOT NULL AND decided_at IS NOT NULL))
);
CREATE UNIQUE INDEX gtin_exemption_request_open ON gtin_exemption_request (product_id) WHERE status = 'PENDING';
CREATE INDEX gtin_exemption_request_queue ON gtin_exemption_request (status, requested_at);
CREATE INDEX gtin_exemption_request_org ON gtin_exemption_request (requested_by_org, requested_at DESC);

-- Where an ATIN went when two duplicate pages were merged (the page ATIN and every variant ATIN of the page that was merged away).
CREATE TABLE atin_redirect (
    from_atin  character(10) PRIMARY KEY,
    to_atin    character(10) NOT NULL,
    merged_by  character varying(120) NOT NULL,
    reason     text,
    merged_at  timestamp with time zone NOT NULL
);
CREATE INDEX atin_redirect_to ON atin_redirect (to_atin);
