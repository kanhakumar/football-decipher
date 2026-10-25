import { useEffect, useSyncExternalStore } from "react";
import type { Broadcast } from "./broadcast";
import { Panel } from "./components/Panel";
import { Stage } from "./components/Stage";

/** The viewer: one broadcast controller, rendered from its snapshots. */
export function App({ broadcast: b }: { broadcast: Broadcast }) {
  const s = useSyncExternalStore(b.subscribe, b.getSnapshot);
  useEffect(() => {
    document.documentElement.lang = s.settings.lang || "en";
  }, [s.settings.lang]);
  // Team colours from the lineups.
  const colours = {
    ...(s.home?.color ? { "--home": s.home.color } : {}),
    ...(s.away?.color ? { "--away": s.away.color } : {}),
  } as React.CSSProperties;
  return (
    <>
      <main className="app" style={colours}>
        <Stage s={s} b={b} />
        <Panel s={s} b={b} />
      </main>
      <div className="toasts" aria-live="polite">
        {s.toasts.map((t) => (
          <div key={t.id} className={`toast ${t.kind}`} onClick={() => b.dismissToast(t.id)}>
            {t.text}
          </div>
        ))}
      </div>
    </>
  );
}
