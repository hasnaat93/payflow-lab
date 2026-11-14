-- PayFlow Lab core schema.
-- Conventions: money is BIGINT minor units (never floating point), every amount
-- travels with an ISO-4217 currency, timestamps are TIMESTAMPTZ stored in UTC.

CREATE TABLE fee_plans (
    id               UUID PRIMARY KEY,
    code             VARCHAR(40)  NOT NULL,
    name             VARCHAR(120) NOT NULL,
    currency         CHAR(3)      NOT NULL,
    percentage_bps   INTEGER      NOT NULL,
    fixed_fee_minor  BIGINT       NOT NULL DEFAULT 0,
    min_fee_minor    BIGINT,
    max_fee_minor    BIGINT,
    active           BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMPTZ  NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL,
    version          BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uq_fee_plans_code UNIQUE (code),
    CONSTRAINT ck_fee_plans_bps CHECK (percentage_bps BETWEEN 0 AND 10000),
    CONSTRAINT ck_fee_plans_fixed CHECK (fixed_fee_minor >= 0),
    CONSTRAINT ck_fee_plans_min CHECK (min_fee_minor IS NULL OR min_fee_minor >= 0),
    CONSTRAINT ck_fee_plans_max CHECK (max_fee_minor IS NULL OR max_fee_minor >= 0),
    CONSTRAINT ck_fee_plans_min_le_max CHECK (
        min_fee_minor IS NULL OR max_fee_minor IS NULL OR min_fee_minor <= max_fee_minor)
);

CREATE TABLE merchants (
    id             UUID PRIMARY KEY,
    merchant_code  VARCHAR(32)  NOT NULL,
    display_name   VARCHAR(120) NOT NULL,
    legal_name     VARCHAR(160),
    currency       CHAR(3)      NOT NULL,
    status         VARCHAR(16)  NOT NULL,
    fee_plan_id    UUID         NOT NULL REFERENCES fee_plans (id),
    created_at     TIMESTAMPTZ  NOT NULL,
    updated_at     TIMESTAMPTZ  NOT NULL,
    version        BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uq_merchants_code UNIQUE (merchant_code),
    CONSTRAINT ck_merchants_status CHECK (status IN ('ACTIVE', 'SUSPENDED'))
);
CREATE INDEX ix_merchants_status ON merchants (status);
CREATE INDEX ix_merchants_fee_plan ON merchants (fee_plan_id);

CREATE TABLE settlement_batches (
    id                 UUID PRIMARY KEY,
    batch_reference    VARCHAR(64)  NOT NULL,
    merchant_id        UUID         NOT NULL REFERENCES merchants (id),
    currency           CHAR(3)      NOT NULL,
    settlement_date    DATE         NOT NULL,
    status             VARCHAR(16)  NOT NULL,
    transaction_count  INTEGER      NOT NULL DEFAULT 0,
    refund_count       INTEGER      NOT NULL DEFAULT 0,
    gross_minor        BIGINT       NOT NULL DEFAULT 0,
    fee_minor          BIGINT       NOT NULL DEFAULT 0,
    refund_minor       BIGINT       NOT NULL DEFAULT 0,
    net_minor          BIGINT       NOT NULL DEFAULT 0,
    summary            VARCHAR(500),
    failure_reason     VARCHAR(500),
    started_at         TIMESTAMPTZ,
    completed_at       TIMESTAMPTZ,
    created_at         TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_settlement_batches_reference UNIQUE (batch_reference),
    CONSTRAINT ck_settlement_batches_status CHECK (status IN ('CREATED', 'PROCESSING', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_settlement_batches_amounts CHECK (gross_minor >= 0 AND fee_minor >= 0 AND refund_minor >= 0),
    -- net is always derivable; storing it is a deliberate denormalisation that this check keeps honest
    CONSTRAINT ck_settlement_batches_net CHECK (net_minor = gross_minor - fee_minor - refund_minor)
);
CREATE INDEX ix_settlement_batches_merchant_date ON settlement_batches (merchant_id, settlement_date DESC);
CREATE INDEX ix_settlement_batches_status_date ON settlement_batches (status, settlement_date DESC);

CREATE TABLE transactions (
    id                     UUID PRIMARY KEY,
    external_reference     VARCHAR(64)  NOT NULL,
    merchant_id            UUID         NOT NULL REFERENCES merchants (id),
    amount_minor           BIGINT       NOT NULL,
    currency               CHAR(3)      NOT NULL,
    status                 VARCHAR(24)  NOT NULL,
    -- Safe synthetic card descriptor only. There is deliberately no column that could hold a PAN, CVV or PIN.
    card_brand             VARCHAR(16)  NOT NULL,
    card_last4             CHAR(4)      NOT NULL,
    card_token             VARCHAR(40)  NOT NULL,
    decline_reason         VARCHAR(40),
    captured_amount_minor  BIGINT       NOT NULL DEFAULT 0,
    refunded_amount_minor  BIGINT       NOT NULL DEFAULT 0,
    fee_minor              BIGINT       NOT NULL DEFAULT 0,
    captured_at            TIMESTAMPTZ,
    settlement_batch_id    UUID REFERENCES settlement_batches (id),
    created_at             TIMESTAMPTZ  NOT NULL,
    updated_at             TIMESTAMPTZ  NOT NULL,
    version                BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uq_transactions_external_reference UNIQUE (external_reference),
    CONSTRAINT ck_transactions_amount CHECK (amount_minor > 0),
    CONSTRAINT ck_transactions_status CHECK (status IN
        ('AUTHORIZED', 'DECLINED', 'CAPTURED', 'PARTIALLY_REFUNDED', 'REFUNDED', 'REVERSED')),
    CONSTRAINT ck_transactions_last4 CHECK (card_last4 ~ '^[0-9]{4}$'),
    CONSTRAINT ck_transactions_captured CHECK (captured_amount_minor BETWEEN 0 AND amount_minor),
    -- the over-refund invariant is enforced by the database as well as the service layer
    CONSTRAINT ck_transactions_refunded CHECK (refunded_amount_minor BETWEEN 0 AND captured_amount_minor),
    CONSTRAINT ck_transactions_fee CHECK (fee_minor BETWEEN 0 AND captured_amount_minor)
);
CREATE INDEX ix_transactions_merchant_created ON transactions (merchant_id, created_at DESC);
CREATE INDEX ix_transactions_status_created ON transactions (status, created_at DESC);
CREATE INDEX ix_transactions_created ON transactions (created_at DESC);
-- settlement only ever looks for captured-but-unsettled rows; a partial index keeps that scan tiny
CREATE INDEX ix_transactions_unsettled ON transactions (merchant_id, captured_at)
    WHERE settlement_batch_id IS NULL AND captured_at IS NOT NULL;

CREATE TABLE refunds (
    id                   UUID PRIMARY KEY,
    transaction_id       UUID         NOT NULL REFERENCES transactions (id),
    refund_reference     VARCHAR(64)  NOT NULL,
    amount_minor         BIGINT       NOT NULL,
    currency             CHAR(3)      NOT NULL,
    reason               VARCHAR(200),
    settlement_batch_id  UUID REFERENCES settlement_batches (id),
    created_at           TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_refunds_reference UNIQUE (refund_reference),
    CONSTRAINT ck_refunds_amount CHECK (amount_minor > 0)
);
CREATE INDEX ix_refunds_transaction ON refunds (transaction_id);
CREATE INDEX ix_refunds_unsettled ON refunds (created_at) WHERE settlement_batch_id IS NULL;

CREATE TABLE transaction_events (
    id              UUID PRIMARY KEY,
    transaction_id  UUID         NOT NULL REFERENCES transactions (id),
    event_type      VARCHAR(32)  NOT NULL,
    from_status     VARCHAR(24),
    to_status       VARCHAR(24)  NOT NULL,
    amount_minor    BIGINT,
    detail          VARCHAR(300),
    created_at      TIMESTAMPTZ  NOT NULL
);
CREATE INDEX ix_transaction_events_txn ON transaction_events (transaction_id, created_at);

-- Snapshot of the fee plan terms applied to a transaction. Editing a fee plan
-- later never changes a historical fee because nothing here references live terms.
CREATE TABLE fee_assessments (
    id                          UUID PRIMARY KEY,
    transaction_id              UUID         NOT NULL REFERENCES transactions (id),
    fee_plan_id                 UUID         NOT NULL REFERENCES fee_plans (id),
    plan_code                   VARCHAR(40)  NOT NULL,
    currency                    CHAR(3)      NOT NULL,
    gross_minor                 BIGINT       NOT NULL,
    percentage_bps              INTEGER      NOT NULL,
    fixed_fee_minor             BIGINT       NOT NULL,
    min_fee_minor               BIGINT,
    max_fee_minor               BIGINT,
    percentage_component_minor  BIGINT       NOT NULL,
    fixed_component_minor       BIGINT       NOT NULL,
    adjustment                  VARCHAR(24)  NOT NULL,
    total_fee_minor             BIGINT       NOT NULL,
    net_minor                   BIGINT       NOT NULL,
    created_at                  TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_fee_assessments_transaction UNIQUE (transaction_id),
    CONSTRAINT ck_fee_assessments_total CHECK (total_fee_minor BETWEEN 0 AND gross_minor),
    CONSTRAINT ck_fee_assessments_net CHECK (net_minor = gross_minor - total_fee_minor),
    CONSTRAINT ck_fee_assessments_adjustment CHECK (adjustment IN
        ('NONE', 'MINIMUM_APPLIED', 'MAXIMUM_APPLIED', 'CAPPED_AT_AMOUNT'))
);

CREATE TABLE settlement_batch_lines (
    id              UUID PRIMARY KEY,
    batch_id        UUID         NOT NULL REFERENCES settlement_batches (id),
    line_type       VARCHAR(12)  NOT NULL,
    transaction_id  UUID         NOT NULL REFERENCES transactions (id),
    refund_id       UUID REFERENCES refunds (id),
    gross_minor     BIGINT       NOT NULL,
    fee_minor       BIGINT       NOT NULL,
    net_minor       BIGINT       NOT NULL,
    CONSTRAINT ck_batch_lines_type CHECK (line_type IN ('CAPTURE', 'REFUND')),
    CONSTRAINT ck_batch_lines_refund CHECK ((line_type = 'REFUND') = (refund_id IS NOT NULL))
);
CREATE INDEX ix_batch_lines_batch ON settlement_batch_lines (batch_id);
-- Last line of defence against double settlement: a capture or a refund can
-- appear on at most one batch line, whatever the application does.
CREATE UNIQUE INDEX uq_batch_lines_capture ON settlement_batch_lines (transaction_id) WHERE line_type = 'CAPTURE';
CREATE UNIQUE INDEX uq_batch_lines_refund ON settlement_batch_lines (refund_id) WHERE refund_id IS NOT NULL;

CREATE TABLE external_statements (
    id                   UUID PRIMARY KEY,
    statement_reference  VARCHAR(64)  NOT NULL,
    source               VARCHAR(24)  NOT NULL,
    period_start         DATE         NOT NULL,
    period_end           DATE         NOT NULL,
    line_count           INTEGER      NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_external_statements_reference UNIQUE (statement_reference),
    CONSTRAINT ck_external_statements_period CHECK (period_start <= period_end)
);

CREATE TABLE external_statement_lines (
    id               UUID PRIMARY KEY,
    statement_id     UUID         NOT NULL REFERENCES external_statements (id),
    batch_reference  VARCHAR(64)  NOT NULL,
    merchant_code    VARCHAR(32)  NOT NULL,
    currency         CHAR(3)      NOT NULL,
    amount_minor     BIGINT       NOT NULL,
    value_date       DATE         NOT NULL
);
CREATE INDEX ix_statement_lines_statement ON external_statement_lines (statement_id);
CREATE INDEX ix_statement_lines_reference ON external_statement_lines (batch_reference);

CREATE TABLE reconciliation_runs (
    id                        UUID PRIMARY KEY,
    statement_id              UUID         NOT NULL REFERENCES external_statements (id),
    status                    VARCHAR(16)  NOT NULL,
    total_items               INTEGER      NOT NULL DEFAULT 0,
    matched_count             INTEGER      NOT NULL DEFAULT 0,
    amount_mismatch_count     INTEGER      NOT NULL DEFAULT 0,
    date_mismatch_count       INTEGER      NOT NULL DEFAULT 0,
    unmatched_internal_count  INTEGER      NOT NULL DEFAULT 0,
    unmatched_external_count  INTEGER      NOT NULL DEFAULT 0,
    started_at                TIMESTAMPTZ  NOT NULL,
    completed_at              TIMESTAMPTZ,
    CONSTRAINT ck_reconciliation_runs_status CHECK (status IN ('COMPLETED', 'FAILED'))
);
CREATE INDEX ix_reconciliation_runs_started ON reconciliation_runs (started_at DESC);

CREATE TABLE reconciliation_items (
    id                 UUID PRIMARY KEY,
    run_id             UUID         NOT NULL REFERENCES reconciliation_runs (id),
    outcome            VARCHAR(24)  NOT NULL,
    batch_reference    VARCHAR(64)  NOT NULL,
    merchant_code      VARCHAR(32),
    currency           CHAR(3),
    batch_id           UUID REFERENCES settlement_batches (id),
    statement_line_id  UUID REFERENCES external_statement_lines (id),
    expected_minor     BIGINT,
    actual_minor       BIGINT,
    difference_minor   BIGINT,
    detail             VARCHAR(300) NOT NULL,
    review_status      VARCHAR(16)  NOT NULL,
    resolution_note    VARCHAR(500),
    reviewed_at        TIMESTAMPTZ,
    version            BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT ck_reconciliation_items_outcome CHECK (outcome IN
        ('MATCHED', 'AMOUNT_MISMATCH', 'DATE_MISMATCH', 'UNMATCHED_INTERNAL', 'UNMATCHED_EXTERNAL')),
    CONSTRAINT ck_reconciliation_items_review CHECK (review_status IN ('NOT_REQUIRED', 'OPEN', 'REVIEWED')),
    -- a matched item can never sit in the exception queue, and an exception can never be "not required"
    CONSTRAINT ck_reconciliation_items_review_consistency CHECK ((outcome = 'MATCHED') = (review_status = 'NOT_REQUIRED'))
);
CREATE INDEX ix_reconciliation_items_run ON reconciliation_items (run_id);
CREATE INDEX ix_reconciliation_items_open ON reconciliation_items (run_id) WHERE review_status = 'OPEN';

CREATE TABLE audit_events (
    id           UUID PRIMARY KEY,
    occurred_at  TIMESTAMPTZ  NOT NULL,
    entity_type  VARCHAR(32)  NOT NULL,
    entity_id    VARCHAR(64)  NOT NULL,
    action       VARCHAR(48)  NOT NULL,
    actor        VARCHAR(48)  NOT NULL,
    metadata     TEXT
);
CREATE INDEX ix_audit_events_occurred ON audit_events (occurred_at DESC);
CREATE INDEX ix_audit_events_entity ON audit_events (entity_type, entity_id);

CREATE TABLE idempotency_records (
    id               UUID PRIMARY KEY,
    idempotency_key  VARCHAR(80)  NOT NULL,
    operation        VARCHAR(40)  NOT NULL,
    request_hash     CHAR(64)     NOT NULL,
    resource_id      UUID         NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_idempotency_key_operation UNIQUE (idempotency_key, operation)
);
