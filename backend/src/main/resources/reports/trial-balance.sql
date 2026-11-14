-- Trial balance as of the end date. The TOTAL row proves the ledger balances:
-- its debit and credit totals must be identical, so its balance is zero.
WITH lines AS (
    SELECT a.code, a.name, a.type, a.normal_side, jl.side, jl.amount_minor
      FROM journal_lines jl
      JOIN journal_entries je ON je.id = jl.journal_entry_id
      JOIN accounts a ON a.id = jl.account_id
     WHERE je.currency = :currency
       AND CAST(je.posted_at AT TIME ZONE 'UTC' AS date) <= :to
),
per_account AS (
    SELECT code, name, type, normal_side,
           CAST(COALESCE(SUM(amount_minor) FILTER (WHERE side = 'DEBIT'), 0) AS bigint)  AS debit_minor,
           CAST(COALESCE(SUM(amount_minor) FILTER (WHERE side = 'CREDIT'), 0) AS bigint) AS credit_minor
      FROM lines
     GROUP BY code, name, type, normal_side
)
SELECT code AS account_code, name AS account_name, type AS account_type,
       debit_minor, credit_minor,
       CASE normal_side WHEN 'DEBIT' THEN debit_minor - credit_minor
                        ELSE credit_minor - debit_minor END AS balance_minor
  FROM per_account
UNION ALL
SELECT 'TOTAL', 'All accounts', '',
       CAST(COALESCE(SUM(debit_minor), 0) AS bigint), CAST(COALESCE(SUM(credit_minor), 0) AS bigint),
       CAST(COALESCE(SUM(debit_minor), 0) - COALESCE(SUM(credit_minor), 0) AS bigint)
  FROM per_account
 ORDER BY account_code
