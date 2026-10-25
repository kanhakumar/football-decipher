// Local defaults. On Azure the postprovision hook (infra/hooks/postprovision.sh) writes this file
// with the deployed core and agents URLs and the sign-in settings. Query parameters override.
window.SMI_CONFIG = { core: "http://127.0.0.1:8080", agents: "http://127.0.0.1:8090" };
