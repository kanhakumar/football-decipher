// The side panel: match controls, the viewer's own view, player focus, latency and the moments.

import { useEffect, useState } from "react";
import type { Broadcast, Snapshot, UserSettings } from "../broadcast";
import { GROUP_LABELS, LANGS, METRICS, PERSONAS } from "../lib/labels";
import { GROUP_NAMES } from "../lib/match";
import { suggestDelay } from "../lib/timeline";

export function Panel({ s, b }: { s: Snapshot; b: Broadcast }) {
  return (
    <section className="panel" aria-label="Controls and settings">
      <MatchControls s={s} b={b} />
      <YourView s={s} b={b} />
      {s.player && (
        <div className="box">
          <h3>{s.player.title}</h3>
          <dl className="stats">
            {s.player.rows.map((r) => (
              <Row key={r.label} label={r.label}>
                {r.value}
                <small>{r.basis}</small>
              </Row>
            ))}
          </dl>
          <p className="note">{s.player.note}</p>
        </div>
      )}
      <Latency s={s} />
      <Moments s={s} b={b} />
    </section>
  );
}

function Row({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <>
      <dt>{label}</dt>
      <dd>{children}</dd>
    </>
  );
}

export function MatchControls({ s, b }: { s: Snapshot; b: Broadcast }) {
  const v = s.view;
  const [form, setForm] = useState({ seed: v.seed, speed: v.speed, delay: suggestDelay(v.speed, v), tracking: v.tracking });
  // The configured match (ADR-019) is the form's default once the settings have loaded.
  useEffect(() => {
    if (s.ready) setForm({ seed: v.seed, speed: v.speed, delay: suggestDelay(v.speed, v), tracking: v.tracking });
  }, [s.ready]); // eslint-disable-line react-hooks/exhaustive-deps
  const status = s.playback?.status;
  const statusText =
    s.gate
      ? "Sign in to watch."
      : !s.coreOk
        ? "Core not reachable."
        : !s.playback || status === "idle"
          ? "No match yet. Press Start."
          : `Seed ${s.playback.seed} · ${s.playback.speed}x · delay ${s.playback.delay_s} s · ${
              s.playback.tracking ? "tracking on (player dots)" : "tracking off (dots: last actions by team)"
            }`;
  return (
    <details className="box" open>
      <summary>Match</summary>
      <form
        className="row"
        onSubmit={(e) => {
          e.preventDefault();
          void b.startMatch({ seed: form.seed, speed: form.speed, delay_s: form.delay, tracking: form.tracking });
        }}
      >
        <label>
          Seed <input type="number" min={0} value={form.seed} onChange={(e) => setForm({ ...form, seed: Number(e.target.value) })} />
        </label>
        <label>
          Speed{" "}
          <input
            type="number"
            min={0.25}
            max={600}
            step={0.25}
            value={form.speed}
            onChange={(e) => {
              const speed = Number(e.target.value);
              setForm({ ...form, speed, delay: speed > 0 ? suggestDelay(speed, v) : form.delay });
            }}
          />
        </label>
        <label title="Video seconds the viewer runs behind the live side, so narration has real time to arrive.">
          Delay s{" "}
          <input type="number" min={0} max={v.maxDelayS} step={1} value={form.delay} onChange={(e) => setForm({ ...form, delay: Number(e.target.value) })} />
        </label>
        <label className="check">
          <input type="checkbox" checked={form.tracking} onChange={(e) => setForm({ ...form, tracking: e.target.checked })} /> Tracking
        </label>
        <button type="submit" className={s.busy.start ? "busy" : ""} disabled={s.busy.start || s.gate}>
          Start
        </button>
        <button
          type="button"
          className={`secondary${s.busy.pause ? " busy" : ""}`}
          disabled={s.busy.pause || !["running", "paused"].includes(status ?? "")}
          onClick={() => void b.togglePause()}
        >
          {status === "paused" ? "Resume" : "Pause"}
        </button>
      </form>
      <p className="status">{statusText}</p>
      <div className="chips">
        <span className={`chip ${s.coreOk ? "ok" : "bad"}`}>{s.coreOk ? "Core: connected" : "Core: unreachable"}</span>
        <span className={`chip ${s.agentsOk ? "ok" : "bad"}`}>{s.agentsOk ? `Agents: ${s.agentModel}` : "Agents: unreachable"}</span>
      </div>
    </details>
  );
}

export function YourView({ s, b }: { s: Snapshot; b: Broadcast }) {
  const set = <K extends keyof UserSettings>(k: K) => (e: React.ChangeEvent<HTMLSelectElement>) =>
    b.setSetting(k, e.target.value as UserSettings[K]);
  return (
    <div className="box">
      <h3>Your view</h3>
      {s.account.enabled && (
        <div className="account">
          {s.account.name ? (
            <>
              <span>Signed in as {s.account.name}</span>
              <button type="button" className="link" onClick={() => void b.signOut()}>
                Sign out
              </button>
            </>
          ) : (
            <button type="button" className="link" onClick={() => void b.signIn()}>
              Sign in
            </button>
          )}
        </div>
      )}
      <div className="grid">
        <label>
          Persona
          <select value={s.settings.persona} onChange={set("persona")}>
            {PERSONAS.map(([v, l]) => (
              <option key={v} value={v}>
                {l}
              </option>
            ))}
          </select>
        </label>
        <label>
          Language
          <select value={s.settings.lang} onChange={set("lang")}>
            {LANGS.map(([v, l]) => (
              <option key={v} value={v}>
                {l}
              </option>
            ))}
          </select>
        </label>
        <label>
          Favourite club
          <select value={s.settings.team} onChange={set("team")}>
            <option value="">None</option>
            {s.teams.map((t) => (
              <option key={t.id} value={t.id}>
                {t.name}
              </option>
            ))}
          </select>
        </label>
        <label>
          Favourite player
          <select value={s.settings.player} onChange={set("player")}>
            <option value="">None</option>
            {s.squads.map((g) => (
              <optgroup key={g.team} label={g.team}>
                {g.players.map((p) => (
                  <option key={p.id} value={p.id}>
                    {p.label}
                  </option>
                ))}
              </optgroup>
            ))}
          </select>
        </label>
        <label>
          Metric focus
          <select value={s.settings.metric} onChange={set("metric")}>
            {METRICS.map(([v, l]) => (
              <option key={v} value={v}>
                {l}
              </option>
            ))}
          </select>
        </label>
      </div>
    </div>
  );
}

function Latency({ s }: { s: Snapshot }) {
  const l = s.latency;
  return (
    <div className="box">
      <h3>Latency</h3>
      <dl className="stats latency">
        <Row label="Viewer delay (video)">{l.delay}</Row>
        <Row label="Quick line">{l.quick}</Row>
        <Row label="Model line">{l.model}</Row>
        <Row label="Ready ahead of moment">
          <span style={{ color: l.leadLate ? "var(--warn)" : undefined }}>{l.lead}</span>
        </Row>
        <Row label="Last card on screen">
          <span style={{ color: l.onTimeOk === null ? undefined : l.onTimeOk ? "var(--ok)" : "var(--warn)" }}>{l.onTime}</span>
        </Row>
      </dl>
    </div>
  );
}

export function Moments({ s, b }: { s: Snapshot; b: Broadcast }) {
  return (
    <div className="box moments-box">
      <h3>
        Moments <span className="count">{s.moments.length ? `(${s.moments.length})` : ""}</span>
      </h3>
      <div className="filters" aria-label="Show moments">
        {GROUP_NAMES.map((g) => (
          <button key={g} type="button" className="filter" aria-pressed={s.settings.groups.includes(g)} onClick={() => b.toggleGroup(g)}>
            {GROUP_LABELS[g]}
          </button>
        ))}
      </div>
      <ol className="moments" data-testid="moments">
        {s.moments.map((m) => (
          <li
            key={m.id}
            className={[m.retracted ? "retracted" : "", m.pending ? "pending" : ""].join(" ").trim()}
            onClick={() => b.replay(m.id)}
          >
            <span className="min">{m.minute}</span>
            <span className="head" lang={m.lang}>
              {m.headline}
            </span>
            <span className="meta">{m.meta}</span>
          </li>
        ))}
      </ol>
    </div>
  );
}
