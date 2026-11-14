package com.payflowlab.reporting;

import com.payflowlab.common.ApiException;
import com.payflowlab.common.AppClock;
import com.payflowlab.common.Currencies;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.ResultSetMetaData;
import java.sql.Types;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A small SQL-first reporting engine. Each report is a plain .sql file under
 * resources/reports with three named parameters (:currency, :from, :to). The
 * service runs it and describes the result set generically, so adding a report
 * means adding one SQL file and one catalogue line; no Java per report.
 *
 * <p>The SQL text itself is returned with the result, so a reader can always
 * see exactly how a number was produced.
 */
@Service
public class ReportService {

    public record ReportDefinition(String id, String title, String description) {
    }

    /** {@code type} is one of money, percent, number, date, text and tells the UI how to format a cell. */
    public record Column(String key, String label, String type) {
    }

    public record ReportResult(String id, String title, String description, String currency, LocalDate from,
                               LocalDate to, List<Column> columns, List<Map<String, Object>> rows, String sql) {
    }

    private static final List<ReportDefinition> CATALOGUE = List.of(
            new ReportDefinition("merchant-performance", "Merchant performance",
                    "Volume, approval rate, fees and net per merchant, ranked by captured volume with each "
                            + "merchant's share of the total (window functions)."),
            new ReportDefinition("daily-summary", "Daily summary",
                    "Activity per day with a running captured total and a 7-day moving average."),
            new ReportDefinition("decline-analysis", "Decline analysis",
                    "Declined authorizations by reason, with each reason's share of all declines."),
            new ReportDefinition("card-brand-mix", "Card brand mix",
                    "Transaction count, approval rate and captured volume by card brand."),
            new ReportDefinition("settlement-summary", "Settlement summary",
                    "Settlement batches by date and merchant: gross, fees, refunds and net paid out."),
            new ReportDefinition("unsettled-aging", "Unsettled funds aging",
                    "Captured money not yet settled, bucketed by how long it has been waiting."),
            new ReportDefinition("trial-balance", "Ledger trial balance",
                    "Debit and credit totals per account as of the end date. Total debits must equal total credits."));

    private final NamedParameterJdbcTemplate jdbc;
    private final AppClock clock;

    public ReportService(NamedParameterJdbcTemplate jdbc, AppClock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public List<ReportDefinition> catalogue() {
        return CATALOGUE;
    }

    @Transactional(readOnly = true)
    public ReportResult run(String id, String currency, LocalDate from, LocalDate to) {
        // The id is matched against the fixed catalogue; it is never used to build a path or SQL directly.
        ReportDefinition definition = CATALOGUE.stream().filter(d -> d.id().equals(id)).findFirst()
                .orElseThrow(() -> ApiException.notFound("Report", id));
        String selectedCurrency = Currencies.require(currency == null || currency.isBlank() ? "USD" : currency);
        LocalDate end = to != null ? to : LocalDate.ofInstant(clock.now(), ZoneOffset.UTC);
        LocalDate start = from != null ? from : end.minusDays(29);
        if (start.isAfter(end)) {
            throw ApiException.badRequest("INVALID_DATE_RANGE", "from must be on or before to");
        }

        String sql = loadSql(definition.id());
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("currency", selectedCurrency)
                .addValue("from", Date.valueOf(start))
                .addValue("to", Date.valueOf(end));

        List<Column> columns = new ArrayList<>();
        List<Map<String, Object>> rows = jdbc.query(sql, params, rs -> {
            ResultSetMetaData meta = rs.getMetaData();
            for (int i = 1; i <= meta.getColumnCount(); i++) {
                String key = meta.getColumnLabel(i);
                columns.add(new Column(key, label(key), type(key, meta.getColumnType(i))));
            }
            List<Map<String, Object>> out = new ArrayList<>();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= meta.getColumnCount(); i++) {
                    Object value = rs.getObject(i);
                    row.put(meta.getColumnLabel(i), value instanceof Date date ? date.toLocalDate().toString() : value);
                }
                out.add(row);
            }
            return out;
        });
        return new ReportResult(definition.id(), definition.title(), definition.description(), selectedCurrency,
                start, end, columns, rows, sql);
    }

    /** RFC 4180 CSV. Money stays in integer minor units so the export is lossless. */
    public String toCsv(ReportResult result) {
        StringBuilder csv = new StringBuilder();
        csv.append(String.join(",", result.columns().stream().map(c -> escape(c.key())).toList())).append("\r\n");
        for (Map<String, Object> row : result.rows()) {
            csv.append(String.join(",", result.columns().stream()
                    .map(c -> escape(row.get(c.key()) == null ? "" : String.valueOf(row.get(c.key())))).toList()))
                    .append("\r\n");
        }
        return csv.toString();
    }

    private static String escape(String value) {
        // neutralise spreadsheet formula injection, then quote if needed
        String safe = !value.isEmpty() && "=+-@".indexOf(value.charAt(0)) >= 0 && !value.matches("^-?[0-9.]+$")
                ? "'" + value
                : value;
        return safe.contains(",") || safe.contains("\"") || safe.contains("\n") || safe.contains("\r")
                ? "\"" + safe.replace("\"", "\"\"") + "\""
                : safe;
    }

    private static String loadSql(String id) {
        try (var in = new ClassPathResource("reports/" + id + ".sql").getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new UncheckedIOException("Report SQL missing for " + id, e);
        }
    }

    private static String type(String key, int sqlType) {
        if (key.endsWith("_minor")) {
            return "money";
        }
        if (key.endsWith("_pct")) {
            return "percent";
        }
        return switch (sqlType) {
            case Types.DATE -> "date";
            case Types.BIGINT, Types.INTEGER, Types.SMALLINT, Types.NUMERIC, Types.DECIMAL, Types.DOUBLE,
                 Types.REAL, Types.FLOAT -> "number";
            default -> "text";
        };
    }

    private static String label(String key) {
        String base = key.replaceAll("_(minor|pct)$", "").replace('_', ' ');
        String label = base.substring(0, 1).toUpperCase(Locale.ROOT) + base.substring(1);
        return key.endsWith("_pct") ? label + " %" : label;
    }
}
