package com.payflowlab.dashboard;

import com.payflowlab.common.ApiException;
import com.payflowlab.common.AppClock;
import com.payflowlab.common.Currencies;
import com.payflowlab.transaction.TransactionStatus;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every figure on the overview page is computed here, by SQL, from persisted
 * rows at request time. Nothing is cached, pre-aggregated or hardcoded.
 * Amounts are always for exactly one currency; unlike currencies are never summed.
 */
@Service
public class DashboardService {

    public record Kpis(long totalTransactions, long approvedCount, long declinedCount, long reversedCount,
                       long refundedCount, long capturedCount, long grossMinor, long capturedMinor,
                       long refundMinor, long feeMinor, long netSettlementMinor, long unsettledMinor,
                       double approvalRatePercent) {
    }

    public record Reconciliation(Double latestMatchRatePercent, Instant latestRunAt, String latestStatement,
                                 long openExceptions, long reviewedExceptions) {
    }

    public record DayPoint(LocalDate date, long transactionCount, long approvedCount, long declinedCount,
                           long grossMinor, long capturedMinor, long feeMinor) {
    }

    public record Summary(String currency, LocalDate from, LocalDate to, String status, List<String> currencies,
                          Kpis kpis, Map<String, Long> settlementBatches, Reconciliation reconciliation,
                          List<DayPoint> series) {
    }

    private static final int MAX_RANGE_DAYS = 366;

    private final NamedParameterJdbcTemplate jdbc;
    private final AppClock clock;

    public DashboardService(NamedParameterJdbcTemplate jdbc, AppClock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Summary summary(String currency, LocalDate from, LocalDate to, TransactionStatus status) {
        LocalDate today = LocalDate.ofInstant(clock.now(), ZoneOffset.UTC);
        LocalDate end = to != null ? to : today;
        LocalDate start = from != null ? from : end.minusDays(13);
        if (start.isAfter(end)) {
            throw ApiException.badRequest("INVALID_DATE_RANGE", "from must be on or before to");
        }
        if (ChronoUnit.DAYS.between(start, end) > MAX_RANGE_DAYS) {
            throw ApiException.badRequest("DATE_RANGE_TOO_LARGE", "Range cannot exceed " + MAX_RANGE_DAYS + " days");
        }

        List<String> currencies = jdbc.getJdbcTemplate().queryForList(
                "SELECT DISTINCT currency FROM merchants ORDER BY currency", String.class);
        String selected = currency != null && !currency.isBlank()
                ? Currencies.require(currency)
                : (currencies.contains("USD") || currencies.isEmpty() ? "USD" : currencies.get(0));

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("currency", selected)
                .addValue("from", Date.valueOf(start))
                .addValue("to", Date.valueOf(end))
                .addValue("status", status == null ? null : status.name());

        Kpis kpis = jdbc.queryForObject("""
                SELECT COUNT(*)                                                  AS total,
                       COUNT(*) FILTER (WHERE is_approved)                       AS approved,
                       COUNT(*) FILTER (WHERE status = 'DECLINED')               AS declined,
                       COUNT(*) FILTER (WHERE status = 'REVERSED')               AS reversed,
                       COUNT(*) FILTER (WHERE refunded_amount_minor > 0)         AS refunded,
                       COUNT(*) FILTER (WHERE is_captured)                       AS captured,
                       COALESCE(SUM(amount_minor), 0)                            AS gross_minor,
                       COALESCE(SUM(captured_amount_minor), 0)                   AS captured_minor,
                       COALESCE(SUM(refunded_amount_minor), 0)                   AS refund_minor,
                       COALESCE(SUM(fee_minor), 0)                               AS fee_minor,
                       COALESCE(SUM(net_minor) FILTER (WHERE is_captured AND NOT is_settled), 0) AS unsettled_minor
                  FROM rpt_transaction_facts
                 WHERE currency = :currency
                   AND txn_date BETWEEN :from AND :to
                   AND (CAST(:status AS varchar) IS NULL OR status = :status)
                """, params, (rs, i) -> {
            long total = rs.getLong("total");
            long approved = rs.getLong("approved");
            return new Kpis(total, approved, rs.getLong("declined"), rs.getLong("reversed"),
                    rs.getLong("refunded"), rs.getLong("captured"), rs.getLong("gross_minor"),
                    rs.getLong("captured_minor"), rs.getLong("refund_minor"), rs.getLong("fee_minor"),
                    netSettled(params), rs.getLong("unsettled_minor"),
                    total == 0 ? 0 : Math.round(approved * 10000.0 / total) / 100.0);
        });

        Map<String, Long> batchCounts = new LinkedHashMap<>();
        for (String s : List.of("CREATED", "PROCESSING", "COMPLETED", "FAILED")) {
            batchCounts.put(s, 0L);
        }
        jdbc.query("""
                SELECT status, COUNT(*) AS n
                  FROM settlement_batches
                 WHERE currency = :currency AND settlement_date BETWEEN :from AND :to
                 GROUP BY status
                """, params, rs -> {
            batchCounts.put(rs.getString("status"), rs.getLong("n"));
        });

        List<DayPoint> series = jdbc.query("""
                SELECT CAST(d.day AS date)                 AS day,
                       COALESCE(a.transaction_count, 0)    AS transaction_count,
                       COALESCE(a.approved_count, 0)       AS approved_count,
                       COALESCE(a.declined_count, 0)       AS declined_count,
                       COALESCE(a.gross_minor, 0)          AS gross_minor,
                       COALESCE(a.captured_minor, 0)       AS captured_minor,
                       COALESCE(a.fee_minor, 0)            AS fee_minor
                  FROM generate_series(CAST(:from AS timestamp), CAST(:to AS timestamp), INTERVAL '1 day') AS d(day)
                  LEFT JOIN (
                        SELECT txn_date,
                               COUNT(*)                                    AS transaction_count,
                               COUNT(*) FILTER (WHERE is_approved)         AS approved_count,
                               COUNT(*) FILTER (WHERE status = 'DECLINED') AS declined_count,
                               SUM(amount_minor)                           AS gross_minor,
                               SUM(captured_amount_minor)                  AS captured_minor,
                               SUM(fee_minor)                              AS fee_minor
                          FROM rpt_transaction_facts
                         WHERE currency = :currency
                           AND txn_date BETWEEN :from AND :to
                           AND (CAST(:status AS varchar) IS NULL OR status = :status)
                         GROUP BY txn_date
                       ) a ON a.txn_date = CAST(d.day AS date)
                 ORDER BY d.day
                """, params, (rs, i) -> new DayPoint(rs.getDate("day").toLocalDate(),
                rs.getLong("transaction_count"), rs.getLong("approved_count"), rs.getLong("declined_count"),
                rs.getLong("gross_minor"), rs.getLong("captured_minor"), rs.getLong("fee_minor")));

        return new Summary(selected, start, end, status == null ? null : status.name(), currencies, kpis,
                batchCounts, reconciliation(), series);
    }

    /** Net amount actually paid out: completed batches only, in the selected currency and period. */
    private long netSettled(MapSqlParameterSource params) {
        Long net = jdbc.queryForObject("""
                SELECT COALESCE(SUM(net_minor), 0)
                  FROM settlement_batches
                 WHERE currency = :currency AND status = 'COMPLETED'
                   AND settlement_date BETWEEN :from AND :to
                """, params, Long.class);
        return net == null ? 0 : net;
    }

    private Reconciliation reconciliation() {
        Map<String, Object> exceptionCounts = jdbc.getJdbcTemplate().queryForMap("""
                SELECT COUNT(*) FILTER (WHERE review_status = 'OPEN')     AS open_count,
                       COUNT(*) FILTER (WHERE review_status = 'REVIEWED') AS reviewed_count
                  FROM reconciliation_items
                """);
        long open = ((Number) exceptionCounts.get("open_count")).longValue();
        long reviewed = ((Number) exceptionCounts.get("reviewed_count")).longValue();
        List<Reconciliation> latest = jdbc.getJdbcTemplate().query("""
                SELECT r.matched_count, r.total_items, r.started_at, s.statement_reference
                  FROM reconciliation_runs r
                  JOIN external_statements s ON s.id = r.statement_id
                 ORDER BY r.started_at DESC
                 LIMIT 1
                """, (rs, i) -> {
            int total = rs.getInt("total_items");
            Double rate = total == 0 ? null : Math.round(rs.getInt("matched_count") * 10000.0 / total) / 100.0;
            return new Reconciliation(rate, rs.getTimestamp("started_at").toInstant(),
                    rs.getString("statement_reference"), open, reviewed);
        });
        return latest.isEmpty() ? new Reconciliation(null, null, null, open, reviewed) : latest.get(0);
    }
}
