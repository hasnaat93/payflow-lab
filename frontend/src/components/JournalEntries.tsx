import type { JournalEntry } from "../lib/types";
import { formatDateTime, humanize } from "../lib/format";
import { Badge, Money } from "./ui";

/** Renders journal entries as classic two-column debit/credit postings. */
export default function JournalEntries({ entries }: { entries: JournalEntry[] }) {
  if (entries.length === 0) {
    return <p className="px-5 py-6 text-sm text-muted">No ledger postings. Authorizations and reversals move no money, so they post nothing.</p>;
  }
  return (
    <div className="divide-y divide-line">
      {entries.map((entry) => {
        const debits = entry.lines.filter((l) => l.side === "DEBIT").reduce((sum, l) => sum + l.amountMinor, 0);
        const credits = entry.lines.filter((l) => l.side === "CREDIT").reduce((sum, l) => sum + l.amountMinor, 0);
        return (
          <article key={entry.id} className="px-5 py-4">
            <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
              <div className="flex flex-wrap items-center gap-2">
                <span className="num text-xs text-faint">JE-{entry.entryNumber}</span>
                <span className="text-sm font-medium">{humanize(entry.eventType)}</span>
                <span className="text-sm text-muted">{entry.description}</span>
              </div>
              <span className="text-xs text-faint">{formatDateTime(entry.postedAt)}</span>
            </div>
            <div className="scroll-thin overflow-x-auto rounded-xl border border-line">
              <table className="w-full min-w-[420px] text-sm" aria-label={`Journal entry ${entry.entryNumber}`}>
                <thead>
                  <tr className="text-[0.7rem] uppercase tracking-[0.08em] text-faint">
                    <th scope="col" className="px-4 py-2 text-left font-medium">Account</th>
                    <th scope="col" className="px-4 py-2 text-right font-medium">Debit</th>
                    <th scope="col" className="px-4 py-2 text-right font-medium">Credit</th>
                  </tr>
                </thead>
                <tbody>
                  {entry.lines.map((line) => (
                    <tr key={line.lineNo} className="border-t border-line/70">
                      <td className={`px-4 py-2 ${line.side === "CREDIT" ? "pl-9" : ""}`}>
                        <span className="num mr-2 text-xs text-faint">{line.accountCode}</span>
                        {line.accountName}
                      </td>
                      <td className="px-4 py-2 text-right">{line.side === "DEBIT" ? <Money minor={line.amountMinor} /> : null}</td>
                      <td className="px-4 py-2 text-right">{line.side === "CREDIT" ? <Money minor={line.amountMinor} /> : null}</td>
                    </tr>
                  ))}
                  <tr className="border-t border-line-strong bg-white/[0.02] font-medium">
                    <td className="px-4 py-2">
                      <span className="mr-2">{entry.currency} total</span>
                      {debits === credits ? <Badge tone="good">Balanced</Badge> : <Badge tone="bad">Unbalanced</Badge>}
                    </td>
                    <td className="px-4 py-2 text-right"><Money minor={debits} /></td>
                    <td className="px-4 py-2 text-right"><Money minor={credits} /></td>
                  </tr>
                </tbody>
              </table>
            </div>
          </article>
        );
      })}
    </div>
  );
}
