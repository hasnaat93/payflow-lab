import { Link, useParams } from "react-router-dom";
import JournalEntries from "../components/JournalEntries";
import { Card, CardHeader, EmptyState, ErrorState, KeyValue, LoadingRows, Money, Notice, PageHeader, ROW, Stat, StatusBadge, Table, Td, TextLink, Th } from "../components/ui";
import { formatDate, formatDateTime, formatMoney } from "../lib/format";
import { useApi } from "../lib/hooks";
import type { SettlementBatchDetail } from "../lib/types";

export default function SettlementDetail() {
  const { id } = useParams();
  const { data, error, isPending, refetch } = useApi<SettlementBatchDetail>(`/settlement-batches/${id}`);

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
  const { batch: b, lines, journalEntries } = data;

  return (
    <>
      <PageHeader
        back={{ to: "/settlements", label: "Settlement batches" }}
        title={
          <span className="flex flex-wrap items-center gap-3">
            <span className="num">{b.batchReference}</span>
            <StatusBadge status={b.status} />
          </span>
        }
        description={`Payout to ${b.merchantName} for ${formatDate(b.settlementDate)}`}
      />

      {b.status === "FAILED" ? (
        <Notice tone="bad">
          <p className="font-medium">This batch failed and nothing was paid out.</p>
          <p className="mt-1 text-muted">{b.failureReason}</p>
          <p className="mt-1 text-muted">{b.summary}</p>
        </Notice>
      ) : b.summary ? (
        <Notice tone="good">{b.summary}</Notice>
      ) : null}

      <div className="mb-5 grid grid-cols-2 gap-4 lg:grid-cols-4">
        <Stat label="Gross captured" value={formatMoney(b.grossMinor, b.currency)} hint={`${b.transactionCount} capture(s)`} />
        <Stat label="Fees" value={formatMoney(b.feeMinor, b.currency)} hint="Kept by the platform" />
        <Stat label="Refunds" value={formatMoney(b.refundMinor, b.currency)} hint={`${b.refundCount} refund(s)`} tone="warn" />
        <Stat label="Net payout" value={formatMoney(b.netMinor, b.currency)} hint="Gross − fees − refunds" tone={b.status === "FAILED" ? "bad" : "good"} />
      </div>

      <div className="grid gap-5 xl:grid-cols-3">
        <Card className="xl:col-span-2">
          <CardHeader title="Batch lines" hint="Each capture or refund appears on exactly one batch" />
          {lines.length === 0 ? (
            <EmptyState title="No lines" hint="A failed batch settles nothing; its items carry forward to the next run." />
          ) : (
            <Table label="Batch lines">
              <thead>
                <tr>
                  <Th>Type</Th>
                  <Th>Payment</Th>
                  <Th>Refund</Th>
                  <Th right>Gross</Th>
                  <Th right>Fee</Th>
                  <Th right>Net</Th>
                </tr>
              </thead>
              <tbody>
                {lines.map((l) => (
                  <tr key={l.id} className={ROW}>
                    <Td>
                      <StatusBadge status={l.lineType} />
                    </Td>
                    <Td>
                      <Link to={`/transactions/${l.transactionId}`} className="num text-accent-strong underline-offset-4 hover:underline">
                        {l.externalReference}
                      </Link>
                    </Td>
                    <Td className="num text-muted">{l.refundReference ?? "—"}</Td>
                    <Td right>
                      <Money minor={l.grossMinor} />
                    </Td>
                    <Td right className="text-muted">
                      <Money minor={l.feeMinor} />
                    </Td>
                    <Td right>
                      <Money minor={l.netMinor} className={l.netMinor < 0 ? "text-warn" : ""} />
                    </Td>
                  </tr>
                ))}
              </tbody>
            </Table>
          )}
        </Card>

        <Card className="h-fit">
          <CardHeader title="Execution" />
          <KeyValue
            items={[
              ["Merchant", <TextLink to={`/merchants/${b.merchantId}`}>{b.merchantName}</TextLink>],
              ["Currency", b.currency],
              ["Settlement date", formatDate(b.settlementDate)],
              ["Started", formatDateTime(b.startedAt)],
              ["Finished", formatDateTime(b.completedAt)],
              ["Batch ID", <span className="num text-xs text-muted">{b.id}</span>],
            ]}
          />
        </Card>
      </div>

      <Card className="mt-5">
        <CardHeader title="Ledger postings" hint="The payout moves from Merchant Payable to Settlement Clearing; refunds are cleared against the network" />
        <JournalEntries entries={journalEntries} />
      </Card>
    </>
  );
}
