-- Captured money that has not been settled yet, aged from the capture date.
-- Age is measured to the report end date, so the report is reproducible for past dates.
WITH unsettled AS (
    SELECT merchant_code,
           merchant_name,
           net_minor,
           CAST(:to AS date) - CAST(captured_at AT TIME ZONE 'UTC' AS date) AS age_days
      FROM rpt_transaction_facts
     WHERE currency = :currency
       AND is_captured
       AND NOT is_settled
       AND CAST(captured_at AT TIME ZONE 'UTC' AS date) <= :to
)
SELECT merchant_code,
       merchant_name,
       COUNT(*)                                                                          AS unsettled_transactions,
       CAST(COALESCE(SUM(net_minor) FILTER (WHERE age_days <= 1), 0) AS bigint)          AS age_0_1_days_minor,
       CAST(COALESCE(SUM(net_minor) FILTER (WHERE age_days BETWEEN 2 AND 3), 0) AS bigint) AS age_2_3_days_minor,
       CAST(COALESCE(SUM(net_minor) FILTER (WHERE age_days BETWEEN 4 AND 7), 0) AS bigint) AS age_4_7_days_minor,
       CAST(COALESCE(SUM(net_minor) FILTER (WHERE age_days > 7), 0) AS bigint)           AS age_over_7_days_minor,
       CAST(SUM(net_minor) AS bigint)                                                    AS total_unsettled_minor,
       MAX(age_days)                                                                     AS oldest_age_days
  FROM unsettled
 GROUP BY merchant_code, merchant_name
 ORDER BY total_unsettled_minor DESC, merchant_code
