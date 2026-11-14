import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import NewPaymentModal from "../components/NewPaymentModal";
import { StatusBadge } from "../components/ui";
import TransactionDetail from "../pages/TransactionDetail";
import Transactions from "../pages/Transactions";
import type { Transaction } from "../lib/types";

const MERCHANT = { id: "m-1", merchantCode: "NORTHWIND", displayName: "Northwind Coffee", legalName: null, currency: "USD", status: "ACTIVE", feePlanId: "p-1", feePlanCode: "STANDARD-USD", feePlanName: "Standard", createdAt: "2026-01-01T00:00:00Z", updatedAt: "2026-01-01T00:00:00Z" };

function txn(overrides: Partial<Transaction> = {}): Transaction {
  return {
    id: "t-1", externalReference: "PAY-TEST-0001", merchantId: "m-1", merchantCode: "NORTHWIND", merchantName: "Northwind Coffee",
    amountMinor: 10000, currency: "USD", status: "AUTHORIZED", cardBrand: "VISA", cardLast4: "4242", declineReason: null,
    capturedAmountMinor: 0, refundedAmountMinor: 0, refundableMinor: 0, feeMinor: 0, netMinor: 0, capturedAt: null,
    settlementBatchId: null, createdAt: "2026-03-01T10:00:00Z", updatedAt: "2026-03-01T10:00:00Z", ...overrides,
  };
}

type Handler = (url: string, init: RequestInit) => { status?: number; body: unknown };

/** Replaces fetch with a tiny router and records every call so tests can assert on requests. */
function mockApi(handler: Handler) {
  const calls: { url: string; init: RequestInit }[] = [];
  vi.stubGlobal("fetch", async (url: string, init: RequestInit = {}) => {
    calls.push({ url, init });
    const { status = 200, body } = handler(url, init);
    return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
  });
  return calls;
}

function renderAt(path: string, routes: React.ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>{routes}</Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

const page = (items: unknown[]) => ({ items, page: 0, size: 20, totalItems: items.length, totalPages: 1 });

beforeEach(() => {
  // jsdom does not implement the dialog element's modal methods
  HTMLDialogElement.prototype.showModal = function () {
    this.setAttribute("open", "");
  };
  HTMLDialogElement.prototype.close = function () {
    this.removeAttribute("open");
  };
});
afterEach(() => vi.unstubAllGlobals());

describe("StatusBadge", () => {
  it("always shows the status as text, not colour alone", () => {
    render(<StatusBadge status="PARTIALLY_REFUNDED" />);
    expect(screen.getByText("Partially refunded")).toBeInTheDocument();
  });
});

describe("Transactions page", () => {
  it("lists transactions from the API", async () => {
    mockApi((url) => (url.includes("/merchants") ? { body: page([MERCHANT]) } : { body: page([txn(), txn({ id: "t-2", externalReference: "PAY-TEST-0002", status: "DECLINED" })]) }));
    renderAt("/transactions", <Route path="/transactions" element={<Transactions />} />);

    expect(await screen.findByText("PAY-TEST-0001")).toBeInTheDocument();
    expect(screen.getByText("PAY-TEST-0002")).toBeInTheDocument();
    expect(screen.getByText("Declined")).toBeInTheDocument();
    expect(screen.getByText("2 results")).toBeInTheDocument();
  });

  it("shows an empty state with a way forward when there is no data", async () => {
    mockApi((url) => ({ body: page(url.includes("/merchants") ? [MERCHANT] : []) }));
    renderAt("/transactions", <Route path="/transactions" element={<Transactions />} />);

    expect(await screen.findByText("No transactions yet")).toBeInTheDocument();
  });

  it("shows the API's own message when loading fails", async () => {
    mockApi((url) =>
      url.includes("/merchants") ? { body: page([]) } : { status: 500, body: { errorCode: "INTERNAL_ERROR", message: "An unexpected error occurred. It has been logged." } },
    );
    renderAt("/transactions", <Route path="/transactions" element={<Transactions />} />);

    expect(await screen.findByRole("alert")).toHaveTextContent("An unexpected error occurred");
    expect(screen.getByRole("button", { name: "Try again" })).toBeInTheDocument();
  });
});

describe("New payment", () => {
  const handler: Handler = (url, init) => {
    if (url.includes("/merchants")) return { body: page([MERCHANT]) };
    if (url.includes("/simulator/rules")) return { body: [{ trigger: "Card last four 0002", outcome: "Declined: INSUFFICIENT_FUNDS" }] };
    if (init.method === "POST") return { status: 201, body: txn() };
    return { body: {} };
  };

  it("validates before sending anything", async () => {
    const calls = mockApi(handler);
    renderAt("/", <Route path="/" element={<NewPaymentModal onClose={() => {}} onCreated={() => {}} />} />);
    await screen.findByRole("option", { name: /Northwind Coffee/ });

    await userEvent.click(screen.getByRole("button", { name: "Request authorization" }));

    expect(screen.getByText("Choose a merchant")).toBeInTheDocument();
    expect(screen.getByText(/Enter a positive amount/)).toBeInTheDocument();
    expect(calls.some((c) => c.init.method === "POST")).toBe(false);
  });

  it("sends integer minor units and an idempotency key, and no card number", async () => {
    const calls = mockApi(handler);
    const onCreated = vi.fn();
    renderAt("/", <Route path="/" element={<NewPaymentModal onClose={() => {}} onCreated={onCreated} />} />);
    await screen.findByRole("option", { name: /Northwind Coffee/ });

    await userEvent.selectOptions(screen.getByLabelText("Merchant"), "m-1");
    await userEvent.type(screen.getByLabelText(/^Amount/), "19.99");
    await userEvent.click(screen.getByRole("button", { name: "Request authorization" }));

    await waitFor(() => expect(onCreated).toHaveBeenCalled());
    const post = calls.find((c) => c.init.method === "POST")!;
    const body = JSON.parse(post.init.body as string);
    expect(body).toEqual({ merchantId: "m-1", amountMinor: 1999, currency: "USD", cardBrand: "VISA", cardLast4: "4242", scenario: "AUTO" });
    expect((post.init.headers as Record<string, string>)["Idempotency-Key"]).toMatch(/^ui-/);
    expect(screen.queryByLabelText(/card number|cvv|expiry/i)).not.toBeInTheDocument();
  });
});

describe("Transaction detail", () => {
  const detail = (t: Transaction) => ({ transaction: t, events: [{ id: "e-1", eventType: "AUTHORIZATION_APPROVED", fromStatus: null, toStatus: "AUTHORIZED", amountMinor: 10000, detail: "Approved by simulator", createdAt: t.createdAt }], feeAssessment: null, refunds: [], journalEntries: [] });

  it("offers capture and reverse for an authorized payment, and asks for confirmation", async () => {
    const calls = mockApi((_url, init) => (init.method === "POST" ? { body: txn({ status: "CAPTURED" }) } : { body: detail(txn()) }));
    renderAt("/transactions/t-1", <Route path="/transactions/:id" element={<TransactionDetail />} />);

    await userEvent.click(await screen.findByRole("button", { name: "Capture" }));
    const dialog = screen.getByRole("dialog");
    expect(within(dialog).getByText("Capture this payment?")).toBeInTheDocument();
    expect(calls.some((c) => c.init.method === "POST")).toBe(false); // nothing happens until confirmed

    await userEvent.click(within(dialog).getByRole("button", { name: "Capture" }));
    await waitFor(() => expect(calls.some((c) => c.init.method === "POST" && c.url.endsWith("/transactions/t-1/capture"))).toBe(true));
  });

  it("offers no money-moving actions on a declined payment", async () => {
    mockApi(() => ({ body: detail(txn({ status: "DECLINED", declineReason: "INSUFFICIENT_FUNDS" })) }));
    renderAt("/transactions/t-1", <Route path="/transactions/:id" element={<TransactionDetail />} />);

    expect(await screen.findByText(/Declined by the simulator/)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Capture" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Refund" })).not.toBeInTheDocument();
  });

  it("blocks a refund larger than the refundable amount before calling the API", async () => {
    const captured = txn({ status: "CAPTURED", capturedAmountMinor: 10000, refundableMinor: 10000, feeMinor: 320, netMinor: 9680 });
    const calls = mockApi(() => ({ body: detail(captured) }));
    renderAt("/transactions/t-1", <Route path="/transactions/:id" element={<TransactionDetail />} />);

    await userEvent.click(await screen.findByRole("button", { name: "Refund" }));
    const dialog = screen.getByRole("dialog");
    await userEvent.type(within(dialog).getByLabelText(/^Amount/), "100.01");
    await userEvent.click(within(dialog).getByRole("button", { name: /^Refund/ }));

    expect(within(dialog).getByText(/At most USD 100.00 can still be refunded/)).toBeInTheDocument();
    expect(calls.some((c) => c.init.method === "POST")).toBe(false);
  });
});
