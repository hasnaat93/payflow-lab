-- Settlement batches by date and merchant, with a running total paid to each merchant.
SELECT b.settlement_date,
       m.merchant_code,
       b.batch_reference,
       b.status,
       b.transaction_count                 AS captures,
       b.refund_count                      AS refunds,
       b.gross_minor,
       b.fee_minor,
       b.refund_minor,
       b.net_minor,
       CAST(COALESCE(SUM(b.net_minor) FILTER (WHERE b.status = 'COMPLETED')
           OVER (PARTITION BY m.merchant_code ORDER BY b.settlement_date, b.batch_reference), 0) AS bigint)
                                           AS merchant_running_paid_minor
  FROM settlement_batches b
  JOIN merchants m ON m.id = b.merchant_id
 WHERE b.currency = :currency
   AND b.settlement_date BETWEEN :from AND :to
 ORDER BY b.settlement_date, m.merchant_code, b.batch_reference
