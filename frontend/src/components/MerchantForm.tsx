import { useState } from "react";
import { api, errorMessage } from "../lib/api";
import { formatBps, formatMoney } from "../lib/format";
import { useApi, useApiMutation } from "../lib/hooks";
import type { FeePlan, Merchant } from "../lib/types";
import { Button, Field, Modal, Notice } from "./ui";

const CURRENCIES = ["USD", "EUR", "PKR"];

/** Create (no `merchant`) or edit an existing merchant. Code and currency are fixed once created. */
export default function MerchantForm({ merchant, onClose, onSaved }: { merchant?: Merchant; onClose: () => void; onSaved: (m: Merchant) => void }) {
  const plans = useApi<FeePlan[]>("/fee-plans");
  const [code, setCode] = useState(merchant?.merchantCode ?? "");
  const [displayName, setDisplayName] = useState(merchant?.displayName ?? "");
  const [legalName, setLegalName] = useState(merchant?.legalName ?? "");
  const [currency, setCurrency] = useState(merchant?.currency ?? "USD");
  const [feePlanId, setFeePlanId] = useState(merchant?.feePlanId ?? "");
  const [touched, setTouched] = useState(false);

  // a fixed fee is an amount, so only plans in the merchant's currency make sense
  const eligible = (plans.data ?? []).filter((p) => p.currency === currency && (p.active || p.id === merchant?.feePlanId));
  const errors = {
    code: /^[A-Z0-9-]{3,32}$/.test(code) ? null : "3-32 characters: A-Z, 0-9 or hyphen",
    displayName: displayName.trim() ? null : "Required",
    feePlanId: eligible.some((p) => p.id === feePlanId) ? null : "Choose a fee plan",
  };
  const valid = !errors.code && !errors.displayName && !errors.feePlanId;

  const save = useApiMutation(
    () =>
      merchant
        ? api.put<Merchant>(`/merchants/${merchant.id}`, { displayName: displayName.trim(), legalName: legalName.trim() || null, feePlanId })
        : api.post<Merchant>("/merchants", { merchantCode: code, displayName: displayName.trim(), legalName: legalName.trim() || null, currency, feePlanId }),
    onSaved,
  );

  return (
    <Modal
      title={merchant ? "Edit merchant" : "New merchant"}
      description={merchant ? "The merchant code and currency cannot change after creation." : "Use a fictional business. This is a simulator, not a place for real company data."}
      onClose={onClose}
      footer={
        <>
          <Button onClick={onClose} disabled={save.isPending}>
            Cancel
          </Button>
          <Button
            variant="primary"
            busy={save.isPending}
            onClick={() => {
              setTouched(true);
              if (valid) save.mutate(undefined);
            }}
          >
            {merchant ? "Save changes" : "Create merchant"}
          </Button>
        </>
      }
    >
      {save.error ? <Notice tone="bad">{errorMessage(save.error)}</Notice> : null}
      <div className="grid gap-4 sm:grid-cols-2">
        <Field label="Merchant code" error={touched ? errors.code : null} hint="Unique, e.g. NORTHWIND-COFFEE">
          {(p) => <input {...p} value={code} disabled={!!merchant} onChange={(e) => setCode(e.target.value.toUpperCase())} />}
        </Field>
        <Field label="Settlement currency">
          {(p) => (
            <select
              {...p}
              value={currency}
              disabled={!!merchant}
              onChange={(e) => {
                setCurrency(e.target.value);
                setFeePlanId("");
              }}
            >
              {CURRENCIES.map((c) => (
                <option key={c}>{c}</option>
              ))}
            </select>
          )}
        </Field>
        <div className="sm:col-span-2">
          <Field label="Display name" error={touched ? errors.displayName : null}>
            {(p) => <input {...p} maxLength={120} value={displayName} onChange={(e) => setDisplayName(e.target.value)} />}
          </Field>
        </div>
        <div className="sm:col-span-2">
          <Field label="Legal name (optional)">{(p) => <input {...p} maxLength={160} value={legalName} onChange={(e) => setLegalName(e.target.value)} />}</Field>
        </div>
        <div className="sm:col-span-2">
          <Field
            label="Fee plan"
            error={touched ? errors.feePlanId : null}
            hint={eligible.length === 0 && !plans.isPending ? `No active ${currency} fee plan exists yet. Create one under Fee plans.` : "Applies to future captures only"}
          >
            {(p) => (
              <select {...p} value={feePlanId} onChange={(e) => setFeePlanId(e.target.value)}>
                <option value="">{plans.isPending ? "Loading…" : "Select a fee plan"}</option>
                {eligible.map((plan) => (
                  <option key={plan.id} value={plan.id}>
                    {plan.code} · {formatBps(plan.percentageBps)} + {formatMoney(plan.fixedFeeMinor, plan.currency)}
                  </option>
                ))}
              </select>
            )}
          </Field>
        </div>
      </div>
    </Modal>
  );
}
