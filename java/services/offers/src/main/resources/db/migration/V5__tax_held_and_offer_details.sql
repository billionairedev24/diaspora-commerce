-- S383, S384: an offer waits (TAX_HELD) until the offering seller has a RESOLVED tax decision on the ATIN, carries a note on
-- its condition and, for Fulfilled by AfroTransact, the box the seller ships it in.

ALTER TABLE offer DROP CONSTRAINT offer_status_check;
ALTER TABLE offer ADD CONSTRAINT offer_status_check CHECK (status IN ('ACTIVE', 'PAUSED', 'WITHDRAWN', 'TAX_HELD'));

ALTER TABLE offer ADD COLUMN condition_note varchar(1000);
ALTER TABLE offer ADD COLUMN package_length_in numeric(7, 2);
ALTER TABLE offer ADD COLUMN package_width_in numeric(7, 2);
ALTER TABLE offer ADD COLUMN package_height_in numeric(7, 2);
ALTER TABLE offer ADD COLUMN package_weight_lb numeric(7, 2);
ALTER TABLE offer ADD CONSTRAINT offer_package_check CHECK (
    (package_length_in IS NULL AND package_width_in IS NULL AND package_height_in IS NULL AND package_weight_lb IS NULL)
    OR (package_length_in > 0 AND package_width_in > 0 AND package_height_in > 0 AND package_weight_lb > 0));

-- The seller's own tax behaviour answers for this listing (sold_heated ...), kept so the decision can be asked again.
ALTER TABLE offer ADD COLUMN seller_behaviour jsonb NOT NULL DEFAULT '{}'::jsonb;

-- Why the offer waits, and why a live offer may be live as far as tax goes. NULL tax_basis = not verified yet. Offers that
-- already exist are NOT given a basis here (this migration creates no decision and guesses nothing): the tax release job
-- asks tax-returns about each one, stamps the ones with a RESOLVED decision and holds the others.
ALTER TABLE offer ADD COLUMN held_reason varchar(60);
ALTER TABLE offer ADD COLUMN tax_basis varchar(30);
ALTER TABLE offer ADD CONSTRAINT offer_tax_basis_check CHECK (tax_basis IS NULL OR tax_basis IN ('OWNER_PAGE', 'SELLER_DECISION'));
ALTER TABLE offer ADD CONSTRAINT offer_held_reason_check CHECK ((status = 'TAX_HELD') = (held_reason IS NOT NULL));

CREATE INDEX offer_tax_held ON offer (atin) WHERE status = 'TAX_HELD';
CREATE INDEX offer_tax_unverified ON offer (created_at) WHERE status = 'ACTIVE' AND tax_basis IS NULL;

-- Staff-edited thresholds (one row, key "policy"); the configured defaults apply until the first save.
CREATE TABLE offer_setting (
    key        varchar(60) PRIMARY KEY,
    value      jsonb NOT NULL,
    updated_by varchar(120),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version    bigint NOT NULL DEFAULT 1
);
