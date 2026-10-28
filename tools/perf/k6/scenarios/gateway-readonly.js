import http from 'k6/http';
import { check, sleep } from 'k6';
import { gateway, authHeaders, readLoadOptions, standardThresholds } from '../lib/env.js';

const load = readLoadOptions(10, '30s');

export const options = {
  ...load,
  thresholds: standardThresholds(800),
};

export default function gatewayReadonly() {
  const headers = { ...authHeaders(), Accept: 'application/json' };

  const health = http.get(`${gateway}/healthz`);
  check(health, { 'healthz 200': (r) => r.status === 200 });

  const home = http.get(`${gateway}/v1/home?market=${market}&zip=${zip}`, { headers });
  check(home, { 'home 200': (r) => r.status === 200 });

  sleep(0.2);
}
