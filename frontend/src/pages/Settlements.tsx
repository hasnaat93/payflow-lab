import { useState } from "react";
import BatchTable from "../components/BatchTable";
import { Button, Card, ConfirmDialog, EmptyState, ErrorState, Field, Filter, FilterBar, LoadingRows, Notice, PageHeader, Pagination, TextLink } from "../components/ui";
import { api } from "../lib/api";
import { formatDate, todayUtc } from "../lib/format";
import { useApi, useApiMutation } from "../lib/hooks";
import type { Merchant, Page, SettlementBatch, SettlementRun } from "../lib/types";

export default function Settlements() {
  const [status, setStatus] = useState("");
  const [merchantId, setMerchantId] = useState("");
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [page, setPage] = useState(0);
  const [confirming, setConfirming] = useState(false);
  const [runDate, setRunDate] = useState(todayUtc());
  const [result, setResult] = useState<SettlementRun | null>(null);

  const merchants = useApi<Page<Merchant>>("/merchants", { size: 100, sort: "displayName,asc" });
  const { data, error, isPending, refetch } = useApi<Page<SettlementBatch>>("/settlement-batches", { status, merchantId, from, to, page, size: 20 });

  const run = useApiMutation(
    () => api.post<SettlementRun>("/settlement-batches/run", { settlementDate: runDate }),
    (r) => {
      setConfirming(false);
      setResult(r);
      setPage(0);
    },
  );
  const filtered = !!(status || merchantId || from || to);
  const change = (setter: (value: string) => void) => (value: string) => {
    setter(value);
    setPage(0);
  };

  return (
    <>
      <PageHeader
        title="Settlement batches"
        description="A settlement run groups each merchant's captured payments and refunds into one payout, and posts it to the ledger."
        actions={
          <Button variant="primary" onClick={() => setConfirming(true)}>
            Run settlement
          </Button>
        }
      />

      {result ? (
        <Notice tone={result.failed > 0 ? "info" : "good"} onDismiss={() => setResult(null)}>
          <p className="font-medium">{result.summary}</p>
          {result.batches.length > 0 ? (
            <ul className="mt-2 space-y-1 text-muted">
              {result.batches.map((b) => (
                <li key={b.id}>
                  <TextLink to={`/settlements/${b.id}`}>{b.batchReference}</TextLink> · {b.status.toLowerCase()}
                  {b.failureReason ? ` · ${b.failureReason}` : ""}
                </li>
              ))}
            </ul>
          ) : null}
        </Notice>
      ) : null}

      <Card>
        <FilterBar>
          <Filter label="Status">
            {(p) => (
              <select {...p} value={status} onChange={(e) => change(setStatus)(e.target.value)}>
                <option value="">All</option>
                <option value="COMPLETED">Completed</option>
                <option value="FAILED">Failed</option>
                <option value="PROCESSING">Processing</option>
                <option value="CREATED">Created</option>
              </select>
            )}
          </Filter>
          <Filter label="Merchant">
            {(p) => (
              <select {...p} value={merchantId} onChange={(e) => change(setMerchantId)(e.target.value)}>
                <option value="">All</option>
                {merchants.data?.items.map((m) => (
                  <option key={m.id} value={m.id}>
                    {m.displayName}
                  </option>
                ))}
              </select>
            )}
          </Filter>
          <Filter label="Settled from">{(p) => <input {...p} type="date" value={from} max={to || todayUtc()} onChange={(e) => change(setFrom)(e.target.value)} />}</Filter>
          <Filter label="Settled to">{(p) => <input {...p} type="date" value={to} min={from} max={todayUtc()} onChange={(e) => change(setTo)(e.target.value)} />}</Filter>
        </FilterBar>

        {isPending ? (
          <LoadingRows />
        ) : error ? (
          <ErrorState error={error} onRetry={() => void refetch()} />
        ) : data.items.length === 0 ? (
          <EmptyState
            title={filtered ? "No batches match these filters" : "No settlement batches yet"}
            hint={filtered ? "Change or clear the filters." : "Capture a payment, then run settlement to create the first payout."}
            action={
              filtered ? undefined : (
                <Button variant="primary" onClick={() => setConfirming(true)}>
                  Run settlement
                </Button>
              )
            }
          />
        ) : (
          <>
            <BatchTable items={data.items} />
            <Pagination page={data.page} totalPages={data.totalPages} totalItems={data.totalItems} onPage={setPage} />
          </>
        )}
      </Card>

      {confirming ? (
        <ConfirmDialog
          title="Run settlement?"
          description="Every captured payment and refund not yet settled, up to the end of the settlement date, is grouped into one batch per merchant and posted to the ledger. Items already settled are never included again."
          confirmLabel={`Settle up to ${formatDate(runDate)}`}
          busy={run.isPending}
          error={run.error}
          onConfirm={() => run.mutate(undefined)}
          onClose={() => setConfirming(false)}
        >
          <Field label="Settlement date (UTC)" hint="Defaults to today. Cannot be in the future.">
            {(p) => <input {...p} type="date" value={runDate} max={todayUtc()} onChange={(e) => e.target.value && setRunDate(e.target.value)} />}
          </Field>
        </ConfirmDialog>
      ) : null}
    </>
  );
}
