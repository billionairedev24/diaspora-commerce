-- Back-office alert topics (seller.orders, seller.payouts, staff.escalations, …) muted per channel by a member, and the
-- member's own quiet hours for SMS and push. A missing row means "on" (locked channels are always on).
CREATE TABLE alert_preference (
  recipient_key text NOT NULL,
  topic         text NOT NULL,
  channel       text NOT NULL,
  enabled       boolean NOT NULL,
  updated_at    timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (recipient_key, topic, channel)
);

CREATE TABLE personal_quiet_hours (
  recipient_key text PRIMARY KEY,
  enabled       boolean NOT NULL,
  start_minute  integer NOT NULL CHECK (start_minute BETWEEN 0 AND 1439),
  end_minute    integer NOT NULL CHECK (end_minute BETWEEN 0 AND 1439),
  updated_at    timestamptz NOT NULL DEFAULT now()
);
