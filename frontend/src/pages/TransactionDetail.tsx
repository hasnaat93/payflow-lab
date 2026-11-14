import { useMemo, useState } from "react";
import { useParams } from "react-router-dom";
import JournalEntries from "../components/JournalEntries";
import { Button, Card, CardHeader, ConfirmDialog, ErrorState, Field, KeyValue, LoadingRows, Modal, Money, Notice, PageHeader, StatusBadge, Table, Td, TextLink, Th } from "../components/ui";
import { api, errorMessage, newIdempotencyKey } from "../lib/api";
import { formatBps, formatDateTime, formatMinor, formatMoney, humanize, parseMajorToMinor } from "../lib/format";
import { useApi, useApiMutation } from "../lib/hooks";
import type { Transaction, TransactionDetail as Detail } from "../lib/types";

export default function TransactionDetail() {
  const { id } = useParams();
  const { data, error, isPending, refetch } = useApi<Detail>(`/transactions/${id}`);
  const [dialog, setDialog] = useState<"capture" | "reverse" | "refund" | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const done = (message: string) => () => {
    setDialog(null);
    setNotice(message);
  };
  const capture = useApiMutation(() => api.post<Transaction>(`/transactions/${id}/capture`), done("Captured. The fee was assessed and the ledger entry posted."));
  const reverse = useApiMutation(() => api.post<Transaction>(`/transactions/${id}/reverse`), done("Authorization reversed. No money moved, so nothing was posted to the ledger."));

  if (isPending) {
    return (
      <Card>
        <LoadingRows rows={8} />
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

  const { transaction: t, events, feeAssessment: fee, refunds, journalEntries } = data;
  const canCapture = t.status === "AUTHORIZED";
  const canRefund = t.status === "CAPTURED" || t.status === "PARTIALLY_REFUNDED";

  return (
    <>
      <PageHeader
        back={{ to: "/transactions", label: "Transactions" }}
        title={
          <span className="flex flex-wrap items-center gap-3">
            <span className="num">{t.externalReference}</span>
            <StatusBadge status={t.status} />
          </span>
        }
        description={`${formatMoney(t.amountMinor, t.currency)} at ${t.merchantName}`}
        actions={
          <>
            {canCapture ? (
              <>
                <Button onClick={() => setDialog("reverse")}>Reverse</Button>
                <Button variant="primary" onClick={() => setDialog("capture")}>
                  Capture
                </Button>
              </>
            ) : null}
            {canRefund ? (
              <Button variant="primary" onClick={() => setDialog("refund")}>
                Refund
              </Button>
            ) : null}
          </>
        }
      />

      {notice ? (
        <Notice tone="good" onDismiss={() => setNotice(null)}>
          {notice}
        </Notice>
      ) : null}
      {t.status === "DECLINED" ? (
        <Notice tone="bad">
          Declined by the simulator: <strong>{humanize(t.declineReason)}</strong>. A declined payment is final and cannot be captured.
        </Notice>
      ) : null}

      <div className="grid gap-5 xl:grid-cols-3">
        <div className="space-y-5 xl:col-span-2">
          <Card>
            <CardHeader title="Payment" />
            <KeyValue
              items={[
                ["Merchant", <TextLink to={`/merchants/${t.merchantId}`}>{t.merchantName}</TextLink>],
                ["Card (synthetic)", <span>{t.cardBrand} <span className="num">•••• {t.cardLast4}</span></span>],
                ["Authorized amount", <Money minor={t.amountMinor} currency={t.currency} />],
                ["Captured", <Money minor={t.capturedAmountMinor} currency={t.currency} />],
                ["Refunded", <Money minor={t.refundedAmountMinor} currency={t.currency} />],
                ["Still refundable", <Money minor={t.refundableMinor} currency={t.currency} />],
                ["Created", formatDateTime(t.createdAt)],
                ["Captured at", formatDateTime(t.capturedAt)],
                [
                  "Settlement",
                  t.settlementBatchId ? <TextLink to={`/settlements/${t.settlementBatchId}`}>View settlement batch</TextLink> : t.capturedAt ? "Awaiting the next settlement run" : "Not applicable",
                ],
                ["Internal ID", <span className="num text-xs text-muted">{t.id}</span>],
              ]}
            />
          </Card>

          <Card>
            <CardHeader title="Fee assessment" hint="A snapshot of the fee plan terms at capture time. Editing the plan later does not change this." />
            {fee ? (
              <div className="p-5">
                <dl className="space-y-2.5 text-sm">
                  <Row label="Gross amount" value={<Money minor={fee.grossMinor} currency={fee.currency} />} />
                  <Row label={`Percentage fee (${formatBps(fee.percentageBps)}, rounded half-up)`} value={<Money minor={fee.percentageComponentMinor} />} muted />
                  <Row label="Fixed fee" value={<Money minor={fee.fixedComponentMinor} />} muted />
                  {fee.adjustment !== "NONE" ? <Row label="Adjustment" value={humanize(fee.adjustment)} muted /> : null}
                  <div className="border-t border-line pt-2.5">
                    <Row label={`Total fee (plan ${fee.planCode})`} value={<Money minor={fee.totalFeeMinor} currency={fee.currency} />} />
                  </div>
                  <Row label="Net to merchant" value={<Money minor={fee.netMinor} currency={fee.currency} className="text-good" />} strong />
                </dl>
              </div>
            ) : (
              <p className="px-5 py-6 text-sm text-muted">Fees are assessed when a payment is captured.</p>
            )}
          </Card>

          {refunds.length > 0 ? (
            <Card>
              <CardHeader title="Refunds" />
              <Table label="Refunds">
                <thead>
                  <tr>
                    <Th>Reference</Th>
                    <Th>Reason</Th>
                    <Th>Settlement</Th>
                    <Th>Created</Th>
                    <Th right>Amount</Th>
                  </tr>
                </thead>
                <tbody>
                  {refunds.map((r) => (
                    <tr key={r.id}>
                      <Td className="num">{r.refundReference}</Td>
                      <Td className="text-muted">{r.reason ?? "—"}</Td>
                      <Td>{r.settlementBatchId ? <TextLink to={`/settlements/${r.settlementBatchId}`}>Settled</TextLink> : <span className="text-muted">Pending</span>}</Td>
                      <Td className="whitespace-nowrap text-muted">{formatDateTime(r.createdAt)}</Td>
                      <Td right>
                        <Money minor={r.amountMinor} currency={r.currency} />
                      </Td>
                    </tr>
                  ))}
                </tbody>
              </Table>
            </Card>
          ) : null}

          <Card>
            <CardHeader title="Ledger postings" hint="Double-entry journal entries created by this payment" />
            <JournalEntries entries={journalEntries} />
          </Card>
        </div>

        <Card className="h-fit">
          <CardHeader title="History" hint="Every state change, oldest first" />
          <ol className="p-5">
            {events.map((e, i) => (
              <li key={e.id} className="relative pb-6 pl-6 last:pb-0">
                {i < events.length - 1 ? <span aria-hidden className="absolute left-[5px] top-3 h-full w-px bg-line-strong" /> : null}
                <span aria-hidden className="absolute left-0 top-1.5 h-[11px] w-[11px] rounded-full border-2 border-accent bg-surface" />
                <p className="text-sm font-medium">{humanize(e.eventType)}</p>
                <p className="mt-0.5 text-xs text-muted">
                  {e.fromStatus ? `${humanize(e.fromStatus)} → ` : ""}
                  {humanize(e.toStatus)}
                  {e.amountMinor !== null ? ` · ${formatMoney(e.amountMinor, t.currency)}` : ""}
                </p>
                {e.detail ? <p className="mt-1 text-xs text-faint">{e.detail}</p> : null}
                <p className="mt-1 text-xs text-faint">{formatDateTime(e.createdAt)}</p>
              </li>
            ))}
          </ol>
        </Card>
      </div>

      {dialog === "capture" ? (
        <ConfirmDialog
          title="Capture this payment?"
          description={`Captures the full ${formatMoney(t.amountMinor, t.currency)}, assesses the merchant's fee and posts a balanced entry to the ledger. A capture cannot be undone, only refunded.`}
          confirmLabel="Capture"
          busy={capture.isPending}
          error={capture.error}
          onConfirm={() => capture.mutate(undefined)}
          onClose={() => setDialog(null)}
        />
      ) : null}
      {dialog === "reverse" ? (
        <ConfirmDialog
          title="Reverse this authorization?"
          description="Releases the authorization without capturing it. The payment becomes final and can no longer be captured."
          confirmLabel="Reverse authorization"
          danger
          busy={reverse.isPending}
          error={reverse.error}
          onConfirm={() => reverse.mutate(undefined)}
          onClose={() => setDialog(null)}
        />
      ) : null}
      {dialog === "refund" ? <RefundModal transaction={t} onClose={() => setDialog(null)} onDone={done("Refund recorded and posted to the ledger. It will be deducted in the next settlement run.")} /> : null}
    </>
  );
}

function Row({ label, value, muted = false, strong = false }: { label: string; value: React.ReactNode; muted?: boolean; strong?: boolean }) {
  return (
    <div className={`flex items-baseline justify-between gap-4 ${muted ? "text-muted" : ""} ${strong ? "font-semibold" : ""}`}>
      <dt>{label}</dt>
      <dd>{value}</dd>
    </div>
  );
}

function RefundModal({ transaction: t, onClose, onDone }: { transaction: Transaction; onClose: () => void; onDone: () => void }) {
  const [amount, setAmount] = useState("");
  const [reason, setReason] = useState("");
  const [touched, setTouched] = useState(false);
  const idempotencyKey = useMemo(() => newIdempotencyKey(), []);

  const amountMinor = parseMajorToMinor(amount);
  const problem =
    amountMinor === null
      ? "Enter a positive amount with up to 2 decimals"
      : amountMinor > t.refundableMinor
        ? `At most ${formatMoney(t.refundableMinor, t.currency)} can still be refunded`
        : null;

  const refund = useApiMutation(() => api.post<Transaction>(`/transactions/${t.id}/refunds`, { amountMinor, reason: reason.trim() || null }, idempotencyKey), onDone);

  return (
    <Modal
      title="Refund payment"
      description={`Up to ${formatMoney(t.refundableMinor, t.currency)} can be refunded. The processing fee is not returned to the merchant.`}
      onClose={onClose}
      footer={
        <>
          <Button onClick={onClose} disabled={refund.isPending}>
            Cancel
          </Button>
          <Button
            variant="primary"
            busy={refund.isPending}
            onClick={() => {
              setTouched(true);
              if (!problem) refund.mutate(undefined);
            }}
          >
            Refund{amountMinor && !problem ? ` ${formatMoney(amountMinor, t.currency)}` : ""}
          </Button>
        </>
      }
    >
      {refund.error ? <Notice tone="bad">{errorMessage(refund.error)}</Notice> : null}
      <Field label={`Amount (${t.currency})`} error={touched ? problem : null}>
        {(p) => (
          <div className="flex gap-2">
            <input {...p} inputMode="decimal" placeholder="0.00" value={amount} onChange={(e) => setAmount(e.target.value)} />
            <Button onClick={() => setAmount(formatMinor(t.refundableMinor).replace(/,/g, ""))}>Full</Button>
          </div>
        )}
      </Field>
      <Field label="Reason (optional)">{(p) => <input {...p} maxLength={200} placeholder="e.g. item returned" value={reason} onChange={(e) => setReason(e.target.value)} />}</Field>
    </Modal>
  );
}
