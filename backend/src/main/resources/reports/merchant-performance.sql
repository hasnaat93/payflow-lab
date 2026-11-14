-- Merchant performance: who drives volume, and how healthy is their traffic?
-- Window functions rank merchants and compute each share of total captured volume.
WITH per_merchant AS (
    SELECT merchant_code,
           merchant_name,
           COUNT(*)                                        AS transactions,
           COUNT(*) FILTER (WHERE is_approved)             AS approved,
           SUM(captured_amount_minor)                      AS captured_minor,
           SUM(fee_minor)                                  AS fee_minor,
           SUM(refunded_amount_minor)                      AS refunded_minor,
           SUM(net_minor) FILTER (WHERE is_captured)       AS net_minor
      FROM rpt_transaction_facts
     WHERE currency = :currency
       AND txn_date BETWEEN :from AND :to
     GROUP BY merchant_code, merchant_name
)
SELECT RANK() OVER (ORDER BY captured_minor DESC)                                AS volume_rank,
       merchant_code,
       merchant_name,
       transactions,
       ROUND(100.0 * approved / NULLIF(transactions, 0), 2)                      AS approval_rate_pct,
       captured_minor,
       fee_minor,
       refunded_minor,
       COALESCE(net_minor, 0)                                                    AS net_minor,
       ROUND(100.0 * captured_minor / NULLIF(SUM(captured_minor) OVER (), 0), 2) AS volume_share_pct,
       ROUND(100.0 * fee_minor / NULLIF(captured_minor, 0), 2)                   AS effective_fee_pct
  FROM per_merchant
 ORDER BY volume_rank, merchant_code
