import { useState } from "react";
import { NavLink, useParams } from "react-router-dom";
import { Card, CardHeader, EmptyState, ErrorState, Filter, LoadingRows, PageHeader, ROW, Table, Td, Th } from "../components/ui";
import { qs } from "../lib/api";
import { daysAgoUtc, formatDate, formatMinor, formatNumber, humanize, todayUtc } from "../lib/format";
import { useApi } from "../lib/hooks";
import type { ReportColumn, ReportDefinition, ReportResult } from "../lib/types";

function cell(value: string | number | null, column: ReportColumn): string {
  if (value === null || value === undefined || value === "") return "—";
  switch (column.type) {
    case "money":
      return formatMinor(Number(value));
    case "percent":
      return Number(value).toFixed(2) + "%";
    case "number":
      return formatNumber(Number(value));
    case "date":
      return formatDate(String(value));
    default:
      return /^[A-Z_]+$/.test(String(value)) && String(value).includes("_") ? humanize(String(value)) : String(value);
  }
}

export default function Reports() {
  const { id } = useParams();
  const catalogue = useApi<ReportDefinition[]>("/reports");
  const [currency, setCurrency] = useState("USD");
  const [from, setFrom] = useState(daysAgoUtc(29));
  const [to, setTo] = useState(todayUtc());
  const [showSql, setShowSql] = useState(false);

  const selected = id ?? catalogue.data?.[0]?.id;
  const params = { currency, from, to };
  const report = useApi<ReportResult>(`/reports/${selected}`, params, { enabled: !!selected });

  return (
    <>
      <PageHeader
        title="Reports"
        description="SQL-first reporting over the operational data. Each report is one query; you can read the exact SQL behind every number and download the result as CSV."
      />

      <div className="grid gap-5 lg:grid-cols-[17rem_1fr]">
        <Card className="h-fit">
          <CardHeader title="Catalogue" />
          {catalogue.isPending ? (
            <LoadingRows rows={5} />
          ) : catalogue.error ? (
            <ErrorState error={catalogue.error} onRetry={() => void catalogue.refetch()} />
          ) : (
            <nav aria-label="Reports" className="flex flex-col p-2">
              {catalogue.data.map((r) => (
                <NavLink
                  key={r.id}
                  to={`/reports/${r.id}`}
                  className={() =>
                    `rounded-lg px-3 py-2.5 text-sm transition-colors ${r.id === selected ? "bg-accent/15 font-medium text-fg" : "text-muted hover:bg-white/[0.05] hover:text-fg"}`
                  }
                >
                  {r.title}
                </NavLink>
              ))}
            </nav>
          )}
        </Card>

        <Card className="min-w-0">
          <div className="flex flex-wrap items-end gap-3 border-b border-line px-5 py-4">
            <Filter label="Currency">
              {(p) => (
                <select {...p} value={currency} onChange={(e) => setCurrency(e.target.value)}>
                  {["USD", "EUR", "PKR"].map((c) => (
                    <option key={c}>{c}</option>
                  ))}
                </select>
              )}
            </Filter>
            <Filter label="From (UTC)">{(p) => <input {...p} type="date" value={from} max={to} onChange={(e) => e.target.value && setFrom(e.target.value)} />}</Filter>
            <Filter label="To (UTC)">{(p) => <input {...p} type="date" value={to} min={from} max={todayUtc()} onChange={(e) => e.target.value && setTo(e.target.value)} />}</Filter>
            <div className="ml-auto flex gap-2">
              <button
                type="button"
                onClick={() => setShowSql((v) => !v)}
                aria-expanded={showSql}
                className="rounded-lg border border-line-strong bg-white/[0.04] px-4 py-2 text-sm font-medium hover:bg-white/[0.09]"
              >
                {showSql ? "Hide SQL" : "Show SQL"}
              </button>
              {selected ? (
                <a
                  href={`/api/v1/reports/${selected}/export${qs(params)}`}
                  download
                  className="rounded-lg bg-accent px-4 py-2 text-sm font-medium text-white hover:bg-accent-strong"
                >
                  Download CSV
                </a>
              ) : null}
            </div>
          </div>

          {report.isPending ? (
            <LoadingRows />
          ) : report.error ? (
            <ErrorState error={report.error} onRetry={() => void report.refetch()} />
          ) : (
            <>
              <div className="border-b border-line px-5 py-4">
                <h2 className="text-lg font-semibold tracking-tight">{report.data.title}</h2>
                <p className="mt-1 max-w-3xl text-sm leading-relaxed text-muted">{report.data.description}</p>
              </div>
              {showSql ? (
                <pre className="scroll-thin max-h-96 overflow-auto border-b border-line bg-bg/60 px-5 py-4 text-xs leading-relaxed text-muted">
                  <code>{report.data.sql}</code>
                </pre>
              ) : null}
              {report.data.rows.length === 0 ? (
                <EmptyState title="No rows for this currency and period" hint="Try another currency or a wider date range." />
              ) : (
                <>
                  <Table label={report.data.title}>
                    <thead>
                      <tr>
                        {report.data.columns.map((c) => (
                          <Th key={c.key} right={c.type !== "text" && c.type !== "date"}>
                            {c.label}
                            {c.type === "money" ? ` (${report.data.currency})` : ""}
                          </Th>
                        ))}
                      </tr>
                    </thead>
                    <tbody>
                      {report.data.rows.map((row, i) => {
                        const total = Object.values(row).includes("TOTAL");
                        return (
                          <tr key={i} className={`${ROW} ${total ? "bg-white/[0.03] font-semibold" : ""}`}>
                            {report.data.columns.map((c) => (
                              <Td key={c.key} right={c.type !== "text" && c.type !== "date"} className={c.type === "text" ? "" : "num whitespace-nowrap"}>
                                {cell(row[c.key], c)}
                              </Td>
                            ))}
                          </tr>
                        );
                      })}
                    </tbody>
                  </Table>
                  <p className="px-5 py-3 text-xs text-faint">
                    {report.data.rows.length} row{report.data.rows.length === 1 ? "" : "s"} · {formatDate(report.data.from)} – {formatDate(report.data.to)} · the CSV keeps amounts in integer minor units
                  </p>
                </>
              )}
            </>
          )}
        </Card>
      </div>
    </>
  );
}
