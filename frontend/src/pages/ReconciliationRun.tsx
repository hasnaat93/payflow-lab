import { useState } from "react";
import { useParams } from "react-router-dom";
import ExceptionTable from "../components/ExceptionTable";
import { Card, CardHeader, EmptyState, ErrorState, Filter, LoadingRows, PageHeader, Stat } from "../components/ui";
import { formatDate, formatDateTime, humanize } from "../lib/format";
import { useApi } from "../lib/hooks";
import type { ReconciliationRunDetail } from "../lib/types";

export default function ReconciliationRunPage() {
  const { id } = useParams();
  const { data, error, isPending, refetch } = useApi<ReconciliationRunDetail>(`/reconciliation/runs/${id}`);
  const [outcome, setOutcome] = useState("");

  if (isPending) {
    return (
      <Card>
        <LoadingRows />
      </Card>
    );
  }
  if (error) {
    return (
      <Card>
        <ErrorState error={error} onRetry={() => void refetch()} />
      </Card>
    );
  }
  const { run, items } = data;
  const outcomes = [...new Set(items.map((i) => i.outcome))];
  const visible = outcome ? items.filter((i) => i.outcome === outcome) : items;

  return (
    <>
      <PageHeader
        back={{ to: "/reconciliation", label: "Reconciliation" }}
        title={<span className="num">{run.statementReference}</span>}
        description={`Run on ${formatDateTime(run.startedAt)} for settlement dates ${formatDate(run.periodStart)} – ${formatDate(run.periodEnd)}`}
      />

      <div className="mb-5 grid grid-cols-2 gap-4 lg:grid-cols-6">
        <Stat label="Match rate" value={run.matchRatePercent.toFixed(1) + "%"} hint={`${run.totalItems} items compared`} tone={run.exceptionCount === 0 ? "good" : "warn"} />
        <Stat label="Matched" value={run.matchedCount} tone="good" />
        <Stat label="Amount mismatch" value={run.amountMismatchCount} tone={run.amountMismatchCount ? "bad" : "default"} />
        <Stat label="Date mismatch" value={run.dateMismatchCount} tone={run.dateMismatchCount ? "warn" : "default"} />
        <Stat label="Missing on statement" value={run.unmatchedInternalCount} tone={run.unmatchedInternalCount ? "warn" : "default"} />
        <Stat label="Unknown on statement" value={run.unmatchedExternalCount} tone={run.unmatchedExternalCount ? "warn" : "default"} />
      </div>

      <Card className="mb-5">
        <CardHeader title="How items are matched" />
        <ol className="list-decimal space-y-1.5 px-9 py-5 text-sm leading-relaxed text-muted">
          <li>A batch pairs with the statement line carrying the same batch reference.</li>
          <li>If that line is for a different merchant or currency it is not the same payout: both sides are left unmatched.</li>
          <li>Amounts must be identical to the minor unit. Any difference is an amount mismatch; there is no tolerance.</li>
          <li>The value date must fall within two days after the settlement date, otherwise it is a date mismatch.</li>
          <li>Statement lines with no batch, or repeated references, are unknown on the statement.</li>
        </ol>
      </Card>

      <Card>
        <CardHeader
          title="Items"
          hint={`${visible.length} of ${items.length}`}
          actions={
            <Filter label="Outcome">
              {(p) => (
                <select {...p} value={outcome} onChange={(e) => setOutcome(e.target.value)}>
                  <option value="">All outcomes</option>
                  {outcomes.map((o) => (
                    <option key={o} value={o}>
                      {humanize(o)}
                    </option>
                  ))}
                </select>
              )}
            </Filter>
          }
        />
        {visible.length === 0 ? <EmptyState title="No items" hint="The statement and the period contain nothing to compare." /> : <ExceptionTable items={visible} />}
      </Card>
    </>
  );
}
