-- Back-in-stock alerts (S211): one row per shopper and variant ATIN. notified_at is set when a restock event claims
-- the alert (notified_event_id = that event, so a redelivery finds the same rows again); subscribing again re-arms it.

CREATE TABLE stock_alert (
  id                uuid PRIMARY KEY,
  user_id           text NOT NULL,
  atin              text NOT NULL,
  email             text,
  market            text NOT NULL DEFAULT 'us',
  locale            text NOT NULL DEFAULT 'en',
  product_title     text,
  created_at        timestamptz NOT NULL DEFAULT now(),
  updated_at        timestamptz NOT NULL DEFAULT now(),
  notified_at       timestamptz,
  notified_event_id text,
  UNIQUE (user_id, atin)
);
CREATE INDEX stock_alert_active ON stock_alert (atin, created_at) WHERE notified_at IS NULL;
CREATE INDEX stock_alert_event ON stock_alert (atin, notified_event_id) WHERE notified_event_id IS NOT NULL;
CREATE INDEX stock_alert_user ON stock_alert (user_id, created_at DESC);
