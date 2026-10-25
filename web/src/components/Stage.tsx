// The broadcast screen: pitch, scorebug, cards, shape map, momentum bar, and the screens before
// kickoff and behind the sign-in gate.

import type { Broadcast, CardView, Snapshot, TeamView } from "../broadcast";

export function Stage({ s, b }: { s: Snapshot; b: Broadcast }) {
  return (
    <section className="stage" aria-label="Broadcast">
      <Pitch s={s} />
      <Scorebug s={s} />
      <LiveChip s={s} />
      {s.corner && (
        <aside className="corner-card" key={s.corner.key} lang={s.corner.lang} data-testid="corner-card">
          <small>{s.corner.kicker}</small>
          {s.corner.body ? (
            <>
              <b>{s.corner.headline}</b>
              <span className="corner-body">{s.corner.body}</span>
            </>
          ) : (
            s.corner.headline
          )}
        </aside>
      )}
      {s.shape && <div className="shape-caption">{s.shape.caption}</div>}
      {s.card && <LowerThird card={s.card} />}
      {s.momentum && (
        <footer className="momentum" aria-label="Momentum">
          <span className="label">{s.momentum.label}</span>
          <div className="bar">
            <span className="home" style={{ flexGrow: s.momentum.home }} />
            <span className="away" style={{ flexGrow: 100 - s.momentum.home }} />
          </div>
          <span className="figures">{s.momentum.figures}</span>
        </footer>
      )}
      <div className="ticker">{s.ticker}</div>
      {s.prematch && <Prematch s={s} />}
      {s.ready && !s.coreOk && (
        <div className="banner" role="alert">
          <span>Lost contact with the match service. Retrying every second…</span>
          <button type="button" className={s.busy.retry ? "busy" : ""} disabled={s.busy.retry} onClick={() => b.retry()}>
            Retry now
          </button>
        </div>
      )}
      {s.gate && (
        <div className="signin-gate">
          <p>Sign in with a Microsoft account (work, school or personal) to watch and keep your view on any device.</p>
          <button type="button" onClick={() => b.signIn()}>
            Sign in
          </button>
        </div>
      )}
    </section>
  );
}

function Pitch({ s }: { s: Snapshot }) {
  return (
    <svg className="pitch" viewBox="-3 -3 111 74" preserveAspectRatio="xMidYMid meet" aria-hidden="true">
      <g className="lines">
        <rect x="0" y="0" width="105" height="68" />
        <line x1="52.5" y1="0" x2="52.5" y2="68" />
        <circle cx="52.5" cy="34" r="9.15" />
        <circle cx="52.5" cy="34" r="0.4" className="spot" />
        <rect x="0" y="13.84" width="16.5" height="40.32" />
        <rect x="88.5" y="13.84" width="16.5" height="40.32" />
        <rect x="0" y="24.84" width="5.5" height="18.32" />
        <rect x="99.5" y="24.84" width="5.5" height="18.32" />
        <circle cx="11" cy="34" r="0.4" className="spot" />
        <circle cx="94" cy="34" r="0.4" className="spot" />
        <path d="M16.5 26.7 A9.15 9.15 0 0 1 16.5 41.3" />
        <path d="M88.5 26.7 A9.15 9.15 0 0 0 88.5 41.3" />
        <rect x="-2" y="30.34" width="2" height="7.32" className="goal" />
        <rect x="105" y="30.34" width="2" height="7.32" className="goal" />
      </g>
      <g>
        {s.dots.map((d) => (
          <circle key={d.id} className={d.fav ? "dot fav" : "dot"} r={1.1} cx={d.x} cy={d.y} fill={d.home ? "var(--home)" : "var(--away)"} />
        ))}
      </g>
      <g className="trail">
        {s.trail.map((t, i) => (
          <circle key={i} cx={t.x} cy={t.y} r={0.9} style={{ fill: t.home ? "var(--home)" : "var(--away)" }} />
        ))}
      </g>
      {s.shape && (
        <g className="shape" data-testid="shape-map">
          {s.shape.dots.map((d, i) => (
            <g key={i}>
              <circle cx={d.x} cy={d.y} r={2} style={{ fill: d.home ? "var(--home)" : "var(--away)" }} />
              <text x={d.x} y={d.y + 0.9}>
                {d.shirt}
              </text>
            </g>
          ))}
        </g>
      )}
      {s.ball && <circle className="ball" r={0.9} cx={s.ball.x} cy={s.ball.y} />}
      {s.tag && (
        <g className="player-tag" transform={`translate(${Math.min(s.tag.x, 80)} ${Math.max(s.tag.y, 7)})`}>
          <rect x={-0.2} y={-6.2} rx={1} ry={1} height={4.2} width={s.tag.label.length * 1.45 + 2} />
          <text x={1} y={-3.3}>
            {s.tag.label}
          </text>
        </g>
      )}
    </svg>
  );
}

function Scorebug({ s }: { s: Snapshot }) {
  return (
    <header className="scorebug" aria-label="Score">
      <span className="brand">
        DECIPHER<b>LIVE</b>
      </span>
      <span className="team home">
        <i className="kit" />
        <span className="short">{s.home?.short ?? "HOM"}</span>
      </span>
      <span className="score" data-testid="score">
        <span>{s.score[0]}</span>
        <span className="dash">–</span>
        <span>{s.score[1]}</span>
      </span>
      <span className="team away">
        <span className="short">{s.away?.short ?? "AWY"}</span>
        <i className="kit" />
      </span>
      <span className="clock" data-testid="clock">
        {s.clock}
      </span>
    </header>
  );
}

const STATUS_LABELS: Record<string, string> = { running: "LIVE", paused: "PAUSED", finished: "FULL TIME", idle: "WAITING" };

function LiveChip({ s }: { s: Snapshot }) {
  const status = s.playback?.status;
  const text = !s.coreOk ? "OFFLINE" : status ? STATUS_LABELS[status] ?? status.toUpperCase() : "WAITING";
  return <div className={`live-chip ${s.coreOk ? status ?? "" : ""}`}>{text}</div>;
}

export function LowerThird({ card }: { card: CardView }) {
  // Keyed by overlay: a model line that replaces the quick one swaps text without a new slide-in.
  return (
    <div
      key={card.pending ? card.key : `${card.overlayId}|${card.retracted}`}
      className={`lower-third${card.retracted ? " retracted" : ""}${card.pending ? " pending" : ""}`}
      aria-live="polite"
      lang={card.lang}
      data-testid="lower-third"
    >
      <div className="stripe" style={{ background: card.away ? "var(--away)" : "var(--home)" }} />
      <div className="card-body">
        <div className="kicker">{card.kicker}</div>
        <h2>{card.headline}</h2>
        {card.body && <p>{card.body}</p>}
        {card.metrics.length > 0 && (
          <ul className="metrics">
            {card.metrics.map((m) => (
              <li key={m.label}>
                {m.label}
                <b>
                  {m.value}
                  {m.unit ?? ""}
                </b>
              </li>
            ))}
          </ul>
        )}
        {card.badges.length > 0 && (
          <div className="badges">
            {card.badges.map((x) => (
              <span key={x.text} className={`badge ${x.cls}`}>
                {x.text}
              </span>
            ))}
          </div>
        )}
      </div>
      {card.retracted && <div className="correction">Correction: this moment was withdrawn</div>}
    </div>
  );
}

/** Before kickoff reaches the viewer: who is playing, and when the broadcast starts. */
export function Prematch({ s }: { s: Snapshot }) {
  const p = s.prematch!;
  const seconds = Math.ceil(p.countdownS);
  return (
    <div className="prematch" data-testid="prematch">
      <div className="prematch-teams">
        <TeamSheet team={s.home} side="home" />
        <div className="kickoff">
          <small>{s.playback?.status === "paused" ? "Paused before kickoff" : "Kick-off in"}</small>
          <strong>{seconds} s</strong>
        </div>
        <TeamSheet team={s.away} side="away" />
      </div>
      <p className="prematch-note">
        The broadcast runs {p.delayS} s of match time behind live, so the analysis of every moment is ready by the time you see it.
      </p>
    </div>
  );
}

function TeamSheet({ team, side }: { team: TeamView | null; side: "home" | "away" }) {
  if (!team) return <div className={`team-sheet ${side}`} />;
  return (
    <div className={`team-sheet ${side}`}>
      <h3>{team.name}</h3>
      {team.formation && <div className="formation">{team.formation}</div>}
      <ol>
        {team.starters.map((p) => (
          <li key={p.shirt}>
            <b>{p.shirt}</b> {p.name}
          </li>
        ))}
      </ol>
    </div>
  );
}
