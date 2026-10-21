// Where the viewer finds the core and the agent service. Local defaults; `azd provision` rewrites
// this file with the deployed URLs. Query parameters (?core=...&agents=...) override it.
window.SMI_CONFIG = { core: "http://127.0.0.1:8080", agents: "http://127.0.0.1:8090" };
