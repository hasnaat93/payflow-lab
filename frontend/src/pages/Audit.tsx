import { useState } from "react";
import { Badge, Card, EmptyState, ErrorState, Filter, FilterBar, LoadingRows, PageHeader, Pagination, ROW, Table, Td, Th } from "../components/ui";
import { formatDateTime, humanize } from "../lib/format";
import { useApi } from "../lib/hooks";
import type { AuditEvent, Page } from "../lib/types";

const ENTITY_TYPES = ["TRANSACTION", "MERCHANT", "FEE_PLAN", "JOURNAL_ENTRY", "SETTLEMENT_BATCH", "SETTLEMENT_RUN", "EXTERNAL_STATEMENT", "RECONCILIATION_RUN", "RECONCILIATION_ITEM"];
const ACTIONS = [
  "TRANSACTION_CREATED",
  "AUTHORIZATION_APPROVED",
  "AUTHORIZATION_DECLINED",
  "TRANSACTION_CAPTURED",
  "TRANSACTION_REVERSED",
  "TRANSACTION_REFUNDED",
  "FEE_ASSESSED",
  "LEDGER_POSTED",
  "SETTLEMENT_RUN_EXECUTED",
  "SETTLEMENT_BATCH_COMPLETED",
  "SETTLEMENT_BATCH_FAILED",
  "STATEMENT_GENERATED",
  "RECONCILIATION_RUN_EXECUTED",
  "EXCEPTION_REVIEWED",
  "MERCHANT_CREATED",
  "MERCHANT_UPDATED",
  "MERCHANT_SUSPENDED",
  "MERCHANT_ACTIVATED",
  "FEE_PLAN_CREATED",
  "FEE_PLAN_UPDATED",
];

function Metadata({ json }: { json: string | null }) {
  if (!json) return <span className="text-faint">—</span>;
  let entries: [string, unknown][];
  try {
    entries = Object.entries(JSON.parse(json) as Record<string, unknown>);
  } catch {
    return <span className="num text-xs text-muted">{json}</span>;
  }
  return (
    <dl className="flex flex-wrap gap-x-4 gap-y-1 text-xs">
      {entries.map(([key, value]) => (
        <div key={key} className="flex gap-1.5">
          <dt className="text-faint">{key}</dt>
          <dd className="num text-muted">{String(value)}</dd>
        </div>
      ))}
    </dl>
  );
}

export default function Audit() {
  const [entityType, setEntityType] = useState("");
  const [action, setAction] = useState("");
  const [entityId, setEntityId] = useState("");
  const [page, setPage] = useState(0);
  const { data, error, isPending, refetch } = useApi<Page<AuditEvent>>("/audit-events", { entityType, action, entityId: entityId.trim(), page, size: 25 });
  const change = (setter: (value: string) => void) => (value: string) => {
    setter(value);
    setPage(0);
  };

  return (
    <>
      <PageHeader
        title="Audit trail"
        description="Who did what, to which record, and when. Events are written in the same database transaction as the change they describe, and never contain card data."
      />
      <Card>
        <FilterBar>
          <Filter label="Record type">
            {(p) => (
              <select {...p} value={entityType} onChange={(e) => change(setEntityType)(e.target.value)}>
                <option value="">All</option>
                {ENTITY_TYPES.map((t) => (
                  <option key={t} value={t}>
                    {humanize(t)}
                  </option>
                ))}
              </select>
            )}
          </Filter>
          <Filter label="Action">
            {(p) => (
              <select {...p} value={action} onChange={(e) => change(setAction)(e.target.value)}>
                <option value="">All</option>
                {ACTIONS.map((a) => (
                  <option key={a} value={a}>
                    {humanize(a)}
                  </option>
                ))}
              </select>
            )}
          </Filter>
          <Filter label="Record ID">{(p) => <input {...p} type="search" placeholder="Paste an ID" value={entityId} onChange={(e) => change(setEntityId)(e.target.value)} />}</Filter>
        </FilterBar>

        {isPending ? (
          <LoadingRows rows={8} />
        ) : error ? (
          <ErrorState error={error} onRetry={() => void refetch()} />
        ) : data.items.length === 0 ? (
          <EmptyState title="No audit events match" hint="Change the filters, or perform an action to generate events." />
        ) : (
          <>
            <Table label="Audit events">
              <thead>
                <tr>
                  <Th>When</Th>
                  <Th>Action</Th>
                  <Th>Record</Th>
                  <Th>Actor</Th>
                  <Th>Details</Th>
                </tr>
              </thead>
              <tbody>
                {data.items.map((e) => (
                  <tr key={e.id} className={ROW}>
                    <Td className="whitespace-nowrap text-muted">{formatDateTime(e.occurredAt)}</Td>
                    <Td className="whitespace-nowrap font-medium">{humanize(e.action)}</Td>
                    <Td>
                      <p className="whitespace-nowrap">{humanize(e.entityType)}</p>
                      <p className="num mt-0.5 text-xs text-faint">{e.entityId}</p>
                    </Td>
                    <Td>
                      <Badge tone={e.actor === "system" ? "neutral" : "info"}>{e.actor}</Badge>
                    </Td>
                    <Td>
                      <Metadata json={e.metadata} />
                    </Td>
                  </tr>
                ))}
              </tbody>
            </Table>
            <Pagination page={data.page} totalPages={data.totalPages} totalItems={data.totalItems} onPage={setPage} />
          </>
        )}
      </Card>
    </>
  );
}
