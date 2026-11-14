// Formatting helpers. Money arrives and leaves as integer minor units; these
// functions only ever convert at the display boundary, using string/integer
// arithmetic so no binary floating point touches an amount.

const MINOR_DIGITS = 2; // every supported currency (USD, EUR, PKR) has two minor digits

/** 123456 -> "1,234.56" (no currency symbol). Exact for any safe integer. */
export function formatMinor(minor: number): string {
  const negative = minor < 0;
  const digits = Math.abs(Math.trunc(minor)).toString().padStart(MINOR_DIGITS + 1, "0");
  const whole = digits.slice(0, -MINOR_DIGITS).replace(/\B(?=(\d{3})+(?!\d))/g, ",");
  return `${negative ? "-" : ""}${whole}.${digits.slice(-MINOR_DIGITS)}`;
}

/** 123456, "USD" -> "USD 1,234.56". The ISO code is spelled out so PKR and USD can never be confused. */
export function formatMoney(minor: number | null | undefined, currency: string | null | undefined): string {
  if (minor === null || minor === undefined) return "—";
  return `${currency ?? ""} ${formatMinor(minor)}`.trim();
}

/** Compact form for chart axes and dense tiles: 1234567 -> "12.3K". */
export function formatMinorCompact(minor: number): string {
  const major = minor / 100; // display-only approximation
  const abs = Math.abs(major);
  if (abs >= 1_000_000) return (major / 1_000_000).toFixed(abs >= 10_000_000 ? 0 : 1) + "M";
  if (abs >= 1_000) return (major / 1_000).toFixed(abs >= 10_000 ? 0 : 1) + "K";
  return major.toFixed(0);
}

/**
 * Parses what a person typed ("12.5", "1,250.00") into minor units, or null if it
 * is not a valid positive amount with at most two decimals. Done on the string,
 * never via parseFloat * 100, which would turn 19.99 into 1998.9999999999998.
 */
export function parseMajorToMinor(input: string): number | null {
  const cleaned = input.trim().replace(/,/g, "");
  const match = /^(\d{1,12})(?:\.(\d{1,2}))?$/.exec(cleaned);
  if (!match) return null;
  const minor = Number(match[1]) * 100 + Number((match[2] ?? "").padEnd(MINOR_DIGITS, "0") || "0");
  return minor > 0 ? minor : null;
}

export function formatBps(bps: number): string {
  return (bps / 100).toFixed(2) + "%";
}

export function formatNumber(value: number): string {
  return value.toLocaleString("en-US");
}

const dateTime = new Intl.DateTimeFormat("en-GB", {
  day: "2-digit",
  month: "short",
  year: "numeric",
  hour: "2-digit",
  minute: "2-digit",
  timeZone: "UTC",
  hour12: false,
});
const dateOnly = new Intl.DateTimeFormat("en-GB", { day: "2-digit", month: "short", year: "numeric", timeZone: "UTC" });
const dayMonth = new Intl.DateTimeFormat("en-GB", { day: "2-digit", month: "short", timeZone: "UTC" });

/** All timestamps are shown in UTC, matching settlement cut-offs. */
export function formatDateTime(iso: string | null | undefined): string {
  return iso ? dateTime.format(new Date(iso)) + " UTC" : "—";
}

export function formatDate(iso: string | null | undefined): string {
  return iso ? dateOnly.format(new Date(iso.length === 10 ? iso + "T00:00:00Z" : iso)) : "—";
}

export function formatDayMonth(isoDate: string): string {
  return dayMonth.format(new Date(isoDate + "T00:00:00Z"));
}

/** "PARTIALLY_REFUNDED" -> "Partially refunded" */
export function humanize(value: string | null | undefined): string {
  if (!value) return "—";
  const text = value.replace(/_/g, " ").toLowerCase();
  return text.charAt(0).toUpperCase() + text.slice(1);
}

export function todayUtc(): string {
  return new Date().toISOString().slice(0, 10);
}

export function daysAgoUtc(days: number): string {
  return new Date(Date.now() - days * 86_400_000).toISOString().slice(0, 10);
}

/** Start of the given UTC date as an ISO instant, for created-at range filters. */
export function startOfDayIso(date: string): string {
  return date + "T00:00:00Z";
}

/** Start of the day after the given UTC date: an exclusive upper bound that includes the whole day. */
export function endOfDayExclusiveIso(date: string): string {
  return new Date(new Date(date + "T00:00:00Z").getTime() + 86_400_000).toISOString();
}
