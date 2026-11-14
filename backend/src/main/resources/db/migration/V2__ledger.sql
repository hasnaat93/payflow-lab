-- Double-entry ledger. The rules that matter are enforced in the database, so
-- they hold even if application code has a bug:
--   1. a business event can be posted at most once        (unique constraint)
--   2. posted entries and lines can never be edited/deleted (row triggers)
--   3. every entry balances: debits = credits               (deferred constraint trigger)

CREATE TABLE accounts (
    id           UUID PRIMARY KEY,
    code         VARCHAR(8)   NOT NULL,
    name         VARCHAR(80)  NOT NULL,
    type         VARCHAR(12)  NOT NULL,
    normal_side  VARCHAR(6)   NOT NULL,
    description  VARCHAR(300) NOT NULL,
    CONSTRAINT uq_accounts_code UNIQUE (code),
    CONSTRAINT ck_accounts_type CHECK (type IN ('ASSET', 'LIABILITY', 'REVENUE')),
    CONSTRAINT ck_accounts_normal_side CHECK (normal_side IN ('DEBIT', 'CREDIT'))
);

CREATE SEQUENCE journal_entry_number_seq START 1000;

CREATE TABLE journal_entries (
    id            UUID PRIMARY KEY,
    entry_number  BIGINT       NOT NULL DEFAULT nextval('journal_entry_number_seq'),
    event_type    VARCHAR(32)  NOT NULL,
    source_type   VARCHAR(24)  NOT NULL,
    source_id     UUID         NOT NULL,
    merchant_id   UUID REFERENCES merchants (id),
    currency      CHAR(3)      NOT NULL,
    description   VARCHAR(200) NOT NULL,
    posted_at     TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_journal_entries_number UNIQUE (entry_number),
    -- idempotent posting: the same business event cannot hit the ledger twice
    CONSTRAINT uq_journal_entries_source UNIQUE (source_type, source_id, event_type)
);
CREATE INDEX ix_journal_entries_posted ON journal_entries (posted_at DESC);
CREATE INDEX ix_journal_entries_merchant ON journal_entries (merchant_id, posted_at DESC);

CREATE TABLE journal_lines (
    id                UUID PRIMARY KEY,
    journal_entry_id  UUID        NOT NULL REFERENCES journal_entries (id),
    line_no           SMALLINT    NOT NULL,
    account_id        UUID        NOT NULL REFERENCES accounts (id),
    side              VARCHAR(6)  NOT NULL,
    amount_minor      BIGINT      NOT NULL,
    CONSTRAINT uq_journal_lines_entry_line UNIQUE (journal_entry_id, line_no),
    CONSTRAINT ck_journal_lines_side CHECK (side IN ('DEBIT', 'CREDIT')),
    CONSTRAINT ck_journal_lines_amount CHECK (amount_minor > 0)
);
CREATE INDEX ix_journal_lines_account ON journal_lines (account_id);

-- 2. append-only -------------------------------------------------------------
CREATE FUNCTION ledger_reject_mutation() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'ledger is append-only: % on % is not allowed', TG_OP, TG_TABLE_NAME
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$;

CREATE TRIGGER journal_entries_append_only
    BEFORE UPDATE OR DELETE ON journal_entries
    FOR EACH ROW EXECUTE FUNCTION ledger_reject_mutation();

CREATE TRIGGER journal_lines_append_only
    BEFORE UPDATE OR DELETE ON journal_lines
    FOR EACH ROW EXECUTE FUNCTION ledger_reject_mutation();

-- 3. balanced ----------------------------------------------------------------
-- Deferred to commit so an entry and all of its lines can be inserted first.
CREATE FUNCTION ledger_assert_entry_balanced() RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    v_debits  BIGINT;
    v_credits BIGINT;
    v_lines   INTEGER;
BEGIN
    SELECT COALESCE(SUM(amount_minor) FILTER (WHERE side = 'DEBIT'), 0),
           COALESCE(SUM(amount_minor) FILTER (WHERE side = 'CREDIT'), 0),
           COUNT(*)
      INTO v_debits, v_credits, v_lines
      FROM journal_lines
     WHERE journal_entry_id = NEW.journal_entry_id;

    IF v_lines < 2 OR v_debits <> v_credits THEN
        RAISE EXCEPTION 'journal entry % is unbalanced: debits=% credits=% lines=%',
            NEW.journal_entry_id, v_debits, v_credits, v_lines
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER journal_lines_balanced
    AFTER INSERT ON journal_lines
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ledger_assert_entry_balanced();

-- Chart of accounts (reference data — part of the schema, not demo data).
INSERT INTO accounts (id, code, name, type, normal_side, description) VALUES
    ('00000000-0000-0000-0000-000000001000', '1000', 'Network Receivable', 'ASSET', 'DEBIT',
     'Simulated card-network clearing: funds the network owes the platform for captured payments.'),
    ('00000000-0000-0000-0000-000000002000', '2000', 'Merchant Payable', 'LIABILITY', 'CREDIT',
     'Captured funds, net of fees and refunds, owed to merchants and not yet settled.'),
    ('00000000-0000-0000-0000-000000002100', '2100', 'Settlement Clearing', 'LIABILITY', 'CREDIT',
     'Settled payouts in transit to merchant bank accounts.'),
    ('00000000-0000-0000-0000-000000002200', '2200', 'Refund Clearing', 'LIABILITY', 'CREDIT',
     'Refunds owed back to cardholders through the network, cleared at settlement.'),
    ('00000000-0000-0000-0000-000000004000', '4000', 'Fee Revenue', 'REVENUE', 'CREDIT',
     'Processing fees earned on captured payments.');
