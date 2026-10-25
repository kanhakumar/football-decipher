// Football Decipher Live: the broadcast-style viewer (ADR-020). Endpoints and sign-in come from config.js
// (written at deploy, ADR-018); query parameters override the endpoints.

import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { App } from "./App";
import { Broadcast } from "./broadcast";
import { createAuth, type AuthConfig } from "./lib/auth";
import "./styles.css";

declare global {
  interface Window {
    SMI_CONFIG?: { core?: string; agents?: string; auth?: AuthConfig };
  }
}

const params = new URLSearchParams(location.search);
const deployed = window.SMI_CONFIG ?? {};
const broadcast = new Broadcast(
  {
    core: params.get("core") ?? deployed.core ?? "http://127.0.0.1:8080",
    agents: params.get("agents") ?? deployed.agents ?? "http://127.0.0.1:8090",
  },
  { auth: createAuth(deployed.auth) },
);

// Started once here, not in an effect (React runs effects twice in development).
void broadcast.start();

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <App broadcast={broadcast} />
  </StrictMode>,
);
