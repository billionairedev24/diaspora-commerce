-- notifications schema: outbox-style notification table, inbox, dead letters, receipts, templates, consent.

CREATE FUNCTION status_rank(s text) RETURNS int LANGUAGE sql IMMUTABLE AS $$
  SELECT CASE s
    WHEN 'queued' THEN 0 WHEN 'sent' THEN 1 WHEN 'delivered' THEN 2
    WHEN 'bounced' THEN 3 WHEN 'failed' THEN 3 WHEN 'suppressed' THEN 3
    WHEN 'complained' THEN 4 ELSE -1 END
$$;

CREATE TABLE notification (
  id                  uuid PRIMARY KEY,
  idempotency_key     text NOT NULL UNIQUE,
  event_id            text,
  template_key        text NOT NULL,
  template_version    int  NOT NULL,
  locale              text NOT NULL,
  channel             text NOT NULL CHECK (channel IN ('email','sms','push','chat')),
  category            text NOT NULL CHECK (category IN ('security','transactional','reminder','marketing')),
  market              text NOT NULL,
  recipient_id        text,
  address             text NOT NULL,
  time_zone           text,
  subject             text,
  body_html           text,
  body_text           text,
  headers             jsonb,
  status              text NOT NULL,
  status_detail       text,
  attempts            int  NOT NULL DEFAULT 0,
  next_attempt_at     timestamptz NOT NULL,
  last_error          text,
  provider            text,
  provider_message_id text,
  created_at          timestamptz NOT NULL DEFAULT now(),
  updated_at          timestamptz NOT NULL DEFAULT now(),
  sent_at             timestamptz
);
CREATE INDEX notification_due ON notification (next_attempt_at) WHERE status = 'queued';
CREATE INDEX notification_provider_msg ON notification (provider, provider_message_id);
CREATE INDEX notification_recipient ON notification (recipient_id, created_at DESC);
CREATE INDEX notification_address ON notification (address, created_at DESC);

CREATE TABLE inbox (
  consumer    text NOT NULL,
  event_id    text NOT NULL,
  received_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (consumer, event_id)
);

CREATE TABLE dead_letter (
  id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  notification_id uuid NOT NULL REFERENCES notification (id),
  reason          text NOT NULL,
  created_at      timestamptz NOT NULL DEFAULT now(),
  replayed_at     timestamptz
);
CREATE INDEX dead_letter_open ON dead_letter (created_at DESC) WHERE replayed_at IS NULL;

CREATE TABLE delivery_receipt (
  id                  bigserial PRIMARY KEY,
  notification_id     uuid,
  provider            text NOT NULL,
  provider_message_id text,
  status              text NOT NULL,
  detail              text,
  occurred_at         timestamptz NOT NULL,
  received_at         timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX delivery_receipt_msg ON delivery_receipt (provider, provider_message_id);

CREATE TABLE template (
  key        text NOT NULL,
  locale     text NOT NULL,
  channel    text NOT NULL CHECK (channel IN ('email','sms','push','chat')),
  version    int  NOT NULL,
  category   text NOT NULL CHECK (category IN ('security','transactional','reminder','marketing')),
  subject    text NOT NULL DEFAULT '',
  body_html  text NOT NULL DEFAULT '',
  body_text  text NOT NULL DEFAULT '',
  created_by text NOT NULL DEFAULT 'system',
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (key, locale, channel, version)
);

-- TCPA: current state per number plus an append-only audit trail of every opt-in/opt-out.
CREATE TABLE sms_consent (
  phone        text PRIMARY KEY,
  recipient_id text,
  opted_in     boolean NOT NULL,
  source       text NOT NULL,
  text_version text NOT NULL,
  consented_at timestamptz NOT NULL,
  revoked_at   timestamptz,
  revoked_via  text,
  updated_at   timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE sms_consent_event (
  id           bigserial PRIMARY KEY,
  phone        text NOT NULL,
  action       text NOT NULL CHECK (action IN ('opt_in','opt_out')),
  source       text NOT NULL,
  text_version text,
  occurred_at  timestamptz NOT NULL
);
CREATE INDEX sms_consent_event_phone ON sms_consent_event (phone, occurred_at DESC);

CREATE TABLE preference (
  recipient_key text NOT NULL,
  channel       text NOT NULL,
  category      text NOT NULL,
  enabled       boolean NOT NULL,
  updated_at    timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (recipient_key, channel, category)
);

CREATE TABLE suppression (
  address    text PRIMARY KEY,
  reason     text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);
