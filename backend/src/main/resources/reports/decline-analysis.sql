-- Why are authorizations being declined?
SELECT decline_reason,
       COUNT(*)                                             AS declines,
       ROUND(100.0 * COUNT(*) / SUM(COUNT(*)) OVER (), 2)   AS share_of_declines_pct,
       CAST(SUM(amount_minor) AS bigint)                    AS declined_amount_minor,
       CAST(ROUND(AVG(amount_minor)) AS bigint)             AS average_amount_minor,
       COUNT(DISTINCT merchant_code)                        AS merchants_affected
  FROM rpt_transaction_facts
 WHERE currency = :currency
   AND txn_date BETWEEN :from AND :to
   AND status = 'DECLINED'
 GROUP BY decline_reason
 ORDER BY declines DESC, decline_reason
