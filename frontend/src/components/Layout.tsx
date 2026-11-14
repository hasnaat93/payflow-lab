import { useEffect, useState } from "react";
import { NavLink, Outlet, useLocation } from "react-router-dom";

const NAV: { to: string; label: string; icon: string; end?: boolean }[] = [
  { to: "/", label: "Overview", icon: "M4 13h6V4H4v9Zm0 7h6v-5H4v5Zm10 0h6v-9h-6v9Zm0-16v5h6V4h-6Z", end: true },
  { to: "/transactions", label: "Transactions", icon: "M4 8h13l-3-3M20 16H7l3 3" },
  { to: "/merchants", label: "Merchants", icon: "M4 10l1.5-5h13L20 10M5 10v9h14v-9M4 10h16M10 19v-5h4v5" },
  { to: "/fee-plans", label: "Fee plans", icon: "M7 17 17 7M8 9a1.5 1.5 0 1 0 0-3 1.5 1.5 0 0 0 0 3Zm8 9a1.5 1.5 0 1 0 0-3 1.5 1.5 0 0 0 0 3Z" },
  { to: "/settlements", label: "Settlements", icon: "M4 7h16v10H4V7Zm0 3h16M8 14h3" },
  { to: "/ledger", label: "Ledger", icon: "M6 4h12v16H6V4Zm3 4h6M9 12h6M9 16h3" },
  { to: "/reconciliation", label: "Reconciliation", icon: "m5 12 4 4 10-10M4 20h16" },
  { to: "/reports", label: "Reports", icon: "M5 20V10M10 20V4M15 20v-7M20 20v-12" },
  { to: "/audit", label: "Audit trail", icon: "M12 7v5l3 2M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18Z" },
  { to: "/about", label: "About & simulator", icon: "M12 11v6M12 7.5v.5M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18Z" },
];

function Icon({ path }: { path: string }) {
  return (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
      <path d={path} />
    </svg>
  );
}

function Brand() {
  return (
    <div className="flex items-center gap-2.5">
      <span className="grid h-8 w-8 place-items-center rounded-lg bg-gradient-to-br from-accent to-cyan text-bg">
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
          <path d="M5 8h10a4 4 0 0 1 0 8H8M11 13l-3 3 3 3" />
        </svg>
      </span>
      <span className="text-[1.05rem] font-semibold tracking-tight">
        PayFlow <span className="text-muted">Lab</span>
      </span>
    </div>
  );
}

function Navigation({ onNavigate }: { onNavigate?: () => void }) {
  return (
    <nav aria-label="Main" className="flex flex-col gap-0.5">
      {NAV.map((item) => (
        <NavLink
          key={item.to}
          to={item.to}
          end={item.end}
          onClick={onNavigate}
          className={({ isActive }) =>
            `flex items-center gap-3 rounded-lg px-3 py-2 text-sm transition-colors duration-150 ${
              isActive ? "bg-accent/15 font-medium text-fg" : "text-muted hover:bg-white/[0.05] hover:text-fg"
            }`
          }
        >
          {({ isActive }) => (
            <>
              <span className={isActive ? "text-accent-strong" : ""}>
                <Icon path={item.icon} />
              </span>
              {item.label}
            </>
          )}
        </NavLink>
      ))}
    </nav>
  );
}

function SimulatorNote() {
  return (
    <div className="rounded-xl border border-warn/25 bg-warn/[0.06] p-3 text-xs leading-relaxed text-muted">
      <p className="mb-1 font-semibold text-warn">Simulator</p>
      Fictional merchants and synthetic data only. Not connected to any card network. Never enter real card details.
    </div>
  );
}

export default function Layout() {
  const [open, setOpen] = useState(false);
  const location = useLocation();

  useEffect(() => {
    window.scrollTo(0, 0);
  }, [location.pathname]);

  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && setOpen(false);
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [open]);

  return (
    <div className="min-h-screen lg:pl-64">
      <a href="#main" className="sr-only focus:not-sr-only focus:fixed focus:left-4 focus:top-4 focus:z-50 focus:rounded-lg focus:bg-accent focus:px-4 focus:py-2 focus:text-white">
        Skip to content
      </a>

      {/* desktop sidebar */}
      <aside className="fixed inset-y-0 left-0 hidden w-64 flex-col border-r border-line bg-surface/60 px-4 py-5 lg:flex">
        <div className="mb-7 px-2">
          <Brand />
        </div>
        <div className="flex-1 overflow-y-auto">
          <Navigation />
        </div>
        <SimulatorNote />
      </aside>

      {/* mobile top bar */}
      <header className="sticky top-0 z-30 flex items-center justify-between border-b border-line bg-bg/85 px-4 py-3 backdrop-blur lg:hidden">
        <Brand />
        <div className="flex items-center gap-2">
          <span className="rounded-full border border-warn/30 bg-warn/10 px-2.5 py-0.5 text-[0.7rem] font-medium text-warn">Simulator</span>
          <button
            type="button"
            onClick={() => setOpen(true)}
            aria-label="Open menu"
            aria-expanded={open}
            className="grid h-9 w-9 place-items-center rounded-lg border border-line-strong"
          >
            <Icon path="M4 7h16M4 12h16M4 17h16" />
          </button>
        </div>
      </header>

      {open ? (
        <div className="fixed inset-0 z-40 lg:hidden" role="dialog" aria-modal="true" aria-label="Menu">
          <button type="button" aria-label="Close menu" className="absolute inset-0 bg-black/60" onClick={() => setOpen(false)} />
          <div className="fade-up absolute inset-y-0 left-0 flex w-72 max-w-[85vw] flex-col border-r border-line bg-surface px-4 py-5">
            <div className="mb-6 flex items-center justify-between px-2">
              <Brand />
              <button type="button" onClick={() => setOpen(false)} aria-label="Close menu" className="rounded p-1 text-muted hover:text-fg">
                ✕
              </button>
            </div>
            <div className="flex-1 overflow-y-auto">
              <Navigation onNavigate={() => setOpen(false)} />
            </div>
            <SimulatorNote />
          </div>
        </div>
      ) : null}

      <main id="main" className="mx-auto w-full max-w-[1320px] px-4 py-6 sm:px-6 lg:px-10 lg:py-9">
        <Outlet />
      </main>
    </div>
  );
}
