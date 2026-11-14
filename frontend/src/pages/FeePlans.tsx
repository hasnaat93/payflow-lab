import { useState } from "react";
import { Button, Card, CardHeader, EmptyState, ErrorState, Field, LoadingRows, Modal, Money, Notice, PageHeader, ROW, StatusBadge, Table, Td, Th } from "../components/ui";
import { api, errorMessage } from "../lib/api";
import { formatBps, formatMinor, formatMoney, humanize, parseMajorToMinor } from "../lib/format";
import { useApi, useApiMutation } from "../lib/hooks";
import type { FeePlan, FeePreview } from "../lib/types";

export default function FeePlans() {
  const { data, error, isPending, refetch } = useApi<FeePlan[]>("/fee-plans");
  const [editing, setEditing] = useState<FeePlan | "new" | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  return (
    <>
      <PageHeader
        title="Fee plans"
        description="What merchants are charged on each captured payment: a percentage plus a fixed fee, with optional minimum and maximum."
        actions={
          <Button variant="primary" onClick={() => setEditing("new")}>
            New fee plan
          </Button>
        }
      />
      {notice ? (
        <Notice tone="good" onDismiss={() => setNotice(null)}>
          {notice}
        </Notice>
      ) : null}

      <div className="grid gap-5 xl:grid-cols-3">
        <Card className="xl:col-span-2">
          {isPending ? (
            <LoadingRows />
          ) : error ? (
            <ErrorState error={error} onRetry={() => void refetch()} />
          ) : data.length === 0 ? (
            <EmptyState title="No fee plans yet" hint="A merchant needs a fee plan in its own currency." />
          ) : (
            <Table label="Fee plans">
              <thead>
                <tr>
                  <Th>Plan</Th>
                  <Th>Status</Th>
                  <Th>Currency</Th>
                  <Th right>Percentage</Th>
                  <Th right>Fixed</Th>
                  <Th right>Min</Th>
                  <Th right>Max</Th>
                  <Th />
                </tr>
              </thead>
              <tbody>
                {data.map((plan) => (
                  <tr key={plan.id} className={ROW}>
                    <Td>
                      <p className="num font-medium">{plan.code}</p>
                      <p className="text-xs text-muted">{plan.name}</p>
                    </Td>
                    <Td>
                      <StatusBadge status={plan.active ? "ACTIVE" : "INACTIVE"} />
                    </Td>
                    <Td>{plan.currency}</Td>
                    <Td right className="num">
                      {formatBps(plan.percentageBps)}
                    </Td>
                    <Td right>
                      <Money minor={plan.fixedFeeMinor} />
                    </Td>
                    <Td right className="text-muted">
                      {plan.minFeeMinor === null ? "—" : <Money minor={plan.minFeeMinor} />}
                    </Td>
                    <Td right className="text-muted">
                      {plan.maxFeeMinor === null ? "—" : <Money minor={plan.maxFeeMinor} />}
                    </Td>
                    <Td right>
                      <Button size="sm" onClick={() => setEditing(plan)}>
                        Edit
                      </Button>
                    </Td>
                  </tr>
                ))}
              </tbody>
            </Table>
          )}
        </Card>

        <div className="space-y-5">
          <FeeCalculator plans={data ?? []} />
          <Card>
            <CardHeader title="Rounding policy" />
            <ol className="list-decimal space-y-2 px-9 py-5 text-sm leading-relaxed text-muted">
              <li>Percentage fee = amount × rate, rounded half-up to the nearest minor unit.</li>
              <li>The fixed fee is added.</li>
              <li>The plan minimum, then the maximum, is applied.</li>
              <li>The fee is capped at the payment amount, so net is never negative.</li>
            </ol>
            <p className="border-t border-line px-5 py-3 text-xs text-faint">All arithmetic is on integer minor units. Editing a plan affects future captures only.</p>
          </Card>
        </div>
      </div>

      {editing ? (
        <FeePlanForm
          plan={editing === "new" ? undefined : editing}
          onClose={() => setEditing(null)}
          onSaved={(plan) => {
            setEditing(null);
            setNotice(`Fee plan ${plan.code} saved. New terms apply to future captures only.`);
          }}
        />
      ) : null}
    </>
  );
}

function FeeCalculator({ plans }: { plans: FeePlan[] }) {
  const [planId, setPlanId] = useState("");
  const [amount, setAmount] = useState("100.00");
  const plan = plans.find((p) => p.id === planId) ?? plans[0];
  const amountMinor = parseMajorToMinor(amount);
  const preview = useApi<FeePreview>(`/fee-plans/${plan?.id}/preview`, { amountMinor: amountMinor ?? 0 }, { enabled: !!plan && amountMinor !== null });

  return (
    <Card>
      <CardHeader title="Fee calculator" hint="Computed by the API with the same code used at capture" />
      <div className="space-y-4 p-5">
        <Field label="Fee plan">
          {(p) => (
            <select {...p} value={plan?.id ?? ""} onChange={(e) => setPlanId(e.target.value)}>
              {plans.map((option) => (
                <option key={option.id} value={option.id}>
                  {option.code}
                </option>
              ))}
            </select>
          )}
        </Field>
        <Field label={`Payment amount${plan ? ` (${plan.currency})` : ""}`} error={amountMinor === null ? "Enter a positive amount, e.g. 100.00" : null}>
          {(p) => <input {...p} inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} />}
        </Field>
        {preview.data && plan && amountMinor !== null ? (
          <dl className="space-y-2 rounded-xl border border-line bg-bg/40 p-4 text-sm">
            <Line label="Percentage component" value={formatMinor(preview.data.percentageComponentMinor)} />
            <Line label="Fixed component" value={formatMinor(preview.data.fixedComponentMinor)} />
            {preview.data.adjustment !== "NONE" ? <Line label="Adjustment" value={humanize(preview.data.adjustment)} /> : null}
            <div className="border-t border-line pt-2">
              <Line label="Total fee" value={formatMoney(preview.data.totalFeeMinor, plan.currency)} strong />
            </div>
            <Line label="Net to merchant" value={formatMoney(preview.data.netMinor, plan.currency)} strong />
          </dl>
        ) : preview.error ? (
          <p className="text-sm text-bad">{errorMessage(preview.error)}</p>
        ) : null}
      </div>
    </Card>
  );
}

function Line({ label, value, strong = false }: { label: string; value: string; strong?: boolean }) {
  return (
    <div className={`flex justify-between gap-4 ${strong ? "font-semibold" : "text-muted"}`}>
      <dt>{label}</dt>
      <dd className="num">{value}</dd>
    </div>
  );
}

/** Optional money field: "" means "not set", otherwise it must parse. Zero is allowed for fixed fees. */
function parseOptional(input: string, allowZero: boolean): number | null | "invalid" {
  const text = input.trim();
  if (text === "") return null;
  if (allowZero && /^0+(\.0{1,2})?$/.test(text)) return 0;
  return parseMajorToMinor(text) ?? "invalid";
}

function FeePlanForm({ plan, onClose, onSaved }: { plan?: FeePlan; onClose: () => void; onSaved: (plan: FeePlan) => void }) {
  const plain = (minor: number | null | undefined) => (minor === null || minor === undefined ? "" : formatMinor(minor).replace(/,/g, ""));
  const [code, setCode] = useState(plan?.code ?? "");
  const [name, setName] = useState(plan?.name ?? "");
  const [currency, setCurrency] = useState(plan?.currency ?? "USD");
  const [percent, setPercent] = useState(plan ? (plan.percentageBps / 100).toFixed(2) : "2.90");
  const [fixed, setFixed] = useState(plan ? plain(plan.fixedFeeMinor) : "0.30");
  const [min, setMin] = useState(plain(plan?.minFeeMinor));
  const [max, setMax] = useState(plain(plan?.maxFeeMinor));
  const [active, setActive] = useState(plan?.active ?? true);
  const [touched, setTouched] = useState(false);

  const percentMatch = /^(\d{1,3})(?:\.(\d{1,2}))?$/.exec(percent.trim());
  const bps = percentMatch ? Number(percentMatch[1]) * 100 + Number((percentMatch[2] ?? "").padEnd(2, "0") || "0") : null;
  const fixedMinor = parseOptional(fixed, true);
  const minMinor = parseOptional(min, true);
  const maxMinor = parseOptional(max, true);

  const errors = {
    code: /^[A-Z0-9_-]{3,40}$/.test(code) ? null : "3-40 characters: A-Z, 0-9, _ or -",
    name: name.trim() ? null : "Required",
    percent: bps !== null && bps <= 10000 ? null : "Between 0.00 and 100.00, up to 2 decimals",
    fixed: fixedMinor === "invalid" || fixedMinor === null ? "Enter an amount, e.g. 0.30 (0 is allowed)" : null,
    min: minMinor === "invalid" ? "Leave blank or enter an amount" : null,
    max:
      maxMinor === "invalid"
        ? "Leave blank or enter an amount"
        : typeof minMinor === "number" && typeof maxMinor === "number" && minMinor > maxMinor
          ? "Maximum cannot be below the minimum"
          : null,
  };
  const valid = Object.values(errors).every((e) => e === null);

  const save = useApiMutation(() => {
    const terms = { name: name.trim(), percentageBps: bps, fixedFeeMinor: fixedMinor, minFeeMinor: minMinor, maxFeeMinor: maxMinor };
    return plan ? api.put<FeePlan>(`/fee-plans/${plan.id}`, { ...terms, active }) : api.post<FeePlan>("/fee-plans", { ...terms, code, currency });
  }, onSaved);

  return (
    <Modal
      title={plan ? `Edit ${plan.code}` : "New fee plan"}
      description={plan ? "Fees already assessed keep the terms they were calculated with." : "A plan is tied to one currency because fixed, minimum and maximum fees are amounts."}
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
            {plan ? "Save changes" : "Create fee plan"}
          </Button>
        </>
      }
    >
      {save.error ? <Notice tone="bad">{errorMessage(save.error)}</Notice> : null}
      <div className="grid gap-4 sm:grid-cols-2">
        <Field label="Code" error={touched ? errors.code : null}>
          {(p) => <input {...p} value={code} disabled={!!plan} onChange={(e) => setCode(e.target.value.toUpperCase())} />}
        </Field>
        <Field label="Currency">
          {(p) => (
            <select {...p} value={currency} disabled={!!plan} onChange={(e) => setCurrency(e.target.value)}>
              {["USD", "EUR", "PKR"].map((c) => (
                <option key={c}>{c}</option>
              ))}
            </select>
          )}
        </Field>
        <div className="sm:col-span-2">
          <Field label="Name" error={touched ? errors.name : null}>
            {(p) => <input {...p} maxLength={120} value={name} onChange={(e) => setName(e.target.value)} />}
          </Field>
        </div>
        <Field label="Percentage (%)" error={touched ? errors.percent : null} hint={bps !== null ? `${bps} basis points` : undefined}>
          {(p) => <input {...p} inputMode="decimal" value={percent} onChange={(e) => setPercent(e.target.value)} />}
        </Field>
        <Field label={`Fixed fee (${currency})`} error={touched ? errors.fixed : null}>
          {(p) => <input {...p} inputMode="decimal" value={fixed} onChange={(e) => setFixed(e.target.value)} />}
        </Field>
        <Field label={`Minimum fee (${currency})`} error={touched ? errors.min : null} hint="Optional">
          {(p) => <input {...p} inputMode="decimal" value={min} onChange={(e) => setMin(e.target.value)} />}
        </Field>
        <Field label={`Maximum fee (${currency})`} error={touched ? errors.max : null} hint="Optional">
          {(p) => <input {...p} inputMode="decimal" value={max} onChange={(e) => setMax(e.target.value)} />}
        </Field>
        {plan ? (
          <label className="flex items-center gap-2.5 text-sm sm:col-span-2">
            <input type="checkbox" checked={active} onChange={(e) => setActive(e.target.checked)} className="h-4 w-4 accent-[#4f8cff]" />
            Active (inactive plans cannot be assigned to merchants)
          </label>
        ) : null}
      </div>
    </Modal>
  );
}
