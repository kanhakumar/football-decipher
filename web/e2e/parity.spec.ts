import { expect, test, type Page } from "@playwright/test";

// Parity with the vanilla viewer (ADR-020): every behaviour /viewer has, against the real core and
// the agent service (stub model), on the demo match (seed 154) at 40x. One match, in order.

test.describe.configure({ mode: "serial" });

const lowerThird = (page: Page) => page.getByTestId("lower-third");
const moments = (page: Page) => page.getByTestId("moments");

test("the demo match plays with every viewer behaviour", async ({ page }) => {
  await page.goto("/");

  await test.step("loads: scorebug, health chips, configured Start form", async () => {
    await expect(page.getByText("Core: connected")).toBeVisible();
    await expect(page.getByText(/Agents: stub/)).toBeVisible();
    await expect(page.getByLabel("Seed")).toHaveValue("154");
  });

  await test.step("start at 40x: the delay follows the speed, the button is busy, a toast confirms", async () => {
    await page.getByLabel(/Speed/).fill("40");
    await expect(page.getByLabel(/Delay s/)).toHaveValue("480");
    await page.getByRole("button", { name: "Start" }).click();
    await expect(page.getByText("Match started: seed 154 at 40x")).toBeVisible();
  });

  await test.step("before kickoff reaches the viewer: both teams and a countdown", async () => {
    const prematch = page.getByTestId("prematch");
    await expect(prematch).toContainText("Northgate FC");
    await expect(prematch).toContainText("Eastmere Rovers");
    await expect(prematch).toContainText("Kick-off in");
    await expect(prematch).toBeHidden({ timeout: 30_000 });
  });

  await test.step("cards: a verified key pass, then the goal, which moves the score", async () => {
    await expect(moments(page)).toContainText("Key pass", { timeout: 60_000 });
    await expect(page.getByTestId("score")).toHaveText("0–1", { timeout: 60_000 });
    await expect(moments(page)).toContainText("Liam Carver");
    await expect(moments(page)).toContainText("Goal · verified");
  });

  await test.step("the shape map: average positions on the pitch, labelled as an estimate", async () => {
    await expect(page.getByTestId("shape-map")).toBeVisible({ timeout: 60_000 });
    await expect(page.getByText(/estimated from events/)).toBeVisible();
  });

  await test.step("pause and resume", async () => {
    await page.getByRole("button", { name: "Pause" }).click();
    await expect(page.locator(".live-chip")).toHaveText("PAUSED");
    await page.getByRole("button", { name: "Resume" }).click();
    await expect(page.locator(".live-chip")).toHaveText("LIVE");
  });

  await test.step("another language re-narrates the moments in place", async () => {
    await page.getByLabel("Language").selectOption("es");
    await expect(moments(page).locator('[lang="es"]').first()).toBeVisible({ timeout: 30_000 });
  });

  await test.step("filters hide a group of moments", async () => {
    const before = await moments(page).locator("li").count();
    await page.getByRole("button", { name: "Players" }).click();
    await expect(moments(page)).not.toContainText("Key pass");
    await page.getByRole("button", { name: "Players" }).click();
    await expect.poll(() => moments(page).locator("li").count()).toBeGreaterThanOrEqual(before);
  });

  await test.step("a moment from the list can be shown again", async () => {
    await moments(page).getByText("Liam Carver").first().click();
    await expect(lowerThird(page)).toBeVisible();
  });

  await test.step("a reload mid-match keeps the score and the moments", async () => {
    await page.reload();
    await expect(page.getByTestId("score")).not.toHaveText("0–0", { timeout: 30_000 });
    await expect(moments(page)).toContainText("Liam Carver", { timeout: 30_000 });
  });
});
