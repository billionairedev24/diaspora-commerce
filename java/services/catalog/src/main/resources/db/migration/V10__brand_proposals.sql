-- Seller-proposed brands (S617): the near-duplicates a new brand was typed next to, for the staff review queue
-- ("possible duplicate of Nike"), and an index for the per-organisation daily cap on new brands.

CREATE TABLE brand_similar (
    brand_id uuid NOT NULL,
    similar_id uuid NOT NULL,
    CONSTRAINT brand_similar_pkey PRIMARY KEY (brand_id, similar_id),
    CONSTRAINT brand_similar_brand_id_fkey FOREIGN KEY (brand_id) REFERENCES brand(id) ON DELETE CASCADE,
    CONSTRAINT brand_similar_similar_id_fkey FOREIGN KEY (similar_id) REFERENCES brand(id) ON DELETE CASCADE
);

CREATE INDEX brand_similar_similar ON brand_similar USING btree (similar_id);

CREATE INDEX brand_org_created ON brand USING btree (created_by_org, created_at) WHERE (created_by_org IS NOT NULL);
