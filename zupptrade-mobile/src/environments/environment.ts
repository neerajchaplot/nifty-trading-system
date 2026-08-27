// The mobile app talks DIRECTLY to the deployed Azure backend over HTTPS (no local proxy).
// The edge nginx rewrites /api/agentN/* -> agentN:808X/api/v1/agentN/* and blanks X-API-Key,
// so identity is carried purely by the JWT Bearer token (see auth.interceptor).
const API_BASE = 'https://zupptrade.centralindia.cloudapp.azure.com';

export const environment = {
  production: false,
  apiBase: API_BASE,
  apiKey: 'dev-internal-key', // legacy; edge blanks X-API-Key — retained only to satisfy old references
  agent1BaseUrl: `${API_BASE}/api/agent1`,
  agent2BaseUrl: `${API_BASE}/api/agent2`,
  agent3BaseUrl: `${API_BASE}/api/agent3`,
  agent4BaseUrl: `${API_BASE}/api/agent4`,
  agent5BaseUrl: `${API_BASE}/api/agent5`,
  agentUserBaseUrl: `${API_BASE}/api/agent-user`,
  marketPollIntervalMs: 10000,
  tradesPollIntervalMs: 5000,
};
