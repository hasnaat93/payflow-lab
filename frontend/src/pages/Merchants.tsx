import { useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import MerchantForm from "../components/MerchantForm";
import { Button, Card, EmptyState, ErrorState, Filter, FilterBar, LoadingRows, PageHeader, Pagination, ROW, StatusBadge, Table, Td, Th } from "../components/ui";
import { formatDate } from "../lib/format";
import { useApi } from "../lib/hooks";
import type { Merchant, Page } from "../lib/types";

export default function Merchants() {
  const navigate = useNavigate();
  const [q, setQ] = useState("");
  const [status, setStatus] = useState("");
  const [page, setPage] = useState(0);
  const [creating, setCreating] = useState(false);
  const { data, error, isPending, refetch } = useApi<Page<Merchant>>("/merchants", { q, status, page, size: 20, sort: "displayName,asc" });
  const filtered = !!(q || status);

  return (
    <>
      <PageHeader
        title="Merchants"
        description="Fictional businesses that accept payments. Each settles in one currency and is charged by one fee plan."
        actions={
          <Button variant="primary" onClick={() => setCreating(true)}>
            New merchant
          </Button>
        }
      />
      <Card>
        <FilterBar>
          <Filter label="Search">
            {(p) => (
              <input
                {...p}
                type="search"
                placeholder="Name or code"
                value={q}
                onChange={(e) => {
                  setQ(e.target.value);
                  setPage(0);
                }}
              />
            )}
          </Filter>
          <Filter label="Status">
            {(p) => (
              <select
                {...p}
                value={status}
                onChange={(e) => {
                  setStatus(e.target.value);
                  setPage(0);
                }}
              >
                <option value="">All</option>
                <option value="ACTIVE">Active</option>
                <option value="SUSPENDED">Suspended</option>
              </select>
            )}
          </Filter>
        </FilterBar>

        {isPending ? (
          <LoadingRows />
        ) : error ? (
          <ErrorState error={error} onRetry={() => void refetch()} />
        ) : data.items.length === 0 ? (
          <EmptyState
            title={filtered ? "No merchants match" : "No merchants yet"}
            hint={filtered ? "Try a different name, code or status." : "Create a fee plan first, then add a merchant."}
          />
        ) : (
          <>
            <Table label="Merchants">
              <thead>
                <tr>
                  <Th>Merchant</Th>
                  <Th>Code</Th>
                  <Th>Status</Th>
                  <Th>Currency</Th>
                  <Th>Fee plan</Th>
                  <Th>Created</Th>
                </tr>
              </thead>
              <tbody>
                {data.items.map((m) => (
                  <tr key={m.id} className={ROW}>
                    <Td>
                      <Link to={`/merchants/${m.id}`} className="font-medium text-accent-strong underline-offset-4 hover:underline">
                        {m.displayName}
                      </Link>
                    </Td>
                    <Td className="num text-muted">{m.merchantCode}</Td>
                    <Td>
                      <StatusBadge status={m.status} />
                    </Td>
                    <Td>{m.currency}</Td>
                    <Td className="text-muted">{m.feePlanCode}</Td>
                    <Td className="whitespace-nowrap text-muted">{formatDate(m.createdAt)}</Td>
                  </tr>
                ))}
              </tbody>
            </Table>
            <Pagination page={data.page} totalPages={data.totalPages} totalItems={data.totalItems} onPage={setPage} />
          </>
        )}
      </Card>
      {creating ? <MerchantForm onClose={() => setCreating(false)} onSaved={(m) => navigate(`/merchants/${m.id}`)} /> : null}
    </>
  );
}
