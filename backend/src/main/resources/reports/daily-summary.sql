-- Daily summary with a running total and a 7-day moving average.
-- generate_series supplies every calendar day, so quiet days show as zero instead of vanishing.
WITH days AS (
    SELECT CAST(d AS date) AS day
      FROM generate_series(CAST(:from AS timestamp), CAST(:to AS timestamp), INTERVAL '1 day') AS d
),
activity AS (
    SELECT txn_date,
           SUM(transaction_count) AS transactions,
           SUM(approved_count)    AS approved,
           SUM(declined_count)    AS declined,
           SUM(captured_minor)    AS captured_minor,
           SUM(fee_minor)         AS fee_minor,
           SUM(refunded_minor)    AS refunded_minor
      FROM rpt_daily_merchant_activity
     WHERE currency = :currency
       AND txn_date BETWEEN :from AND :to
     GROUP BY txn_date
)
SELECT days.day,
       CAST(COALESCE(a.transactions, 0) AS bigint)                                AS transactions,
       CAST(COALESCE(a.approved, 0) AS bigint)                                    AS approved,
       CAST(COALESCE(a.declined, 0) AS bigint)                                    AS declined,
       CAST(COALESCE(a.captured_minor, 0) AS bigint)                              AS captured_minor,
       CAST(COALESCE(a.fee_minor, 0) AS bigint)                                   AS fee_minor,
       CAST(COALESCE(a.refunded_minor, 0) AS bigint)                              AS refunded_minor,
       CAST(SUM(COALESCE(a.captured_minor, 0)) OVER (ORDER BY days.day) AS bigint) AS running_captured_minor,
       CAST(ROUND(AVG(COALESCE(a.captured_minor, 0))
            OVER (ORDER BY days.day ROWS BETWEEN 6 PRECEDING AND CURRENT ROW)) AS bigint) AS captured_7d_avg_minor
  FROM days
  LEFT JOIN activity a ON a.txn_date = days.day
 ORDER BY days.day
