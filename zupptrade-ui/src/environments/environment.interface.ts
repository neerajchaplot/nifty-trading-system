/**
 * Contract that BOTH environment.ts (dev) and environment.prod.ts (prod) must satisfy.
 * Annotating each `environment` with this type makes the TypeScript compiler flag any
 * drift — a missing or extra property — at build time (and in the IDE), instead of only
 * when a production `ng build` runs. Add a new key here first, then to both env files.
 */
export interface AppEnvironment {
  production: boolean;
  apiKey: string;
  agent1BaseUrl: string;
  agent2BaseUrl: string;
  agent3BaseUrl: string;
  agent5BaseUrl: string;
  agentUserBaseUrl: string;
  marketPollIntervalMs: number;
  tradesPollIntervalMs: number;
}
