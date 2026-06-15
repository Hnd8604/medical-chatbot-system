CREATE TABLE IF NOT EXISTS alerts (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    source varchar(50) NOT NULL,
    alert_type varchar(100) NOT NULL,
    severity varchar(20) NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'OPEN',
    message text NOT NULL,
    metadata_json jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    resolved_at timestamptz,
    resolved_by varchar(100)
);


CREATE INDEX IF NOT EXISTS idx_alerts_status_created_at ON alerts(status, created_at);
CREATE INDEX IF NOT EXISTS idx_alerts_severity_created_at ON alerts(severity, created_at);
CREATE INDEX IF NOT EXISTS idx_alerts_type_status_created ON alerts(alert_type, status, created_at);