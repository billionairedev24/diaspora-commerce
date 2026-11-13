-- Search-first add-a-product (no barcode needed): what a seller answered on the "Is your product already on AfroTransact?" step.
-- SELL_EXISTING: the seller sells a page that exists. NONE_OF_THESE: the seller adds a new page; when a likely page was shown
-- next to it, review_state is OPEN so staff can compare the new page with it later (a possible duplicate, never a block).
CREATE TABLE product_match_decision (
    id                  uuid PRIMARY KEY,
    seller_org_id       character varying(80) NOT NULL,
    choice              character varying(16) NOT NULL,
    query               character varying(200) NOT NULL,
    category_id         uuid,
    atin                character(10),
    shown               text NOT NULL DEFAULT '',
    likely_duplicate_of character(10),
    review_state        character varying(8) NOT NULL DEFAULT 'NONE',
    decided_at          timestamp with time zone NOT NULL,
    CONSTRAINT product_match_decision_choice_check CHECK (choice IN ('SELL_EXISTING', 'NONE_OF_THESE')),
    CONSTRAINT product_match_decision_state_check CHECK (review_state IN ('NONE', 'OPEN', 'DISTINCT', 'DUPLICATE'))
);
-- The daily limit of "None of these" answers per organisation.
CREATE INDEX product_match_decision_org ON product_match_decision (seller_org_id, decided_at DESC);
-- Staff's queue of possible duplicates, oldest first.
CREATE INDEX product_match_decision_review ON product_match_decision (decided_at) WHERE review_state = 'OPEN';
