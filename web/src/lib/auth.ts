// Sign-in with Microsoft Entra ID (ADR-018). Off unless config.js names an app registration; then
// MSAL signs the viewer in with a redirect and gets access tokens for the API, which every call to
// the core and the agent service carries. Without it the viewer works as before (local, tests).

import type { AccountInfo, IPublicClientApplication } from "@azure/msal-browser";

export interface AuthConfig {
  clientId: string;
  scope: string;
  authority?: string;
}

export interface Auth {
  enabled: boolean;
  account: AccountInfo | null;
  init(): Promise<void>;
  headers(): Promise<Record<string, string>>;
  signIn(): Promise<void>;
  signOut(): Promise<void>;
}

export const NO_AUTH: Auth = {
  enabled: false,
  account: null,
  init: async () => {},
  headers: async () => ({}),
  signIn: async () => {},
  signOut: async () => {},
};

export function createAuth(config: AuthConfig | undefined): Auth {
  if (!config?.clientId || !config.scope) return NO_AUTH;
  const request = { scopes: [config.scope] };
  let app: IPublicClientApplication | null = null;
  let msal: typeof import("@azure/msal-browser") | null = null;
  const auth: Auth = {
    enabled: true,
    account: null,
    async init() {
      msal = await import("@azure/msal-browser"); // loaded only when sign-in is configured
      const client = await msal.createStandardPublicClientApplication({
        auth: {
          clientId: config.clientId,
          authority: config.authority ?? "https://login.microsoftonline.com/common",
          redirectUri: location.origin + location.pathname,
        },
        cache: { cacheLocation: "localStorage" },
      });
      app = client;
      const result = await client.handleRedirectPromise();
      auth.account = result?.account ?? client.getAllAccounts()[0] ?? null;
      if (auth.account) client.setActiveAccount(auth.account);
    },
    signIn: async () => app?.loginRedirect(request),
    signOut: async () => app?.logoutRedirect({ account: auth.account }),
    /** The Authorization header, refreshing the token silently; a redirect if it must ask. */
    async headers(): Promise<Record<string, string>> {
      if (!app || !auth.account) return {};
      try {
        const r = await app.acquireTokenSilent({ ...request, account: auth.account });
        return { Authorization: `Bearer ${r.accessToken}` };
      } catch (e) {
        if (msal && e instanceof msal.InteractionRequiredAuthError) await app.acquireTokenRedirect(request);
        throw e;
      }
    },
  };
  return auth;
}
