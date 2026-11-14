import { useMemo, useState } from "react";
import { api, errorMessage, newIdempotencyKey } from "../lib/api";
import { formatMoney, parseMajorToMinor } from "../lib/format";
import { useApi, useApiMutation } from "../lib/hooks";
import type { Merchant, Page, SimulatorRule, Transaction } from "../lib/types";
import { Button, Field, Modal, Notice } from "./ui";

const BRANDS = ["VISA", "MASTERCARD", "AMEX", "UNIONPAY"];

/**
 * Creates a synthetic authorization. There is intentionally no card-number,
 * expiry or CVV input anywhere: the simulator only takes a brand and four digits.
 */
export default function NewPaymentModal({
  onClose,
  onCreated,
  merchantId,
}: {
  onClose: () => void;
  onCreated: (transaction: Transaction) => void;
  merchantId?: string;
}) {
  const merchants = useApi<Page<Merchant>>("/merchants", { status: "ACTIVE", size: 100, sort: "displayName,asc" });
  const rules = useApi<SimulatorRule[]>("/simulator/rules");

  const [merchant, setMerchant] = useState(merchantId ?? "");
  const [amount, setAmount] = useState("");
  const [brand, setBrand] = useState("VISA");
  const [last4, setLast4] = useState("4242");
  const [scenario, setScenario] = useState("AUTO");
  const [touched, setTouched] = useState(false);
  // One key for the lifetime of this dialog: a double-click or a retry after a
  // timeout replays the same request instead of creating a second payment.
  const idempotencyKey = useMemo(() => newIdempotencyKey(), []);

  const selected = merchants.data?.items.find((m) => m.id === merchant);
  const amountMinor = parseMajorToMinor(amount);
  const errors = {
    merchant: merchant ? null : "Choose a merchant",
    amount: amountMinor === null ? "Enter a positive amount with up to 2 decimals, e.g. 49.99" : null,
    last4: /^\d{4}$/.test(last4) ? null : "Exactly 4 digits",
  };
  const valid = !errors.merchant && !errors.amount && !errors.last4;

  const create = useApiMutation(
    () =>
      api.post<Transaction>(
        "/transactions",
        { merchantId: merchant, amountMinor, currency: selected?.currency, cardBrand: brand, cardLast4: last4, scenario },
        idempotencyKey,
      ),
    onCreated,
  );

  return (
    <Modal
      title="New synthetic payment"
      description="Requests an authorization from the simulator. It approves or declines by fixed rules, never at random."
      onClose={onClose}
      wide
      footer={
        <>
          <Button onClick={onClose} disabled={create.isPending}>
            Cancel
          </Button>
          <Button
            variant="primary"
            busy={create.isPending}
            onClick={() => {
              setTouched(true);
              if (valid) create.mutate(undefined);
            }}
          >
            Request authorization
          </Button>
        </>
      }
    >
      {create.error ? <Notice tone="bad">{errorMessage(create.error)}</Notice> : null}
      {merchants.error ? <Notice tone="bad">{errorMessage(merchants.error)}</Notice> : null}

      <div className="grid gap-4 sm:grid-cols-2">
        <div className="sm:col-span-2">
          <Field label="Merchant" error={touched ? errors.merchant : null} hint="Only active merchants can accept payments">
            {(p) => (
              <select {...p} value={merchant} onChange={(e) => setMerchant(e.target.value)} disabled={!!merchantId}>
                <option value="">{merchants.isPending ? "Loading merchants…" : "Select a merchant"}</option>
                {merchants.data?.items.map((m) => (
                  <option key={m.id} value={m.id}>
                    {m.displayName} ({m.currency})
                  </option>
                ))}
              </select>
            )}
          </Field>
        </div>
        <Field
          label={`Amount${selected ? ` (${selected.currency})` : ""}`}
          error={touched ? errors.amount : null}
          hint={amountMinor !== null && selected ? `Sent as ${amountMinor} minor units = ${formatMoney(amountMinor, selected.currency)}` : "Stored as integer minor units, never as a decimal"}
        >
          {(p) => <input {...p} inputMode="decimal" placeholder="49.99" value={amount} onChange={(e) => setAmount(e.target.value)} />}
        </Field>
        <Field label="Simulator scenario" hint="AUTO applies the rules below">
          {(p) => (
            <select {...p} value={scenario} onChange={(e) => setScenario(e.target.value)}>
              <option value="AUTO">AUTO (use the rules)</option>
              <option value="FORCE_APPROVE">FORCE_APPROVE</option>
              <option value="FORCE_DECLINE">FORCE_DECLINE</option>
            </select>
          )}
        </Field>
        <Field label="Card brand">
          {(p) => (
            <select {...p} value={brand} onChange={(e) => setBrand(e.target.value)}>
              {BRANDS.map((b) => (
                <option key={b}>{b}</option>
              ))}
            </select>
          )}
        </Field>
        <Field label="Card last four (synthetic)" error={touched ? errors.last4 : null} hint="Try 0002, 0051 or 0119 to see a decline">
          {(p) => <input {...p} inputMode="numeric" maxLength={4} value={last4} onChange={(e) => setLast4(e.target.value.replace(/\D/g, ""))} />}
        </Field>
      </div>

      <details className="rounded-xl border border-line bg-bg/40 px-4 py-3 text-sm">
        <summary className="cursor-pointer font-medium">How the simulator decides</summary>
        <ol className="mt-3 space-y-1.5 text-muted">
          {rules.data?.map((rule, i) => (
            <li key={rule.trigger} className="flex gap-2">
              <span className="num text-faint">{i + 1}.</span>
              <span>
                {rule.trigger} → <span className="text-fg">{rule.outcome}</span>
              </span>
            </li>
          ))}
        </ol>
      </details>
    </Modal>
  );
}
