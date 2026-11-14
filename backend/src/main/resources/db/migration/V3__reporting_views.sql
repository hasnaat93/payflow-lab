-- Reporting layer. Operational tables stay normalised; analytics read from
-- views so report SQL (and any BI tool pointed at this database) shares one
-- definition of each measure.

-- One row per payment with its merchant and fee, flattened for analysis.
CREATE VIEW rpt_transaction_facts AS
SELECT t.id                                         AS transaction_id,
       t.external_reference,
       t.created_at,
       (t.created_at AT TIME ZONE 'UTC')::date      AS txn_date,
       t.captured_at,
       m.id                                         AS merchant_id,
       m.merchant_code,
       m.display_name                               AS merchant_name,
       t.currency,
       t.status,
       t.card_brand,
       t.decline_reason,
       t.amount_minor,
       t.captured_amount_minor,
       t.refunded_amount_minor,
       t.fee_minor,
       t.captured_amount_minor - t.fee_minor - t.refunded_amount_minor AS net_minor,
       (t.status <> 'DECLINED')                     AS is_approved,
       (t.captured_at IS NOT NULL)                  AS is_captured,
       (t.settlement_batch_id IS NOT NULL)          AS is_settled
  FROM transactions t
  JOIN merchants m ON m.id = t.merchant_id;

-- Daily activity per merchant and currency.
CREATE VIEW rpt_daily_merchant_activity AS
SELECT txn_date,
       merchant_id,
       merchant_code,
       merchant_name,
       currency,
       COUNT(*)                                         AS transaction_count,
       COUNT(*) FILTER (WHERE is_approved)              AS approved_count,
       COUNT(*) FILTER (WHERE status = 'DECLINED')      AS declined_count,
       COUNT(*) FILTER (WHERE status = 'REVERSED')      AS reversed_count,
       COUNT(*) FILTER (WHERE refunded_amount_minor > 0) AS refunded_count,
       COALESCE(SUM(amount_minor), 0)                   AS gross_minor,
       COALESCE(SUM(captured_amount_minor), 0)          AS captured_minor,
       COALESCE(SUM(refunded_amount_minor), 0)          AS refunded_minor,
       COALESCE(SUM(fee_minor), 0)                      AS fee_minor
  FROM rpt_transaction_facts
 GROUP BY txn_date, merchant_id, merchant_code, merchant_name, currency;

-- Account balances derived purely from journal lines, per currency.
CREATE VIEW rpt_account_balances AS
SELECT a.id                                                              AS account_id,
       a.code,
       a.name,
       a.type,
       a.normal_side,
       je.currency,
       COALESCE(SUM(jl.amount_minor) FILTER (WHERE jl.side = 'DEBIT'), 0)  AS debit_minor,
       COALESCE(SUM(jl.amount_minor) FILTER (WHERE jl.side = 'CREDIT'), 0) AS credit_minor,
       CASE a.normal_side
           WHEN 'DEBIT' THEN COALESCE(SUM(jl.amount_minor) FILTER (WHERE jl.side = 'DEBIT'), 0)
                           - COALESCE(SUM(jl.amount_minor) FILTER (WHERE jl.side = 'CREDIT'), 0)
           ELSE COALESCE(SUM(jl.amount_minor) FILTER (WHERE jl.side = 'CREDIT'), 0)
              - COALESCE(SUM(jl.amount_minor) FILTER (WHERE jl.side = 'DEBIT'), 0)
       END                                                               AS balance_minor
  FROM accounts a
  JOIN journal_lines jl ON jl.account_id = a.id
  JOIN journal_entries je ON je.id = jl.journal_entry_id
 GROUP BY a.id, a.code, a.name, a.type, a.normal_side, je.currency;
