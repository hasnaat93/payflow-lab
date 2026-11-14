import { useState } from "react";
import { Button, Card, CardHeader, ConfirmDialog, Notice, PageHeader, Table, Td, TextLink, Th } from "../components/ui";
import { api } from "../lib/api";
import { useApi, useApiMutation } from "../lib/hooks";
import type { DemoStatus, SimulatorRule } from "../lib/types";

const POSTINGS = [
  ["Authorization / reversal", "No entry", "An authorization moves no money."],
  ["Capture", "Dr Network Receivable (gross) · Cr Merchant Payable (net) · Cr Fee Revenue (fee)", "The network owes the platform; the platform owes the merchant, less its fee."],
  ["Refund", "Dr Merchant Payable · Cr Refund Clearing", "The merchant's balance is reduced. The processing fee is not returned."],
  ["Settlement payout", "Dr Merchant Payable · Cr Settlement Clearing (net)", "The net balance leaves for the merchant's bank."],
  ["Refunds at settlement", "Dr Refund Clearing · Cr Network Receivable", "Refunds are netted against what the network owes."],
];

const WALKTHROUGH: [string, string, string][] = [
  ["Open the overview", "/", "KPIs, daily volume and the reconciliation position, all computed from stored rows."],
  ["Inspect a merchant", "/merchants", "Its fee plan, payments and payouts."],
  ["Create a payment", "/transactions", "Select New payment. Card last four 4242 is approved; 0002 is declined."],
  ["Capture it", "/transactions", "On the payment page, capture the authorization, then read the fee breakdown and the ledger posting."],
  ["Run settlement", "/settlements", "Batches the captured payment into a payout and posts it to the ledger."],
  ["Reconcile", "/reconciliation", "Generate a statement for the period, reconcile it, and review an exception."],
  ["Read the SQL", "/reports", "Every report shows the query that produced it and exports to CSV."],
];

export default function About() {
  const status = useApi<DemoStatus>("/demo/status");
  const rules = useApi<SimulatorRule[]>("/simulator/rules");
  const [confirming, setConfirming] = useState(false);
  const [done, setDone] = useState(false);
  const reset = useApiMutation(
    () => api.post<DemoStatus>("/demo/reset"),
    () => {
      setConfirming(false);
      setDone(true);
    },
  );

  return (
    <>
      <PageHeader title="About PayFlow Lab" description="An educational, production-inspired simulator of card payment operations: authorization, capture, refunds, fees, a double-entry ledger, settlement and reconciliation." />

      <div className="mb-5 rounded-2xl border border-warn/30 bg-warn/[0.06] p-6">
        <h2 className="text-base font-semibold text-warn">This is a simulator, not a payment system</h2>
        <ul className="mt-3 list-disc space-y-1.5 pl-5 text-sm leading-relaxed text-muted">
          <li>It is not connected to any card network, bank or payment processor, and moves no real money.</li>
          <li>Every merchant, reference and amount is fictional. Card details are a brand, four digits and a generated demo token; the system has nowhere to store a card number, expiry, CVV or PIN.</li>
          <li>It is not PCI DSS certified, not security audited, and not suitable for real payments. Never enter real card or customer data.</li>
          <li>There is no login: every action is attributed to a single demo operator.</li>
        </ul>
        {status.data ? <p className="mt-4 text-xs text-faint">{status.data.notice}</p> : null}
      </div>

      {done ? (
        <Notice tone="good" onDismiss={() => setDone(false)}>
          Demo data was reset. Ten days of fictional history have been regenerated.
        </Notice>
      ) : null}

      <div className="grid gap-5 xl:grid-cols-2">
        <Card>
          <CardHeader title="Try the full flow" hint="About five minutes" />
          <ol className="divide-y divide-line">
            {WALKTHROUGH.map(([title, to, text], i) => (
              <li key={title} className="flex gap-4 px-5 py-3.5">
                <span className="num mt-0.5 text-sm text-faint">{i + 1}</span>
                <div>
                  <TextLink to={to}>{title}</TextLink>
                  <p className="mt-0.5 text-sm text-muted">{text}</p>
                </div>
              </li>
            ))}
          </ol>
        </Card>

        <div className="space-y-5">
          <Card>
            <CardHeader title="Authorization rules" hint="Deterministic: the same request always gets the same answer" />
            <ol className="divide-y divide-line">
              {rules.data?.map((rule, i) => (
                <li key={rule.trigger} className="flex gap-4 px-5 py-3 text-sm">
                  <span className="num text-faint">{i + 1}</span>
                  <span className="flex-1">{rule.trigger}</span>
                  <span className="text-right text-muted">{rule.outcome}</span>
                </li>
              ))}
            </ol>
          </Card>

          <Card>
            <CardHeader title="Demo data" hint="Wipes everything and regenerates the fictional dataset" />
            <div className="flex flex-wrap items-center justify-between gap-4 p-5">
              <p className="max-w-sm text-sm text-muted">
                {status.data?.resetEnabled === false
                  ? "Reset is disabled in this environment (DEMO_RESET_ENABLED=false)."
                  : "Seeds merchants, ten days of payments, settlements with one failed batch, and two reconciliation runs."}
              </p>
              <Button variant="danger" disabled={!status.data?.resetEnabled} onClick={() => setConfirming(true)}>
                Reset demo data
              </Button>
            </div>
          </Card>
        </div>
      </div>

      <Card className="mt-5">
        <CardHeader title="How the ledger is posted" hint="A small, internally consistent model for learning; it does not represent every real acquirer's accounting" />
        <Table label="Posting rules">
          <thead>
            <tr>
              <Th>Event</Th>
              <Th>Journal entry</Th>
              <Th>Why</Th>
            </tr>
          </thead>
          <tbody>
            {POSTINGS.map(([event, entry, why]) => (
              <tr key={event}>
                <Td className="whitespace-nowrap font-medium">{event}</Td>
                <Td className="num text-xs">{entry}</Td>
                <Td className="text-muted">{why}</Td>
              </tr>
            ))}
          </tbody>
        </Table>
        <p className="px-5 py-4 text-sm text-muted">
          API documentation is served by the backend at <code className="num text-fg">/swagger-ui.html</code>. Source, architecture notes and the data model are in the repository.
        </p>
      </Card>

      {confirming ? (
        <ConfirmDialog
          title="Reset all demo data?"
          description="Every merchant, payment, ledger entry, settlement batch and reconciliation run is deleted and the fictional dataset is generated again. Anything you created will be lost. This takes a few seconds."
          confirmLabel="Reset demo data"
          danger
          busy={reset.isPending}
          error={reset.error}
          onConfirm={() => reset.mutate(undefined)}
          onClose={() => setConfirming(false)}
        />
      ) : null}
    </>
  );
}
