import { useState } from "react";
import { useNavigate } from "react-router-dom";
import NewPaymentModal from "../components/NewPaymentModal";
import TransactionTable from "../components/TransactionTable";
import { Button, Card, EmptyState, ErrorState, Filter, FilterBar, LoadingRows, PageHeader, Pagination } from "../components/ui";
import { endOfDayExclusiveIso, startOfDayIso, todayUtc } from "../lib/format";
import { useApi } from "../lib/hooks";
import type { Merchant, Page, Transaction } from "../lib/types";

const STATUSES = ["AUTHORIZED", "CAPTURED", "PARTIALLY_REFUNDED", "REFUNDED", "DECLINED", "REVERSED"];

export default function Transactions() {
  const navigate = useNavigate();
  const [q, setQ] = useState("");
  const [status, setStatus] = useState("");
  const [merchantId, setMerchantId] = useState("");
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [page, setPage] = useState(0);
  const [creating, setCreating] = useState(false);

  const merchants = useApi<Page<Merchant>>("/merchants", { size: 100, sort: "displayName,asc" });
  const { data, error, isPending, refetch } = useApi<Page<Transaction>>("/transactions", {
    q,
    status,
    merchantId,
    from: from ? startOfDayIso(from) : "",
    to: to ? endOfDayExclusiveIso(to) : "",
    page,
    size: 20,
  });

  const filtered = !!(q || status || merchantId || from || to);
  const change = (setter: (value: string) => void) => (value: string) => {
    setter(value);
    setPage(0);
  };
  const clear = () => {
    setQ("");
    setStatus("");
    setMerchantId("");
    setFrom("");
    setTo("");
    setPage(0);
  };

  return (
    <>
      <PageHeader
        title="Transactions"
        description="Every synthetic card payment and where it is in its lifecycle. Open one to capture, reverse or refund it."
        actions={
          <Button variant="primary" onClick={() => setCreating(true)}>
            New payment
          </Button>
        }
      />

      <Card>
        <FilterBar>
          <Filter label="Reference">{(p) => <input {...p} type="search" placeholder="PAY-…" value={q} onChange={(e) => change(setQ)(e.target.value)} />}</Filter>
          <Filter label="Status">
            {(p) => (
              <select {...p} value={status} onChange={(e) => change(setStatus)(e.target.value)}>
                <option value="">All</option>
                {STATUSES.map((s) => (
                  <option key={s} value={s}>
                    {s.replace(/_/g, " ").toLowerCase()}
                  </option>
                ))}
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
          <Filter label="From (UTC)">{(p) => <input {...p} type="date" value={from} max={to || todayUtc()} onChange={(e) => change(setFrom)(e.target.value)} />}</Filter>
          <Filter label="To (UTC)">{(p) => <input {...p} type="date" value={to} min={from} max={todayUtc()} onChange={(e) => change(setTo)(e.target.value)} />}</Filter>
          {filtered ? (
            <Button variant="ghost" onClick={clear}>
              Clear filters
            </Button>
          ) : null}
        </FilterBar>

        {isPending ? (
          <LoadingRows />
        ) : error ? (
          <ErrorState error={error} onRetry={() => void refetch()} />
        ) : data.items.length === 0 ? (
          <EmptyState
            title={filtered ? "No transactions match these filters" : "No transactions yet"}
            hint={filtered ? "Try a wider date range or clear the filters." : "Create a synthetic payment to see the lifecycle in action."}
            action={filtered ? <Button onClick={clear}>Clear filters</Button> : <Button variant="primary" onClick={() => setCreating(true)}>New payment</Button>}
          />
        ) : (
          <>
            <TransactionTable items={data.items} />
            <Pagination page={data.page} totalPages={data.totalPages} totalItems={data.totalItems} onPage={setPage} />
          </>
        )}
      </Card>

      {creating ? <NewPaymentModal onClose={() => setCreating(false)} onCreated={(t) => navigate(`/transactions/${t.id}`)} /> : null}
    </>
  );
}
