import { useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import ExceptionTable from "../components/ExceptionTable";
import { Badge, Button, Card, CardHeader, EmptyState, ErrorState, Field, Filter, LoadingRows, Modal, Notice, PageHeader, Pagination, ROW, Table, Td, Th } from "../components/ui";
import { api, errorMessage } from "../lib/api";
import { daysAgoUtc, formatDate, formatDateTime, todayUtc } from "../lib/format";
import { useApi, useApiMutation } from "../lib/hooks";
import type { Page, ReconciliationItem, ReconciliationRun, ReconciliationRunDetail, Statement } from "../lib/types";

export default function Reconciliation() {
  const navigate = useNavigate();
  const statements = useApi<Statement[]>("/reconciliation/statements");
  const [runPage, setRunPage] = useState(0);
  const runs = useApi<Page<ReconciliationRun>>("/reconciliation/runs", { page: runPage, size: 8 });
  const [reviewStatus, setReviewStatus] = useState("OPEN");
  const [queuePage, setQueuePage] = useState(0);
  const queue = useApi<Page<ReconciliationItem>>("/reconciliation/exceptions", { reviewStatus, page: queuePage, size: 15 });
  const [generating, setGenerating] = useState(false);

  const reconcile = useApiMutation(
    (statementId: string) => api.post<ReconciliationRunDetail>("/reconciliation/runs", { statementId }),
    (detail) => navigate(`/reconciliation/runs/${detail.run.id}`),
  );

  return (
    <>
      <PageHeader
        title="Reconciliation"
        description="Compares what the platform paid out (completed settlement batches) with what an external statement says arrived. Anything that does not agree exactly becomes an exception for a person to review."
        actions={
          <Button variant="primary" onClick={() => setGenerating(true)}>
            Generate statement
          </Button>
        }
      />
      {reconcile.error ? <Notice tone="bad">{errorMessage(reconcile.error)}</Notice> : null}

      <div className="mb-5 grid gap-5 xl:grid-cols-2">
        <Card>
          <CardHeader title="External statements" hint="Synthetic statements generated from completed batches" />
          {statements.isPending ? (
            <LoadingRows rows={4} />
          ) : statements.error ? (
            <ErrorState error={statements.error} onRetry={() => void statements.refetch()} />
          ) : statements.data.length === 0 ? (
            <EmptyState
              title="No statements yet"
              hint="Generate a synthetic statement for a period that has completed settlement batches."
              action={
                <Button variant="primary" onClick={() => setGenerating(true)}>
                  Generate statement
                </Button>
              }
            />
          ) : (
            <Table label="External statements">
              <thead>
                <tr>
                  <Th>Statement</Th>
                  <Th>Period</Th>
                  <Th right>Lines</Th>
                  <Th />
                </tr>
              </thead>
              <tbody>
                {statements.data.map((s) => (
                  <tr key={s.id} className={ROW}>
                    <Td>
                      <p className="num font-medium">{s.statementReference}</p>
                      <p className="mt-0.5 text-xs text-muted">
                        {s.source === "SYNTHETIC_CLEAN" ? "Clean" : "With injected anomalies"} · {formatDateTime(s.createdAt)}
                      </p>
                    </Td>
                    <Td className="whitespace-nowrap text-muted">
                      {formatDate(s.periodStart)} – {formatDate(s.periodEnd)}
                    </Td>
                    <Td right className="num">
                      {s.lineCount}
                    </Td>
                    <Td right>
                      <Button size="sm" busy={reconcile.isPending && reconcile.variables === s.id} disabled={reconcile.isPending} onClick={() => reconcile.mutate(s.id)}>
                        Reconcile
                      </Button>
                    </Td>
                  </tr>
                ))}
              </tbody>
            </Table>
          )}
        </Card>

        <Card>
          <CardHeader title="Run history" hint="Every run is kept; re-running never overwrites an earlier result" />
          {runs.isPending ? (
            <LoadingRows rows={4} />
          ) : runs.error ? (
            <ErrorState error={runs.error} onRetry={() => void runs.refetch()} />
          ) : runs.data.items.length === 0 ? (
            <EmptyState title="No reconciliation runs yet" hint="Choose a statement and select Reconcile." />
          ) : (
            <>
              <Table label="Reconciliation runs">
                <thead>
                  <tr>
                    <Th>Run</Th>
                    <Th right>Items</Th>
                    <Th right>Match rate</Th>
                    <Th right>Exceptions</Th>
                  </tr>
                </thead>
                <tbody>
                  {runs.data.items.map((r) => (
                    <tr key={r.id} className={ROW}>
                      <Td>
                        <Link to={`/reconciliation/runs/${r.id}`} className="font-medium text-accent-strong underline-offset-4 hover:underline">
                          {formatDateTime(r.startedAt)}
                        </Link>
                        <p className="num mt-0.5 text-xs text-muted">{r.statementReference}</p>
                      </Td>
                      <Td right className="num">
                        {r.totalItems}
                      </Td>
                      <Td right className="num">
                        {r.matchRatePercent.toFixed(1)}%
                      </Td>
                      <Td right>{r.exceptionCount === 0 ? <Badge tone="good">None</Badge> : <Badge tone="warn">{r.exceptionCount}</Badge>}</Td>
                    </tr>
                  ))}
                </tbody>
              </Table>
              <Pagination page={runs.data.page} totalPages={runs.data.totalPages} totalItems={runs.data.totalItems} onPage={setRunPage} />
            </>
          )}
        </Card>
      </div>

      <Card>
        <CardHeader
          title="Exception queue"
          hint="Items from all runs that did not match"
          actions={
            <Filter label="Show">
              {(p) => (
                <select
                  {...p}
                  value={reviewStatus}
                  onChange={(e) => {
                    setReviewStatus(e.target.value);
                    setQueuePage(0);
                  }}
                >
                  <option value="OPEN">Open</option>
                  <option value="REVIEWED">Reviewed</option>
                  <option value="">Open and reviewed</option>
                </select>
              )}
            </Filter>
          }
        />
        {queue.isPending ? (
          <LoadingRows />
        ) : queue.error ? (
          <ErrorState error={queue.error} onRetry={() => void queue.refetch()} />
        ) : queue.data.items.length === 0 ? (
          <EmptyState title={reviewStatus === "OPEN" ? "No open exceptions" : "Nothing here"} hint={reviewStatus === "OPEN" ? "Everything that failed to match has been reviewed." : undefined} />
        ) : (
          <>
            <ExceptionTable items={queue.data.items} />
            <Pagination page={queue.data.page} totalPages={queue.data.totalPages} totalItems={queue.data.totalItems} onPage={setQueuePage} />
          </>
        )}
      </Card>

      {generating ? <GenerateModal onClose={() => setGenerating(false)} /> : null}
    </>
  );
}

function GenerateModal({ onClose }: { onClose: () => void }) {
  const [periodStart, setPeriodStart] = useState(daysAgoUtc(6));
  const [periodEnd, setPeriodEnd] = useState(todayUtc());
  const [scenario, setScenario] = useState("WITH_ANOMALIES");
  const generate = useApiMutation(() => api.post<Statement>("/reconciliation/statements/generate", { periodStart, periodEnd, scenario }), onClose);

  return (
    <Modal
      title="Generate synthetic statement"
      description="Builds an external statement from the completed settlement batches in the period. There is no real bank; this stands in for one."
      onClose={onClose}
      footer={
        <>
          <Button onClick={onClose} disabled={generate.isPending}>
            Cancel
          </Button>
          <Button variant="primary" busy={generate.isPending} onClick={() => generate.mutate(undefined)}>
            Generate
          </Button>
        </>
      }
    >
      {generate.error ? <Notice tone="bad">{errorMessage(generate.error)}</Notice> : null}
      <div className="grid gap-4 sm:grid-cols-2">
        <Field label="Period start (UTC)">{(p) => <input {...p} type="date" value={periodStart} max={periodEnd} onChange={(e) => e.target.value && setPeriodStart(e.target.value)} />}</Field>
        <Field label="Period end (UTC)">{(p) => <input {...p} type="date" value={periodEnd} min={periodStart} max={todayUtc()} onChange={(e) => e.target.value && setPeriodEnd(e.target.value)} />}</Field>
      </div>
      <Field
        label="Scenario"
        hint={
          scenario === "CLEAN"
            ? "Every batch appears exactly as settled, so reconciliation should match 100%."
            : "Defects are injected by position, not at random: a short payment, a missing line, a late value date and an unknown line."
        }
      >
        {(p) => (
          <select {...p} value={scenario} onChange={(e) => setScenario(e.target.value)}>
            <option value="WITH_ANOMALIES">With anomalies (shows the exception types)</option>
            <option value="CLEAN">Clean (everything matches)</option>
          </select>
        )}
      </Field>
    </Modal>
  );
}
