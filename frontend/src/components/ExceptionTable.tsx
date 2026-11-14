import { useState } from "react";
import { Link } from "react-router-dom";
import { api, errorMessage } from "../lib/api";
import { formatDateTime } from "../lib/format";
import { useApiMutation } from "../lib/hooks";
import type { ReconciliationItem } from "../lib/types";
import { Button, Field, Modal, Money, Notice, ROW, StatusBadge, Table, Td, Th } from "./ui";

/** Reconciliation items with a review action on anything still open. */
export default function ExceptionTable({ items, showReview = true }: { items: ReconciliationItem[]; showReview?: boolean }) {
  const [reviewing, setReviewing] = useState<ReconciliationItem | null>(null);
  return (
    <>
      <Table label="Reconciliation items">
        <thead>
          <tr>
            <Th>Outcome</Th>
            <Th>Batch reference</Th>
            <Th>Merchant</Th>
            <Th right>Expected</Th>
            <Th right>On statement</Th>
            <Th right>Difference</Th>
            <Th>Review</Th>
            {showReview ? <Th /> : null}
          </tr>
        </thead>
        <tbody>
          {items.map((item) => (
            <tr key={item.id} className={ROW}>
              <Td>
                <StatusBadge status={item.outcome} />
              </Td>
              <Td>
                {item.batchId ? (
                  <Link to={`/settlements/${item.batchId}`} className="num text-accent-strong underline-offset-4 hover:underline">
                    {item.batchReference}
                  </Link>
                ) : (
                  <span className="num">{item.batchReference}</span>
                )}
                <p className="mt-1 max-w-xs text-xs text-muted">{item.detail}</p>
                {item.resolutionNote ? (
                  <p className="mt-1 max-w-xs text-xs text-good">
                    Note: {item.resolutionNote} ({formatDateTime(item.reviewedAt)})
                  </p>
                ) : null}
              </Td>
              <Td className="num text-muted">{item.merchantCode ?? "—"}</Td>
              <Td right>
                <Money minor={item.expectedMinor} currency={item.expectedMinor === null ? null : item.currency} />
              </Td>
              <Td right>
                <Money minor={item.actualMinor} currency={item.actualMinor === null ? null : item.currency} />
              </Td>
              <Td right>{item.differenceMinor ? <Money minor={item.differenceMinor} className="text-bad" /> : <span className="text-faint">—</span>}</Td>
              <Td>{item.reviewStatus === "NOT_REQUIRED" ? <span className="text-faint">Not needed</span> : <StatusBadge status={item.reviewStatus} />}</Td>
              {showReview ? (
                <Td right>
                  {item.reviewStatus === "OPEN" ? (
                    <Button size="sm" onClick={() => setReviewing(item)}>
                      Review
                    </Button>
                  ) : null}
                </Td>
              ) : null}
            </tr>
          ))}
        </tbody>
      </Table>
      {reviewing ? <ReviewModal item={reviewing} onClose={() => setReviewing(null)} /> : null}
    </>
  );
}

function ReviewModal({ item, onClose }: { item: ReconciliationItem; onClose: () => void }) {
  const [note, setNote] = useState("");
  const [touched, setTouched] = useState(false);
  const problem = note.trim().length < 5 ? "Write at least 5 characters explaining the resolution" : null;
  const review = useApiMutation(() => api.post<ReconciliationItem>(`/reconciliation/exceptions/${item.id}/review`, { resolutionNote: note.trim() }), onClose);

  return (
    <Modal
      title="Review exception"
      description="Records that someone investigated this exception and why. It does not change the outcome: a mismatch stays a mismatch."
      onClose={onClose}
      footer={
        <>
          <Button onClick={onClose} disabled={review.isPending}>
            Cancel
          </Button>
          <Button
            variant="primary"
            busy={review.isPending}
            onClick={() => {
              setTouched(true);
              if (!problem) review.mutate(undefined);
            }}
          >
            Mark as reviewed
          </Button>
        </>
      }
    >
      {review.error ? <Notice tone="bad">{errorMessage(review.error)}</Notice> : null}
      <div className="rounded-xl border border-line bg-bg/40 p-4 text-sm">
        <div className="mb-2 flex flex-wrap items-center gap-2">
          <StatusBadge status={item.outcome} />
          <span className="num">{item.batchReference}</span>
        </div>
        <p className="text-muted">{item.detail}</p>
      </div>
      <Field label="Resolution note" error={touched ? problem : null} hint={`${note.trim().length}/500`}>
        {(p) => <textarea {...p} rows={4} maxLength={500} placeholder="What did you find, and what happens next?" value={note} onChange={(e) => setNote(e.target.value)} />}
      </Field>
    </Modal>
  );
}
