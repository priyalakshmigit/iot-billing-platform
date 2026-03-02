CREATE TABLE IF NOT EXISTS processed_events (
  event_id UUID PRIMARY KEY,
  device_id VARCHAR(50) NOT NULL,
  event_type VARCHAR(40) NOT NULL,
  event_ts TIMESTAMPTZ NOT NULL,
  seen_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS billing_sessions (
  session_id UUID PRIMARY KEY,
  device_id VARCHAR(50) NOT NULL,
  start_ts TIMESTAMPTZ NOT NULL,
  end_ts TIMESTAMPTZ NOT NULL,
  total_bytes BIGINT NOT NULL DEFAULT 0,
  final_status VARCHAR(40) NOT NULL,
  finalized_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  finalized_by_event_id UUID NOT NULL
);

ALTER TABLE billing_sessions
ADD CONSTRAINT fk_billing_sessions_finalized_by_event
FOREIGN KEY (finalized_by_event_id)
REFERENCES processed_events(event_id)
ON DELETE RESTRICT;

CREATE INDEX IF NOT EXISTS idx_billing_sessions_device_finalized
  ON billing_sessions(device_id, finalized_at DESC);