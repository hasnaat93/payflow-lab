import { Link } from "react-router-dom";
import { formatDate } from "../lib/format";
import type { SettlementBatch } from "../lib/types";
import { Money, ROW, StatusBadge, Table, Td, Th } from "./ui";

export default function BatchTable({ items, showMerchant = true }: { items: SettlementBatch[]; showMerchant?: boolean }) {
  return (
    <Table label="Settlement batches">
      <thead>
        <tr>
          <Th>Batch</Th>
          {showMerchant ? <Th>Merchant</Th> : null}
          <Th>Status</Th>
          <Th>Settlement date</Th>
          <Th right>Items</Th>
          <Th right>Gross</Th>
          <Th right>Fees</Th>
          <Th right>Refunds</Th>
          <Th right>Net payout</Th>
        </tr>
      </thead>
      <tbody>
        {items.map((b) => (
          <tr key={b.id} className={ROW}>
            <Td>
              <Link to={`/settlements/${b.id}`} className="num font-medium text-accent-strong underline-offset-4 hover:underline">
                {b.batchReference}
              </Link>
            </Td>
            {showMerchant ? (
              <Td>
                <Link to={`/merchants/${b.merchantId}`} className="hover:underline">
                  {b.merchantName}
                </Link>
              </Td>
            ) : null}
            <Td>
              <StatusBadge status={b.status} />
            </Td>
            <Td className="whitespace-nowrap text-muted">{formatDate(b.settlementDate)}</Td>
            <Td right className="num text-muted">
              {b.transactionCount + b.refundCount}
            </Td>
            <Td right>
              <Money minor={b.grossMinor} />
            </Td>
            <Td right className="text-muted">
              <Money minor={b.feeMinor} />
            </Td>
            <Td right className="text-muted">
              <Money minor={b.refundMinor} />
            </Td>
            <Td right>
              <Money minor={b.netMinor} currency={b.currency} className="font-medium" />
            </Td>
          </tr>
        ))}
      </tbody>
    </Table>
  );
}
