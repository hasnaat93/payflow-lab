import { useState } from "react";
import { Area, AreaChart, Bar, BarChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import { Card, CardHeader, EmptyState, ErrorState, Filter, LoadingRows, PageHeader, Stat, StatusBadge, TextLink } from "../components/ui";
import { daysAgoUtc, formatDate, formatDateTime, formatDayMonth, formatMinorCompact, formatMoney, formatNumber, todayUtc } from "../lib/format";
import { useApi } from "../lib/hooks";
import type { DashboardSummary } from "../lib/types";

const STATUSES = ["AUTHORIZED", "CAPTURED", "PARTIALLY_REFUNDED", "REFUNDED", "DECLINED", "REVERSED"];
const SERIES_1 = "#3987e5";
const SERIES_2 = "#d95926";
const AXIS = { fontSize: 11, fill: "#7f8aa6" };

type Point = DashboardSummary["series"][number];

function ChartTooltip({ active, payload, currency }: { active?: boolean; payload?: { payload: Point }[]; currency: string }) {
  if (!active || !payload?.length) return null;
  const p = payload[0].payload;
  return (
    <div className="rounded-lg border border-line-strong bg-raised px-3 py-2 text-xs shadow-xl">
      <p className="mb-1.5 font-semibold">{formatDate(p.date)}</p>
      <dl className="grid grid-cols-[auto_auto] gap-x-5 gap-y-1 text-muted">
        <dt>Captured</dt>
        <dd className="num text-right text-fg">{formatMoney(p.capturedMinor, currency)}</dd>
        <dt>Fees</dt>
        <dd className="num text-right text-fg">{formatMoney(p.feeMinor, currency)}</dd>
        <dt>Approved</dt>
        <dd className="num text-right text-fg">{p.approvedCount}</dd>
        <dt>Declined</dt>
        <dd className="num text-right text-fg">{p.declinedCount}</dd>
      </dl>
    </div>
  );
}

export default function Overview() {
  const [currency, setCurrency] = useState("");
  const [from, setFrom] = useState(daysAgoUtc(13));
  const [to, setTo] = useState(todayUtc());
  const [status, setStatus] = useState("");

  const { data, error, isPending, refetch } = useApi<DashboardSummary>("/dashboard/summary", { currency, from, to, status });

  const filters = (
    <Card className="mb-5">
      <div className="flex flex-wrap items-end gap-3 px-5 py-4">
        <Filter label="Currency">
          {(p) => (
            <select {...p} value={data?.currency ?? currency} onChange={(e) => setCurrency(e.target.value)}>
              {(data?.currencies.length ? data.currencies : ["USD"]).map((c) => (
                <option key={c}>{c}</option>
              ))}
            </select>
          )}
        </Filter>
        <Filter label="From (UTC)">{(p) => <input {...p} type="date" value={from} max={to} onChange={(e) => e.target.value && setFrom(e.target.value)} />}</Filter>
        <Filter label="To (UTC)">{(p) => <input {...p} type="date" value={to} min={from} max={todayUtc()} onChange={(e) => e.target.value && setTo(e.target.value)} />}</Filter>
        <Filter label="Transaction status">
          {(p) => (
            <select {...p} value={status} onChange={(e) => setStatus(e.target.value)}>
              <option value="">All statuses</option>
              {STATUSES.map((s) => (
                <option key={s} value={s}>
                  {s.replace(/_/g, " ").toLowerCase()}
                </option>
              ))}
            </select>
          )}
        </Filter>
        <p className="ml-auto max-w-xs text-xs leading-relaxed text-faint">Amounts are for one currency at a time; different currencies are never added together.</p>
      </div>
    </Card>
  );

  return (
    <>
      <PageHeader
        title="Operations overview"
        description="Live figures computed from stored payments, settlement batches and reconciliation runs. Nothing on this page is hardcoded."
      />
      {filters}

      {isPending ? (
        <Card>
          <LoadingRows rows={8} />
        </Card>
      ) : error ? (
        <Card>
          <ErrorState error={error} onRetry={() => void refetch()} />
        </Card>
      ) : (
        <Dashboard data={data} />
      )}
    </>
  );
}

function Dashboard({ data }: { data: DashboardSummary }) {
  const { kpis, currency, reconciliation, settlementBatches } = data;
  const money = (minor: number) => formatMoney(minor, currency);

  if (kpis.totalTransactions === 0) {
    return (
      <Card>
        <EmptyState
          title={`No ${currency} transactions in this period`}
          hint="Widen the date range, clear the status filter, pick another currency, or create a payment."
          action={<TextLink to="/transactions">Go to transactions</TextLink>}
        />
      </Card>
    );
  }

  return (
    <div className="fade-up space-y-5">
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-4">
        <Stat label="Captured amount" value={money(kpis.capturedMinor)} hint={`${formatNumber(kpis.capturedCount)} captured payments`} />
        <Stat label="Fees assessed" value={money(kpis.feeMinor)} hint="Platform revenue on captures" />
        <Stat label="Net settled" value={money(kpis.netSettlementMinor)} hint={`${money(kpis.unsettledMinor)} awaiting settlement`} tone="good" />
        <Stat label="Refunded" value={money(kpis.refundMinor)} hint={`${formatNumber(kpis.refundedCount)} payments with refunds`} tone="warn" />
      </div>

      <div className="grid grid-cols-2 gap-4 lg:grid-cols-5">
        <Stat label="Transactions" value={formatNumber(kpis.totalTransactions)} hint={`Gross ${money(kpis.grossMinor)}`} />
        <Stat label="Approved" value={formatNumber(kpis.approvedCount)} hint={`${kpis.approvalRatePercent.toFixed(1)}% approval rate`} tone="good" />
        <Stat label="Declined" value={formatNumber(kpis.declinedCount)} tone="bad" />
        <Stat label="Reversed" value={formatNumber(kpis.reversedCount)} />
        <Stat label="Refunded" value={formatNumber(kpis.refundedCount)} tone="warn" />
      </div>

      <div className="grid gap-5 xl:grid-cols-2">
        <Card>
          <CardHeader title={`Captured value per day (${currency})`} hint="Sum of captured amounts by transaction date, UTC" />
          <div className="h-72 px-2 pb-3 pt-5" role="img" aria-label={`Area chart of captured ${currency} value per day`}>
            <ResponsiveContainer width="100%" height="100%">
              <AreaChart data={data.series} margin={{ top: 4, right: 16, left: 0, bottom: 0 }}>
                <defs>
                  <linearGradient id="capturedFill" x1="0" y1="0" x2="0" y2="1">
                    <stop offset="0%" stopColor={SERIES_1} stopOpacity={0.35} />
                    <stop offset="100%" stopColor={SERIES_1} stopOpacity={0.02} />
                  </linearGradient>
                </defs>
                <CartesianGrid stroke="rgba(148,163,184,0.12)" vertical={false} />
                <XAxis dataKey="date" tickFormatter={formatDayMonth} tick={AXIS} tickLine={false} axisLine={false} minTickGap={24} />
                <YAxis tickFormatter={formatMinorCompact} tick={AXIS} tickLine={false} axisLine={false} width={48} />
                <Tooltip content={<ChartTooltip currency={currency} />} cursor={{ stroke: "rgba(148,163,184,0.4)", strokeWidth: 1 }} />
                <Area type="monotone" dataKey="capturedMinor" stroke={SERIES_1} strokeWidth={2} fill="url(#capturedFill)" activeDot={{ r: 4, stroke: "#111831", strokeWidth: 2 }} />
              </AreaChart>
            </ResponsiveContainer>
          </div>
        </Card>

        <Card>
          <CardHeader
            title="Transactions per day"
            hint="Authorization outcomes by transaction date, UTC"
            actions={
              <ul className="flex gap-4 text-xs text-muted" aria-label="Legend">
                <li className="flex items-center gap-1.5">
                  <span aria-hidden className="h-2.5 w-2.5 rounded-sm" style={{ background: SERIES_1 }} /> Approved
                </li>
                <li className="flex items-center gap-1.5">
                  <span aria-hidden className="h-2.5 w-2.5 rounded-sm" style={{ background: SERIES_2 }} /> Declined
                </li>
              </ul>
            }
          />
          <div className="h-72 px-2 pb-3 pt-5" role="img" aria-label="Stacked bar chart of approved and declined transactions per day">
            <ResponsiveContainer width="100%" height="100%">
              <BarChart data={data.series} margin={{ top: 4, right: 16, left: 0, bottom: 0 }} barCategoryGap="28%">
                <CartesianGrid stroke="rgba(148,163,184,0.12)" vertical={false} />
                <XAxis dataKey="date" tickFormatter={formatDayMonth} tick={AXIS} tickLine={false} axisLine={false} minTickGap={24} />
                <YAxis allowDecimals={false} tick={AXIS} tickLine={false} axisLine={false} width={36} />
                <Tooltip content={<ChartTooltip currency={currency} />} cursor={{ fill: "rgba(148,163,184,0.08)" }} />
                <Bar dataKey="approvedCount" stackId="a" fill={SERIES_1} stroke="#111831" strokeWidth={1} />
                <Bar dataKey="declinedCount" stackId="a" fill={SERIES_2} stroke="#111831" strokeWidth={1} radius={[4, 4, 0, 0]} />
              </BarChart>
            </ResponsiveContainer>
          </div>
        </Card>
      </div>

      <div className="grid gap-5 lg:grid-cols-2">
        <Card>
          <CardHeader title={`Settlement batches (${currency})`} hint="By status, for settlement dates in this period" actions={<TextLink to="/settlements">View batches</TextLink>} />
          <ul className="grid grid-cols-2 gap-px bg-line sm:grid-cols-4">
            {Object.entries(settlementBatches).map(([state, count]) => (
              <li key={state} className="bg-surface px-5 py-5">
                <StatusBadge status={state} />
                <p className="num mt-3 text-2xl font-semibold">{formatNumber(count)}</p>
              </li>
            ))}
          </ul>
        </Card>

        <Card>
          <CardHeader title="Reconciliation" hint="Latest run and the exception queue, all currencies" actions={<TextLink to="/reconciliation">Open reconciliation</TextLink>} />
          <div className="grid grid-cols-3 gap-px bg-line">
            <div className="bg-surface px-5 py-5">
              <p className="text-xs text-muted">Match rate, latest run</p>
              <p className="num mt-2 text-2xl font-semibold">
                {reconciliation.latestMatchRatePercent === null ? "—" : reconciliation.latestMatchRatePercent.toFixed(1) + "%"}
              </p>
            </div>
            <div className="bg-surface px-5 py-5">
              <p className="text-xs text-muted">Open exceptions</p>
              <p className={`num mt-2 text-2xl font-semibold ${reconciliation.openExceptions > 0 ? "text-warn" : ""}`}>{formatNumber(reconciliation.openExceptions)}</p>
            </div>
            <div className="bg-surface px-5 py-5">
              <p className="text-xs text-muted">Reviewed</p>
              <p className="num mt-2 text-2xl font-semibold">{formatNumber(reconciliation.reviewedExceptions)}</p>
            </div>
          </div>
          <p className="px-5 py-3 text-xs text-faint">
            {reconciliation.latestRunAt
              ? `Last run ${formatDateTime(reconciliation.latestRunAt)} against ${reconciliation.latestStatement}`
              : "No reconciliation has been run yet."}
          </p>
        </Card>
      </div>
    </div>
  );
}
