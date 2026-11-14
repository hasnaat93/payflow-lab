import { lazy, Suspense } from "react";
import { Route, Routes } from "react-router-dom";
import Layout from "../components/Layout";
import { EmptyState, LoadingRows, TextLink } from "../components/ui";
import About from "../pages/About";
import Audit from "../pages/Audit";
import FeePlans from "../pages/FeePlans";
import Ledger from "../pages/Ledger";
import MerchantDetail from "../pages/MerchantDetail";
import Merchants from "../pages/Merchants";
import Reconciliation from "../pages/Reconciliation";
import ReconciliationRunPage from "../pages/ReconciliationRun";
import Reports from "../pages/Reports";
import SettlementDetail from "../pages/SettlementDetail";
import Settlements from "../pages/Settlements";
import TransactionDetail from "../pages/TransactionDetail";
import Transactions from "../pages/Transactions";

// the overview pulls in the charting library, so it is split into its own chunk
const Overview = lazy(() => import("../pages/Overview"));

export default function App() {
  return (
    <Routes>
      <Route element={<Layout />}>
        <Route
          index
          element={
            <Suspense fallback={<LoadingRows rows={8} />}>
              <Overview />
            </Suspense>
          }
        />
        <Route path="transactions" element={<Transactions />} />
        <Route path="transactions/:id" element={<TransactionDetail />} />
        <Route path="merchants" element={<Merchants />} />
        <Route path="merchants/:id" element={<MerchantDetail />} />
        <Route path="fee-plans" element={<FeePlans />} />
        <Route path="settlements" element={<Settlements />} />
        <Route path="settlements/:id" element={<SettlementDetail />} />
        <Route path="ledger" element={<Ledger />} />
        <Route path="reconciliation" element={<Reconciliation />} />
        <Route path="reconciliation/runs/:id" element={<ReconciliationRunPage />} />
        <Route path="reports" element={<Reports />} />
        <Route path="reports/:id" element={<Reports />} />
        <Route path="audit" element={<Audit />} />
        <Route path="about" element={<About />} />
        <Route
          path="*"
          element={<EmptyState title="Page not found" hint="That address does not match any screen." action={<TextLink to="/">Back to the overview</TextLink>} />}
        />
      </Route>
    </Routes>
  );
}
