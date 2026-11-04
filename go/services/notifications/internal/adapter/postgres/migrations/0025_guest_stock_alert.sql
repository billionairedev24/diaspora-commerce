-- Guest back-in-stock alerts: a visitor who is not signed in subscribes with just an email address. The alert has no
-- user_id and is unique per (email, atin) (email is stored lower-cased and validated by the service). Stored per
-- alert: email, ATIN, market, locale, created time and the catalog's product title; nothing the visitor typed except
-- the address. Open alerts are purged after 180 days and sent ones after 14 (StockAlerts purge worker).

ALTER TABLE stock_alert ALTER COLUMN user_id DROP NOT NULL;
ALTER TABLE stock_alert ADD CONSTRAINT stock_alert_owner CHECK (user_id IS NOT NULL OR email IS NOT NULL);

CREATE UNIQUE INDEX stock_alert_guest ON stock_alert (email, atin) WHERE user_id IS NULL;
-- open-alert cap per owner
CREATE INDEX stock_alert_guest_open ON stock_alert (email) WHERE user_id IS NULL AND notified_at IS NULL;
CREATE INDEX stock_alert_user_open ON stock_alert (user_id) WHERE user_id IS NOT NULL AND notified_at IS NULL;
-- retention purge
CREATE INDEX stock_alert_open_age ON stock_alert (created_at) WHERE notified_at IS NULL;
CREATE INDEX stock_alert_sent_age ON stock_alert (notified_at) WHERE notified_at IS NOT NULL;
