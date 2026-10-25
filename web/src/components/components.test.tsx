import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, assert, test, vi } from "vitest";
import type { Broadcast, Snapshot } from "../broadcast";
import { DEFAULTS } from "../lib/settings";
import { MatchControls, Moments, YourView } from "./Panel";
import { LowerThird, Prematch } from "./Stage";

afterEach(cleanup);

const base: Snapshot = {
  ready: true, gate: false, account: { enabled: false, name: null }, playback: null, coreOk: true,
  agentsOk: true, agentModel: "stub", clock: "00:00", score: [0, 0], home: null, away: null, ball: null,
  trail: [], dots: [], tag: null, ticker: "", card: null, corner: null, shape: null, momentum: null,
  prematch: null, moments: [],
  latency: { delay: "–", quick: "–", model: "–", lead: "–", leadLate: false, onTime: "–", onTimeOk: null },
  player: null, toasts: [], settings: { persona: "casual", lang: "en", team: "", player: "", metric: "", groups: ["chances", "flow", "tactics", "players"] },
  view: DEFAULTS, teams: [], squads: [], busy: { start: false, pause: false, retry: false },
};

function fakeBroadcast() {
  const mocks = { startMatch: vi.fn(), togglePause: vi.fn(), setSetting: vi.fn(), toggleGroup: vi.fn(), replay: vi.fn(), signIn: vi.fn(), signOut: vi.fn() };
  return { ...mocks, b: mocks as unknown as Broadcast };
}

test("a card shows its kicker, line, metrics and badges; a withdrawn one says so", () => {
  const card = {
    key: "k", overlayId: "O_1", kicker: "Goal · 11'", headline: "Carver scores", body: "It is 0-1.",
    metrics: [{ label: "xG", value: 0.12 }], badges: [{ cls: "ok", text: "✓ Verified" }], away: true, lang: "en" as const,
    retracted: false, pending: false,
  };
  const { rerender } = render(<LowerThird card={card} />);
  screen.getByText("Goal · 11'");
  screen.getByRole("heading", { name: "Carver scores" });
  screen.getByText("✓ Verified");
  assert.isNull(screen.queryByText(/Correction/));
  rerender(<LowerThird card={{ ...card, retracted: true }} />);
  screen.getByText("Correction: this moment was withdrawn");
});

test("the pre-match screen shows both teams and counts down to the broadcast", () => {
  const team = (name: string, formation: string) => ({ id: name, name, short: name.slice(0, 3), formation, starters: [{ shirt: 9, name: `${name} striker` }] });
  render(
    <Prematch
      s={{ ...base, playback: { status: "running", seed: 154, speed: 10, delay_s: 120, tracking: false, envelopes_viewer: 0 }, prematch: { countdownS: 5.2, delayS: 120 }, home: team("Northgate FC", "4-3-3"), away: team("Eastmere Rovers", "4-2-3-1") }}
    />,
  );
  screen.getByText("Kick-off in");
  screen.getByText("6 s");
  screen.getByText("4-2-3-1");
  screen.getByText(/runs 120 s of match time behind live/);
});

test("changing the speed suggests a delay, and Start sends the form", () => {
  const f = fakeBroadcast();
  render(<MatchControls s={base} b={f.b} />);
  const speed = screen.getByLabelText(/Speed/) as HTMLInputElement;
  fireEvent.change(speed, { target: { value: "20" } });
  assert.equal((screen.getByLabelText(/Delay s/) as HTMLInputElement).value, "240");
  fireEvent.click(screen.getByRole("button", { name: "Start" }));
  assert.deepEqual(f.startMatch.mock.calls[0][0], { seed: 154, speed: 20, delay_s: 240, tracking: false });
  assert.isTrue((screen.getByRole("button", { name: "Pause" }) as HTMLButtonElement).disabled, "nothing to pause yet");
});

test("view settings and moment filters go to the controller", () => {
  const f = fakeBroadcast();
  render(<YourView s={{ ...base, account: { enabled: true, name: null } }} b={f.b} />);
  fireEvent.change(screen.getByLabelText("Language"), { target: { value: "hi" } });
  assert.deepEqual(f.setSetting.mock.calls[0], ["lang", "hi"]);
  fireEvent.click(screen.getByRole("button", { name: "Sign in" }));
  assert.equal(f.signIn.mock.calls.length, 1);
  cleanup();
  const moments = [{ id: "I_0002", minute: "11'", headline: "Carver scores", meta: "Goal · verified", lang: "en" as const, pending: false, retracted: false }];
  render(<Moments s={{ ...base, moments }} b={f.b} />);
  fireEvent.click(screen.getByRole("button", { name: "Tactics" }));
  assert.deepEqual(f.toggleGroup.mock.calls[0], ["tactics"]);
  fireEvent.click(screen.getByText("Carver scores"));
  assert.deepEqual(f.replay.mock.calls[0], ["I_0002"]);
  screen.getByText("(1)");
});
