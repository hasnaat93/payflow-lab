-- Card brand mix.
SELECT card_brand,
       COUNT(*)                                                            AS transactions,
       ROUND(100.0 * COUNT(*) / SUM(COUNT(*)) OVER (), 2)                  AS share_of_transactions_pct,
       ROUND(100.0 * COUNT(*) FILTER (WHERE is_approved) / COUNT(*), 2)    AS approval_rate_pct,
       CAST(SUM(captured_amount_minor) AS bigint)                          AS captured_minor,
       CAST(SUM(fee_minor) AS bigint)                                      AS fee_minor
  FROM rpt_transaction_facts
 WHERE currency = :currency
   AND txn_date BETWEEN :from AND :to
 GROUP BY card_brand
 ORDER BY transactions DESC, card_brand
