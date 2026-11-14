import { useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import BatchTable from "../components/BatchTable";
import MerchantForm from "../components/MerchantForm";
import NewPaymentModal from "../components/NewPaymentModal";
import TransactionTable from "../components/TransactionTable";
import { Button, Card, CardHeader, ConfirmDialog, EmptyState, ErrorState, KeyValue, LoadingRows, Notice, PageHeader, Pagination, StatusBadge, TextLink } from "../components/ui";
import { api } from "../lib/api";
import { formatBps, formatDateTime, formatMoney } from "../lib/format";
import { useApi, useApiMutation } from "../lib/hooks";
import type { FeePlan, Merchant, Page, SettlementBatch, Transaction } from "../lib/types";

export default function MerchantDetail() {
  const { id } = useParams();
  const navigate = useNavigate();
  const merchant = useApi<Merchant>(`/merchants/${id}`);
  const plan = useApi<FeePlan>(`/fee-plans/${merchant.data?.feePlanId}`, undefined, { enabled: !!merchant.data });
  const [txPage, setTxPage] = useState(0);
  const [batchPage, setBatchPage] = useState(0);
  const transactions = useApi<Page<Transaction>>("/transactions", { merchantId: id, page: txPage, size: 10 });
  const batches = useApi<Page<SettlementBatch>>("/settlement-batches", { merchantId: id, page: batchPage, size: 10 });
  const [dialog, setDialog] = useState<"edit" | "status" | "pay" | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const m = merchant.data;
  const toggle = useApiMutation(
    () => api.post<Merchant>(`/merchants/${id}/${m?.status === "ACTIVE" ? "suspend" : "activate"}`),
    (updated) => {
      setDialog(null);
      setNotice(updated.status === "ACTIVE" ? "Merchant activated. It can accept payments again." : "Merchant suspended. New authorizations will be rejected.");
    },
  );

  if (merchant.isPending) {
    return (
      <Card>
        <LoadingRows />
      </Card>
    );
  }
  if (merchant.error || !m) {
    return (
      <Card>
        <ErrorState error={merchant.error} onRetry={() => void merchant.refetch()} />
      </Card>
    );
  }

  return (
    <>
      <PageHeader
        back={{ to: "/merchants", label: "Merchants" }}
        title={
          <span className="flex flex-wrap items-center gap-3">
            {m.displayName} <StatusBadge status={m.status} />
          </span>
        }
        description={`${m.merchantCode} · settles in ${m.currency}`}
        actions={
          <>
            <Button onClick={() => setDialog("edit")}>Edit</Button>
            <Button variant={m.status === "ACTIVE" ? "danger" : "secondary"} onClick={() => setDialog("status")}>
              {m.status === "ACTIVE" ? "Suspend" : "Activate"}
            </Button>
            {m.status === "ACTIVE" ? (
              <Button variant="primary" onClick={() => setDialog("pay")}>
                New payment
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

      <div className="mb-5 grid gap-5 lg:grid-cols-2">
        <Card>
          <CardHeader title="Profile" />
          <KeyValue
            items={[
              ["Display name", m.displayName],
              ["Legal name", m.legalName ?? "—"],
              ["Merchant code", <span className="num">{m.merchantCode}</span>],
              ["Currency", m.currency],
              ["Created", formatDateTime(m.createdAt)],
              ["Last updated", formatDateTime(m.updatedAt)],
            ]}
          />
        </Card>
        <Card>
          <CardHeader title="Fee plan" hint="Applied when a payment is captured" actions={<TextLink to="/fee-plans">Manage fee plans</TextLink>} />
          {plan.data ? (
            <KeyValue
              items={[
                ["Plan", `${plan.data.code} · ${plan.data.name}`],
                ["Percentage", formatBps(plan.data.percentageBps)],
                ["Fixed fee", formatMoney(plan.data.fixedFeeMinor, plan.data.currency)],
                ["Minimum fee", plan.data.minFeeMinor === null ? "None" : formatMoney(plan.data.minFeeMinor, plan.data.currency)],
                ["Maximum fee", plan.data.maxFeeMinor === null ? "None" : formatMoney(plan.data.maxFeeMinor, plan.data.currency)],
                ["Plan status", <StatusBadge status={plan.data.active ? "ACTIVE" : "INACTIVE"} />],
              ]}
            />
          ) : (
            <LoadingRows rows={3} />
          )}
        </Card>
      </div>

      <Card className="mb-5">
        <CardHeader title="Transactions" hint="Most recent first" />
        {transactions.isPending ? (
          <LoadingRows rows={4} />
        ) : transactions.error ? (
          <ErrorState error={transactions.error} onRetry={() => void transactions.refetch()} />
        ) : transactions.data.items.length === 0 ? (
          <EmptyState title="No transactions for this merchant yet" />
        ) : (
          <>
            <TransactionTable items={transactions.data.items} showMerchant={false} />
            <Pagination page={transactions.data.page} totalPages={transactions.data.totalPages} totalItems={transactions.data.totalItems} onPage={setTxPage} />
          </>
        )}
      </Card>

      <Card>
        <CardHeader title="Settlement batches" hint="Payouts to this merchant" />
        {batches.isPending ? (
          <LoadingRows rows={4} />
        ) : batches.error ? (
          <ErrorState error={batches.error} onRetry={() => void batches.refetch()} />
        ) : batches.data.items.length === 0 ? (
          <EmptyState title="Nothing has been settled for this merchant yet" hint="Captured payments are paid out by the next settlement run." />
        ) : (
          <>
            <BatchTable items={batches.data.items} showMerchant={false} />
            <Pagination page={batches.data.page} totalPages={batches.data.totalPages} totalItems={batches.data.totalItems} onPage={setBatchPage} />
          </>
        )}
      </Card>

      {dialog === "edit" ? (
        <MerchantForm
          merchant={m}
          onClose={() => setDialog(null)}
          onSaved={() => {
            setDialog(null);
            setNotice("Merchant updated.");
          }}
        />
      ) : null}
      {dialog === "status" ? (
        <ConfirmDialog
          title={m.status === "ACTIVE" ? "Suspend this merchant?" : "Activate this merchant?"}
          description={
            m.status === "ACTIVE"
              ? "New authorizations will be rejected. Payments already captured still settle, and existing payments can still be refunded."
              : "The merchant will be able to accept payments again."
          }
          confirmLabel={m.status === "ACTIVE" ? "Suspend merchant" : "Activate merchant"}
          danger={m.status === "ACTIVE"}
          busy={toggle.isPending}
          error={toggle.error}
          onConfirm={() => toggle.mutate(undefined)}
          onClose={() => setDialog(null)}
        />
      ) : null}
      {dialog === "pay" ? <NewPaymentModal merchantId={m.id} onClose={() => setDialog(null)} onCreated={(t) => navigate(`/transactions/${t.id}`)} /> : null}
    </>
  );
}
