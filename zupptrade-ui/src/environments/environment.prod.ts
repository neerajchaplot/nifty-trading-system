export const environment = {
  production: true,
  apiKey: '${API_KEY}',          // replaced at container start via envsubst
  agent1BaseUrl: '/api/agent1',
  agent2BaseUrl: '/api/agent2',
  agent3BaseUrl: '/api/agent3',
  agent5BaseUrl: '/api/agent5',
  agentUserBaseUrl: '/api/agent-user',
  marketPollIntervalMs: 10000,
  tradesPollIntervalMs: 5000,
};
