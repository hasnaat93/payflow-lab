import { useEffect, useId, useRef, type ButtonHTMLAttributes, type ReactNode } from "react";
import { Link } from "react-router-dom";
import { errorMessage } from "../lib/api";
import { formatMoney, humanize } from "../lib/format";

// ---------------------------------------------------------------- layout bits

export function PageHeader({
  title,
  description,
  actions,
  back,
}: {
  title: ReactNode;
  description?: ReactNode;
  actions?: ReactNode;
  back?: { to: string; label: string };
}) {
  return (
    <header className="mb-6 flex flex-col gap-4 sm:flex-row sm:items-end sm:justify-between">
      <div className="min-w-0">
        {back ? (
          <Link to={back.to} className="mb-2 inline-flex items-center gap-1 text-sm text-muted hover:text-fg">
            <span aria-hidden>←</span> {back.label}
          </Link>
        ) : null}
        <h1 className="truncate text-2xl font-semibold tracking-tight sm:text-[1.7rem]">{title}</h1>
        {description ? <p className="mt-1.5 max-w-2xl text-sm leading-relaxed text-muted">{description}</p> : null}
      </div>
      {actions ? <div className="flex shrink-0 flex-wrap items-center gap-2">{actions}</div> : null}
    </header>
  );
}

export function Card({ children, className = "" }: { children: ReactNode; className?: string }) {
  return <section className={`rounded-2xl border border-line bg-surface ${className}`}>{children}</section>;
}

export function CardHeader({ title, hint, actions }: { title: ReactNode; hint?: ReactNode; actions?: ReactNode }) {
  return (
    <div className="flex flex-wrap items-center justify-between gap-3 border-b border-line px-5 py-4">
      <div>
        <h2 className="text-[0.95rem] font-semibold tracking-tight">{title}</h2>
        {hint ? <p className="mt-0.5 text-xs text-muted">{hint}</p> : null}
      </div>
      {actions}
    </div>
  );
}

export function Stat({
  label,
  value,
  hint,
  tone = "default",
}: {
  label: string;
  value: ReactNode;
  hint?: ReactNode;
  tone?: "default" | "good" | "bad" | "warn";
}) {
  const accent = { default: "bg-accent", good: "bg-good", bad: "bg-bad", warn: "bg-warn" }[tone];
  return (
    <div className="relative overflow-hidden rounded-2xl border border-line bg-surface px-5 py-4">
      <span aria-hidden className={`absolute left-0 top-4 h-8 w-[3px] rounded-r ${accent}`} />
      <p className="text-xs font-medium uppercase tracking-[0.08em] text-muted">{label}</p>
      <p className="num mt-2 truncate text-[1.45rem] font-semibold leading-tight">{value}</p>
      {hint ? <p className="mt-1.5 truncate text-xs text-muted">{hint}</p> : null}
    </div>
  );
}

// -------------------------------------------------------------------- badges

type Tone = "good" | "bad" | "warn" | "serious" | "info" | "violet" | "neutral";

const TONES: Record<Tone, string> = {
  good: "border-good/30 bg-good/10 text-good",
  bad: "border-bad/30 bg-bad/10 text-bad",
  warn: "border-warn/30 bg-warn/10 text-warn",
  serious: "border-serious/30 bg-serious/10 text-serious",
  info: "border-info/30 bg-info/10 text-info",
  violet: "border-violet/30 bg-violet/10 text-violet",
  neutral: "border-line-strong bg-white/[0.04] text-muted",
};

/** One place that decides what every status looks like, so colour meaning is consistent across pages. */
const STATUS_TONE: Record<string, Tone> = {
  AUTHORIZED: "info",
  CAPTURED: "good",
  DECLINED: "bad",
  REVERSED: "neutral",
  PARTIALLY_REFUNDED: "warn",
  REFUNDED: "violet",
  ACTIVE: "good",
  SUSPENDED: "neutral",
  INACTIVE: "neutral",
  CREATED: "info",
  PROCESSING: "info",
  COMPLETED: "good",
  FAILED: "bad",
  MATCHED: "good",
  AMOUNT_MISMATCH: "bad",
  DATE_MISMATCH: "warn",
  UNMATCHED_INTERNAL: "serious",
  UNMATCHED_EXTERNAL: "serious",
  OPEN: "warn",
  REVIEWED: "good",
  NOT_REQUIRED: "neutral",
  DEBIT: "info",
  CREDIT: "violet",
  CAPTURE: "good",
  REFUND: "violet",
};

export function Badge({ tone = "neutral", children }: { tone?: Tone; children: ReactNode }) {
  return (
    <span className={`inline-flex items-center gap-1.5 whitespace-nowrap rounded-full border px-2.5 py-0.5 text-xs font-medium ${TONES[tone]}`}>
      <span aria-hidden className="h-1.5 w-1.5 rounded-full bg-current" />
      {children}
    </span>
  );
}

export function StatusBadge({ status }: { status: string | null | undefined }) {
  if (!status) return <span className="text-faint">—</span>;
  return <Badge tone={STATUS_TONE[status] ?? "neutral"}>{humanize(status)}</Badge>;
}

export function Money({ minor, currency, className = "" }: { minor: number | null | undefined; currency?: string | null; className?: string }) {
  return <span className={`num whitespace-nowrap ${className}`}>{formatMoney(minor, currency)}</span>;
}

// ------------------------------------------------------------------- buttons

type ButtonVariant = "primary" | "secondary" | "danger" | "ghost";

const BUTTON: Record<ButtonVariant, string> = {
  primary: "bg-accent text-white hover:bg-accent-strong disabled:bg-accent/40",
  secondary: "border border-line-strong bg-white/[0.04] text-fg hover:bg-white/[0.09]",
  danger: "border border-bad/40 bg-bad/10 text-bad hover:bg-bad/20",
  ghost: "text-muted hover:bg-white/[0.06] hover:text-fg",
};

export function Button({
  variant = "secondary",
  busy = false,
  size = "md",
  className = "",
  children,
  ...rest
}: ButtonHTMLAttributes<HTMLButtonElement> & { variant?: ButtonVariant; busy?: boolean; size?: "sm" | "md" }) {
  return (
    <button
      type="button"
      {...rest}
      disabled={rest.disabled || busy}
      className={`inline-flex items-center justify-center gap-2 rounded-lg font-medium transition-colors duration-150 disabled:cursor-not-allowed disabled:opacity-60 ${
        size === "sm" ? "px-3 py-1.5 text-xs" : "px-4 py-2 text-sm"
      } ${BUTTON[variant]} ${className}`}
    >
      {busy ? <Spinner /> : null}
      {children}
    </button>
  );
}

export function Spinner() {
  return (
    <span
      role="status"
      aria-label="Loading"
      className="inline-block h-3.5 w-3.5 animate-spin rounded-full border-2 border-current border-r-transparent"
    />
  );
}

// --------------------------------------------------------------------- forms

const CONTROL =
  "w-full rounded-lg border border-line-strong bg-bg/60 px-3 py-2 text-sm text-fg placeholder:text-faint transition-colors hover:border-white/30 focus:border-accent focus:outline-none";

export function Field({
  label,
  hint,
  error,
  children,
}: {
  label: string;
  hint?: ReactNode;
  error?: string | null;
  children: (props: { id: string; className: string; "aria-invalid": boolean; "aria-describedby"?: string }) => ReactNode;
}) {
  const id = useId();
  const describedBy = error ? `${id}-error` : hint ? `${id}-hint` : undefined;
  return (
    <div>
      <label htmlFor={id} className="mb-1.5 block text-xs font-medium text-muted">
        {label}
      </label>
      {children({ id, className: `${CONTROL} ${error ? "border-bad" : ""}`, "aria-invalid": !!error, "aria-describedby": describedBy })}
      {error ? (
        <p id={`${id}-error`} className="mt-1.5 text-xs text-bad">
          {error}
        </p>
      ) : hint ? (
        <p id={`${id}-hint`} className="mt-1.5 text-xs text-faint">
          {hint}
        </p>
      ) : null}
    </div>
  );
}

/** Compact labelled control for filter bars. */
export function Filter({ label, children }: { label: string; children: (props: { id: string; className: string }) => ReactNode }) {
  const id = useId();
  return (
    <div className="min-w-[9rem] flex-1 sm:flex-none">
      <label htmlFor={id} className="mb-1 block text-[0.7rem] font-medium uppercase tracking-[0.08em] text-faint">
        {label}
      </label>
      {children({ id, className: `${CONTROL} py-1.5` })}
    </div>
  );
}

export function FilterBar({ children }: { children: ReactNode }) {
  return <div className="flex flex-wrap items-end gap-3 border-b border-line px-5 py-4">{children}</div>;
}

// -------------------------------------------------------------------- states

export function LoadingRows({ rows = 6 }: { rows?: number }) {
  return (
    <div className="space-y-3 p-5" role="status" aria-label="Loading">
      {Array.from({ length: rows }, (_, i) => (
        <div key={i} className="skeleton h-9" style={{ opacity: 1 - i * 0.12 }} />
      ))}
    </div>
  );
}

export function ErrorState({ error, onRetry }: { error: unknown; onRetry?: () => void }) {
  return (
    <div role="alert" className="m-5 rounded-xl border border-bad/30 bg-bad/[0.07] p-5">
      <p className="text-sm font-semibold text-bad">Could not load this data</p>
      <p className="mt-1 text-sm text-muted">{errorMessage(error)}</p>
      {onRetry ? (
        <Button size="sm" className="mt-4" onClick={onRetry}>
          Try again
        </Button>
      ) : null}
    </div>
  );
}

export function EmptyState({ title, hint, action }: { title: string; hint?: ReactNode; action?: ReactNode }) {
  return (
    <div className="flex flex-col items-center px-6 py-14 text-center">
      <div aria-hidden className="mb-4 grid h-11 w-11 place-items-center rounded-full border border-line-strong text-faint">
        ∅
      </div>
      <p className="text-sm font-semibold">{title}</p>
      {hint ? <p className="mt-1 max-w-sm text-sm text-muted">{hint}</p> : null}
      {action ? <div className="mt-5">{action}</div> : null}
    </div>
  );
}

export function Notice({ tone, children, onDismiss }: { tone: "good" | "bad" | "info"; children: ReactNode; onDismiss?: () => void }) {
  const style = { good: "border-good/30 bg-good/[0.08]", bad: "border-bad/30 bg-bad/[0.08]", info: "border-info/30 bg-info/[0.08]" }[tone];
  return (
    <div role={tone === "bad" ? "alert" : "status"} className={`fade-up mb-5 flex items-start justify-between gap-4 rounded-xl border px-4 py-3 text-sm ${style}`}>
      <div className="min-w-0 leading-relaxed">{children}</div>
      {onDismiss ? (
        <button type="button" onClick={onDismiss} className="shrink-0 rounded text-muted hover:text-fg" aria-label="Dismiss">
          ✕
        </button>
      ) : null}
    </div>
  );
}

// -------------------------------------------------------------------- tables

export function Table({ children, label }: { children: ReactNode; label: string }) {
  return (
    <div className="scroll-thin overflow-x-auto">
      <table className="w-full min-w-[640px] border-collapse text-sm" aria-label={label}>
        {children}
      </table>
    </div>
  );
}

export function Th({ children, right = false }: { children?: ReactNode; right?: boolean }) {
  return (
    <th scope="col" className={`whitespace-nowrap border-b border-line px-5 py-3 text-[0.7rem] font-medium uppercase tracking-[0.08em] text-faint ${right ? "text-right" : "text-left"}`}>
      {children}
    </th>
  );
}

export function Td({ children, right = false, className = "" }: { children?: ReactNode; right?: boolean; className?: string }) {
  return <td className={`border-b border-line/70 px-5 py-3 align-middle ${right ? "text-right" : ""} ${className}`}>{children}</td>;
}

export const ROW = "transition-colors hover:bg-white/[0.03]";

export function Pagination({
  page,
  totalPages,
  totalItems,
  onPage,
}: {
  page: number;
  totalPages: number;
  totalItems: number;
  onPage: (page: number) => void;
}) {
  return (
    <div className="flex items-center justify-between gap-4 px-5 py-3 text-xs text-muted">
      <span>
        {totalItems.toLocaleString("en-US")} result{totalItems === 1 ? "" : "s"}
        {totalPages > 1 ? ` · page ${page + 1} of ${totalPages}` : ""}
      </span>
      <div className="flex gap-2">
        <Button size="sm" disabled={page <= 0} onClick={() => onPage(page - 1)}>
          Previous
        </Button>
        <Button size="sm" disabled={page >= totalPages - 1} onClick={() => onPage(page + 1)}>
          Next
        </Button>
      </div>
    </div>
  );
}

export function KeyValue({ items }: { items: [string, ReactNode][] }) {
  return (
    <dl className="grid grid-cols-1 gap-x-8 gap-y-4 p-5 sm:grid-cols-2">
      {items.map(([label, value]) => (
        <div key={label} className="min-w-0">
          <dt className="text-[0.7rem] font-medium uppercase tracking-[0.08em] text-faint">{label}</dt>
          <dd className="mt-1 break-words text-sm">{value}</dd>
        </div>
      ))}
    </dl>
  );
}

// --------------------------------------------------------------------- modal

export function Modal({
  title,
  description,
  onClose,
  children,
  footer,
  wide = false,
}: {
  title: string;
  description?: ReactNode;
  onClose: () => void;
  children?: ReactNode;
  footer?: ReactNode;
  wide?: boolean;
}) {
  const ref = useRef<HTMLDialogElement>(null);
  const titleId = useId();

  // The native <dialog> gives a focus trap, Escape-to-close and an inert background for free.
  useEffect(() => {
    const dialog = ref.current;
    if (dialog && !dialog.open && typeof dialog.showModal === "function") dialog.showModal();
  }, []);

  return (
    <dialog
      ref={ref}
      aria-labelledby={titleId}
      onCancel={(e) => {
        e.preventDefault();
        onClose();
      }}
      onClick={(e) => {
        if (e.target === ref.current) onClose();
      }}
      className={`fade-up m-auto w-[calc(100%-2rem)] rounded-2xl border border-line-strong bg-surface p-0 text-fg shadow-2xl backdrop:bg-black/60 backdrop:backdrop-blur-sm ${
        wide ? "max-w-2xl" : "max-w-lg"
      }`}
    >
      <div className="border-b border-line px-6 py-5">
        <h2 id={titleId} className="text-lg font-semibold tracking-tight">
          {title}
        </h2>
        {description ? <p className="mt-1 text-sm leading-relaxed text-muted">{description}</p> : null}
      </div>
      {children ? <div className="max-h-[62vh] space-y-4 overflow-y-auto px-6 py-5">{children}</div> : null}
      {footer ? <div className="flex flex-wrap justify-end gap-2 border-t border-line px-6 py-4">{footer}</div> : null}
    </dialog>
  );
}

/** Confirmation for actions that move money or cannot be undone. */
export function ConfirmDialog({
  title,
  description,
  confirmLabel,
  danger = false,
  busy,
  error,
  onConfirm,
  onClose,
  children,
}: {
  title: string;
  description: ReactNode;
  confirmLabel: string;
  danger?: boolean;
  busy: boolean;
  error?: unknown;
  onConfirm: () => void;
  onClose: () => void;
  children?: ReactNode;
}) {
  return (
    <Modal
      title={title}
      description={description}
      onClose={onClose}
      footer={
        <>
          <Button onClick={onClose} disabled={busy}>
            Cancel
          </Button>
          <Button variant={danger ? "danger" : "primary"} busy={busy} onClick={onConfirm}>
            {confirmLabel}
          </Button>
        </>
      }
    >
      {children || error ? (
        <>
          {children}
          {error ? <Notice tone="bad">{errorMessage(error)}</Notice> : null}
        </>
      ) : undefined}
    </Modal>
  );
}

export function TextLink({ to, children }: { to: string; children: ReactNode }) {
  return (
    <Link to={to} className="font-medium text-accent-strong underline-offset-4 hover:underline">
      {children}
    </Link>
  );
}
