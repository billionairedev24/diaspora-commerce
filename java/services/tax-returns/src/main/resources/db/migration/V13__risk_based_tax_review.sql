-- Risk-based tax review (docs/engineering/listing-rules.md D7). The setting taxReview.mode is OFF by default (ALL): it is
-- stored as the key tax.review in tax_setting only when staff change it, so a database without the row keeps today's
-- behaviour (every classification waits for admin review).

-- The review settings value is JSON and holds the always-review list, which can be long.
alter table tax_setting alter column value type text;
alter table tax_setting_history alter column old_value type text;
alter table tax_setting_history alter column new_value type text;

-- An audit sample item is approved (live) by the system and waits for a reviewer to confirm or reclassify it.
alter table variant_classification add column audit_sampled_at timestamptz;
create index variant_classification_audit_queue on variant_classification (audit_sampled_at)
    where audit_sampled_at is not null;

alter table variant_classification_audit drop constraint if exists variant_classification_audit_action_check;
alter table variant_classification_audit add constraint variant_classification_audit_action_check
    check (action in ('FACTS_CHANGED', 'SUBMITTED', 'APPROVED', 'REJECTED', 'RECLASSIFIED', 'MARKED_STALE',
        'EVIDENCE_ADDED', 'INFORMATION_REQUESTED', 'AUDIT_SAMPLED', 'AUDIT_CONFIRMED'));
