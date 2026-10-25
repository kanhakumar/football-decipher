// Sign-in with Microsoft Entra ID (ADR-018). Off unless config.js names an app registration; then
// MSAL (pinned, from jsDelivr) signs the viewer in with a redirect and gets access tokens for the
// API, which every call to the core and the agent service carries. Without it the viewer works
// as before (local runs, tests).

const MSAL_URL = "https://cdn.jsdelivr.net/npm/@azure/msal-browser@5.25.0/+esm";

/** `config`: { clientId, scope, authority? } from window.SMI_CONFIG.auth, or nothing. */
export function createAuth(config) {
  if (!config?.clientId || !config?.scope) {
    return {
      enabled: false,
      account: null,
      init: async () => {},
      headers: async () => ({}),
      signIn: async () => {},
      signOut: async () => {},
    };
  }
  let msal = null;
  let app = null;
  const request = { scopes: [config.scope] };
  const auth = {
    enabled: true,
    account: null,
    async init() {
      msal = await import(MSAL_URL);
      app = new msal.PublicClientApplication({
        auth: {
          clientId: config.clientId,
          authority: config.authority ?? "https://login.microsoftonline.com/common",
          redirectUri: location.origin + location.pathname,
        },
        cache: { cacheLocation: "localStorage" },
      });
      await app.initialize();
      const result = await app.handleRedirectPromise();
      auth.account = result?.account ?? app.getAllAccounts()[0] ?? null;
      if (auth.account) app.setActiveAccount(auth.account);
    },
    signIn: () => app.loginRedirect(request),
    signOut: () => app.logoutRedirect({ account: auth.account }),
    /** The Authorization header, refreshing the token silently; a redirect if it must ask. */
    async headers() {
      if (!auth.account) return {};
      try {
        const r = await app.acquireTokenSilent({ ...request, account: auth.account });
        return { Authorization: `Bearer ${r.accessToken}` };
      } catch (e) {
        if (e instanceof msal.InteractionRequiredAuthError) await app.acquireTokenRedirect(request);
        throw e;
      }
    },
  };
  return auth;
}
