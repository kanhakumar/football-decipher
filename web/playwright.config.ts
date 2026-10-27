import { defineConfig, devices } from "@playwright/test";

// The parity suite (ADR-020) runs the real services: the core jar, the agent service on the
// offline stub model, and the built viewer. Servers already running are reused (local runs);
// CI starts all three. SMI_PYTHON names the Python with smi_agents installed.
const python = process.env.SMI_PYTHON ?? "python";

export default defineConfig({
  testDir: "e2e",
  timeout: 420_000, // the demo match plays to full time at 40x
  expect: { timeout: 30_000 },
  fullyParallel: false,
  workers: 1,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [["list"], ["html", { open: "never" }]] : "list",
  use: { baseURL: "http://127.0.0.1:5600", trace: "retain-on-failure", ...devices["Desktop Chrome"] },
  webServer: [
    {
      command: "java -jar ../core/target/scala-3.9.0/app.jar",
      url: "http://127.0.0.1:8080/health",
      reuseExistingServer: true,
      timeout: 120_000,
    },
    {
      command: `${python} -m smi_agents serve --port 8090`,
      url: "http://127.0.0.1:8090/health",
      reuseExistingServer: true,
      timeout: 120_000,
      env: { SMI_MODEL: "stub" },
    },
    {
      command: "npm run build && npm run preview -- --host 127.0.0.1",
      url: "http://127.0.0.1:5600",
      reuseExistingServer: true,
      timeout: 120_000,
    },
  ],
});
