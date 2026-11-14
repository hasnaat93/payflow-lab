// Shapes returned by the PayFlow Lab API (/api/v1). Money is always integer minor units.

export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
}

export type TransactionStatus =
  | "AUTHORIZED"
  | "DECLINED"
  | "CAPTURED"
  | "PARTIALLY_REFUNDED"
  | "REFUNDED"
  | "REVERSED";

export interface FeePlan {
  id: string;
  code: string;
  name: string;
  currency: string;
  percentageBps: number;
  fixedFeeMinor: number;
  minFeeMinor: number | null;
  maxFeeMinor: number | null;
  active: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface FeePreview {
  grossMinor: number;
  percentageComponentMinor: number;
  fixedComponentMinor: number;
  adjustment: string;
  totalFeeMinor: number;
  netMinor: number;
}

export interface Merchant {
  id: string;
  merchantCode: string;
  displayName: string;
  legalName: string | null;
  currency: string;
  status: "ACTIVE" | "SUSPENDED";
  feePlanId: string;
  feePlanCode: string;
  feePlanName: string;
  createdAt: string;
  updatedAt: string;
}

export interface Transaction {
  id: string;
  externalReference: string;
  merchantId: string;
  merchantCode: string;
  merchantName: string;
  amountMinor: number;
  currency: string;
  status: TransactionStatus;
  cardBrand: string;
  cardLast4: string;
  declineReason: string | null;
  capturedAmountMinor: number;
  refundedAmountMinor: number;
  refundableMinor: number;
  feeMinor: number;
  netMinor: number;
  capturedAt: string | null;
  settlementBatchId: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface TransactionEvent {
  id: string;
  eventType: string;
  fromStatus: string | null;
  toStatus: string;
  amountMinor: number | null;
  detail: string | null;
  createdAt: string;
}

export interface FeeAssessment {
  planCode: string;
  currency: string;
  grossMinor: number;
  percentageBps: number;
  fixedFeeMinor: number;
  minFeeMinor: number | null;
  maxFeeMinor: number | null;
  percentageComponentMinor: number;
  fixedComponentMinor: number;
  adjustment: string;
  totalFeeMinor: number;
  netMinor: number;
  createdAt: string;
}

export interface Refund {
  id: string;
  refundReference: string;
  amountMinor: number;
  currency: string;
  reason: string | null;
  settlementBatchId: string | null;
  createdAt: string;
}

export interface JournalLine {
  lineNo: number;
  accountCode: string;
  accountName: string;
  side: "DEBIT" | "CREDIT";
  amountMinor: number;
}

export interface JournalEntry {
  id: string;
  entryNumber: number;
  eventType: string;
  sourceType: string;
  sourceId: string;
  merchantId: string | null;
  currency: string;
  description: string;
  postedAt: string;
  totalMinor: number;
  lines: JournalLine[];
}

export interface TransactionDetail {
  transaction: Transaction;
  events: TransactionEvent[];
  feeAssessment: FeeAssessment | null;
  refunds: Refund[];
  journalEntries: JournalEntry[];
}

export interface SimulatorRule {
  trigger: string;
  outcome: string;
}

export interface AccountBalance {
  accountId: string;
  code: string;
  name: string;
  type: string;
  normalSide: string;
  description: string;
  currency: string | null;
  debitMinor: number;
  creditMinor: number;
  balanceMinor: number;
}

export interface SettlementBatch {
  id: string;
  batchReference: string;
  merchantId: string;
  merchantCode: string;
  merchantName: string;
  currency: string;
  settlementDate: string;
  status: "CREATED" | "PROCESSING" | "COMPLETED" | "FAILED";
  transactionCount: number;
  refundCount: number;
  grossMinor: number;
  feeMinor: number;
  refundMinor: number;
  netMinor: number;
  summary: string | null;
  failureReason: string | null;
  startedAt: string | null;
  completedAt: string | null;
  createdAt: string;
}

export interface SettlementRun {
  settlementDate: string;
  merchantsConsidered: number;
  completed: number;
  failed: number;
  transactionsSettled: number;
  summary: string;
  batches: SettlementBatch[];
}

export interface BatchLine {
  id: string;
  lineType: "CAPTURE" | "REFUND";
  transactionId: string;
  externalReference: string;
  refundReference: string | null;
  grossMinor: number;
  feeMinor: number;
  netMinor: number;
}

export interface SettlementBatchDetail {
  batch: SettlementBatch;
  lines: BatchLine[];
  journalEntries: JournalEntry[];
}

export interface Statement {
  id: string;
  statementReference: string;
  source: string;
  periodStart: string;
  periodEnd: string;
  lineCount: number;
  createdAt: string;
}

export interface ReconciliationRun {
  id: string;
  statementId: string;
  statementReference: string;
  periodStart: string;
  periodEnd: string;
  status: string;
  totalItems: number;
  matchedCount: number;
  amountMismatchCount: number;
  dateMismatchCount: number;
  unmatchedInternalCount: number;
  unmatchedExternalCount: number;
  exceptionCount: number;
  matchRatePercent: number;
  startedAt: string;
  completedAt: string | null;
}

export interface ReconciliationItem {
  id: string;
  runId: string;
  outcome: string;
  batchReference: string;
  merchantCode: string | null;
  currency: string | null;
  batchId: string | null;
  expectedMinor: number | null;
  actualMinor: number | null;
  differenceMinor: number | null;
  detail: string;
  reviewStatus: "NOT_REQUIRED" | "OPEN" | "REVIEWED";
  resolutionNote: string | null;
  reviewedAt: string | null;
}

export interface ReconciliationRunDetail {
  run: ReconciliationRun;
  items: ReconciliationItem[];
}

export interface AuditEvent {
  id: string;
  occurredAt: string;
  entityType: string;
  entityId: string;
  action: string;
  actor: string;
  metadata: string | null;
}

export interface DashboardSummary {
  currency: string;
  from: string;
  to: string;
  status: string | null;
  currencies: string[];
  kpis: {
    totalTransactions: number;
    approvedCount: number;
    declinedCount: number;
    reversedCount: number;
    refundedCount: number;
    capturedCount: number;
    grossMinor: number;
    capturedMinor: number;
    refundMinor: number;
    feeMinor: number;
    netSettlementMinor: number;
    unsettledMinor: number;
    approvalRatePercent: number;
  };
  settlementBatches: Record<string, number>;
  reconciliation: {
    latestMatchRatePercent: number | null;
    latestRunAt: string | null;
    latestStatement: string | null;
    openExceptions: number;
    reviewedExceptions: number;
  };
  series: {
    date: string;
    transactionCount: number;
    approvedCount: number;
    declinedCount: number;
    grossMinor: number;
    capturedMinor: number;
    feeMinor: number;
  }[];
}

export interface ReportDefinition {
  id: string;
  title: string;
  description: string;
}

export interface ReportColumn {
  key: string;
  label: string;
  type: "money" | "percent" | "number" | "date" | "text";
}

export interface ReportResult extends ReportDefinition {
  currency: string;
  from: string;
  to: string;
  columns: ReportColumn[];
  rows: Record<string, string | number | null>[];
  sql: string;
}

export interface DemoStatus {
  simulator: boolean;
  resetEnabled: boolean;
  notice: string;
}
