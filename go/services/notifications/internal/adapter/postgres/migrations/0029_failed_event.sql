-- Consumed events that could not be processed (a permanent upstream 4xx, or a transient failure that outlasted every
-- retry). The consumer records the event here, commits its offset and carries on, so one poison message never blocks the
-- ones behind it. payload is kept so the event can be replayed once the cause is fixed; replayed_at marks it handled.
CREATE TABLE failed_event (
  consumer     text        NOT NULL,
  event_id     text        NOT NULL,
  event_type   text        NOT NULL,
  aggregate_id text        NOT NULL DEFAULT '',
  reason       text        NOT NULL,
  error_class  text        NOT NULL,
  upstream_status integer  NOT NULL DEFAULT 0,
  upstream_code   text     NOT NULL DEFAULT '',
  attempts     integer     NOT NULL DEFAULT 0,
  payload      bytea       NOT NULL,
  failed_at    timestamptz NOT NULL DEFAULT now(),
  replayed_at  timestamptz,
  PRIMARY KEY (consumer, event_id)
);

CREATE INDEX failed_event_open ON failed_event (failed_at) WHERE replayed_at IS NULL;
