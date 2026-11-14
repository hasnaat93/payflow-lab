import { Link } from "react-router-dom";
import { formatDateTime } from "../lib/format";
import type { Transaction } from "../lib/types";
import { Money, ROW, StatusBadge, Table, Td, Th } from "./ui";

export default function TransactionTable({ items, showMerchant = true }: { items: Transaction[]; showMerchant?: boolean }) {
  return (
    <Table label="Transactions">
      <thead>
        <tr>
          <Th>Reference</Th>
          {showMerchant ? <Th>Merchant</Th> : null}
          <Th>Status</Th>
          <Th>Card</Th>
          <Th right>Amount</Th>
          <Th right>Fee</Th>
          <Th>Created</Th>
        </tr>
      </thead>
      <tbody>
        {items.map((t) => (
          <tr key={t.id} className={ROW}>
            <Td>
              <Link to={`/transactions/${t.id}`} className="num font-medium text-accent-strong underline-offset-4 hover:underline">
                {t.externalReference}
              </Link>
            </Td>
            {showMerchant ? (
              <Td>
                <Link to={`/merchants/${t.merchantId}`} className="hover:underline">
                  {t.merchantName}
                </Link>
              </Td>
            ) : null}
            <Td>
              <StatusBadge status={t.status} />
            </Td>
            <Td className="whitespace-nowrap text-muted">
              {t.cardBrand} <span className="num">•••• {t.cardLast4}</span>
            </Td>
            <Td right>
              <Money minor={t.amountMinor} currency={t.currency} />
            </Td>
            <Td right className="text-muted">
              {t.feeMinor > 0 ? <Money minor={t.feeMinor} /> : "—"}
            </Td>
            <Td className="whitespace-nowrap text-muted">{formatDateTime(t.createdAt)}</Td>
          </tr>
        ))}
      </tbody>
    </Table>
  );
}
