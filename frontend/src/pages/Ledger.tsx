import { Fragment, useState } from "react";
import JournalEntries from "../components/JournalEntries";
import { Badge, Button, Card, CardHeader, EmptyState, ErrorState, Filter, FilterBar, LoadingRows, Money, PageHeader, Pagination, ROW, Table, Td, Th } from "../components/ui";
import { formatDateTime, humanize } from "../lib/format";
import { useApi } from "../lib/hooks";
import type { AccountBalance, JournalEntry, Page } from "../lib/types";

const EVENTS = ["CAPTURE", "REFUND", "SETTLEMENT_PAYOUT", "REFUND_SETTLEMENT"];

export default function Ledger() {
  const accounts = useApi<AccountBalance[]>("/ledger/accounts");
  const [accountCode, setAccountCode] = useState("");
  const [eventType, setEventType] = useState("");
  const [currency, setCurrency] = useState("");
  const [page, setPage] = useState(0);
  const [open, setOpen] = useState<string | null>(null);
  const entries = useApi<Page<JournalEntry>>("/ledger/journal-entries", { accountCode, eventType, currency, page, size: 20 });

  const balances = accounts.data ?? [];
  const currencies = [...new Set(balances.map((b) => b.currency).filter((c): c is string => !!c))].sort();
  const chart = [...new Map(balances.map((b) => [b.code, b])).values()];
  const change = (setter: (value: string) => void) => (value: string) => {
    setter(value);
    setPage(0);
    setOpen(null);
  };

  return (
    <>
      <PageHeader
        title="Ledger"
        description="An append-only, double-entry record of every money movement. Balances are always derived from journal lines; nothing stores a running total."
      />

      <Card className="mb-5">
        <CardHeader title="Chart of accounts" hint="Balance shown on each account's normal side, per currency" />
        {accounts.isPending ? (
          <LoadingRows rows={5} />
        ) : accounts.error ? (
          <ErrorState error={accounts.error} onRetry={() => void accounts.refetch()} />
        ) : (
          <Table label="Account balances">
            <thead>
              <tr>
                <Th>Account</Th>
                <Th>Type</Th>
                {currencies.length === 0 ? <Th right>Balance</Th> : currencies.map((c) => <Th key={c} right>{c} balance</Th>)}
              </tr>
            </thead>
            <tbody>
              {chart.map((account) => (
                <tr key={account.code} className={ROW}>
                  <Td>
                    <p>
                      <span className="num mr-2 text-xs text-faint">{account.code}</span>
                      <span className="font-medium">{account.name}</span>
                    </p>
                    <p className="mt-0.5 max-w-xl text-xs text-muted">{account.description}</p>
                  </Td>
                  <Td className="whitespace-nowrap text-muted">
                    {humanize(account.type)} · normal {account.normalSide.toLowerCase()}
                  </Td>
                  {currencies.length === 0 ? (
                    <Td right className="text-muted">—</Td>
                  ) : (
                    currencies.map((c) => {
                      const cell = balances.find((b) => b.code === account.code && b.currency === c);
                      return (
                        <Td key={c} right>
                          {cell ? <Money minor={cell.balanceMinor} /> : <span className="text-faint">0.00</span>}
                        </Td>
                      );
                    })
                  )}
                </tr>
              ))}
            </tbody>
          </Table>
        )}
      </Card>

      <Card>
        <CardHeader title="Journal entries" hint="Select an entry to see its debit and credit lines" />
        <FilterBar>
          <Filter label="Account">
            {(p) => (
              <select {...p} value={accountCode} onChange={(e) => change(setAccountCode)(e.target.value)}>
                <option value="">All accounts</option>
                {chart.map((a) => (
                  <option key={a.code} value={a.code}>
                    {a.code} {a.name}
                  </option>
                ))}
              </select>
            )}
          </Filter>
          <Filter label="Event">
            {(p) => (
              <select {...p} value={eventType} onChange={(e) => change(setEventType)(e.target.value)}>
                <option value="">All events</option>
                {EVENTS.map((e) => (
                  <option key={e} value={e}>
                    {humanize(e)}
                  </option>
                ))}
              </select>
            )}
          </Filter>
          <Filter label="Currency">
            {(p) => (
              <select {...p} value={currency} onChange={(e) => change(setCurrency)(e.target.value)}>
                <option value="">All</option>
                {currencies.map((c) => (
                  <option key={c}>{c}</option>
                ))}
              </select>
            )}
          </Filter>
        </FilterBar>

        {entries.isPending ? (
          <LoadingRows />
        ) : entries.error ? (
          <ErrorState error={entries.error} onRetry={() => void entries.refetch()} />
        ) : entries.data.items.length === 0 ? (
          <EmptyState title="No journal entries" hint="Entries are posted when a payment is captured, refunded or settled." />
        ) : (
          <>
            <Table label="Journal entries">
              <thead>
                <tr>
                  <Th>Entry</Th>
                  <Th>Event</Th>
                  <Th>Description</Th>
                  <Th>Posted</Th>
                  <Th right>Amount</Th>
                  <Th />
                </tr>
              </thead>
              <tbody>
                {entries.data.items.map((entry) => (
                  <Fragment key={entry.id}>
                    <tr className={ROW}>
                      <Td className="num text-muted">JE-{entry.entryNumber}</Td>
                      <Td>
                        <Badge tone="info">{humanize(entry.eventType)}</Badge>
                      </Td>
                      <Td>{entry.description}</Td>
                      <Td className="whitespace-nowrap text-muted">{formatDateTime(entry.postedAt)}</Td>
                      <Td right>
                        <Money minor={entry.totalMinor} currency={entry.currency} />
                      </Td>
                      <Td right>
                        <Button size="sm" aria-expanded={open === entry.id} onClick={() => setOpen(open === entry.id ? null : entry.id)}>
                          {open === entry.id ? "Hide lines" : "View lines"}
                        </Button>
                      </Td>
                    </tr>
                    {open === entry.id ? (
                      <tr>
                        <td colSpan={6} className="border-b border-line bg-bg/40">
                          <JournalEntries entries={[entry]} />
                        </td>
                      </tr>
                    ) : null}
                  </Fragment>
                ))}
              </tbody>
            </Table>
            <Pagination page={entries.data.page} totalPages={entries.data.totalPages} totalItems={entries.data.totalItems} onPage={setPage} />
          </>
        )}
      </Card>
    </>
  );
}
