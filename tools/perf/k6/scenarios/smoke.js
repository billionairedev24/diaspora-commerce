import http from 'k6/http';
import { check } from 'k6';
import { gateway, search, readLoadOptions, standardThresholds } from '../lib/env.js';

// Short mixed read: one iteration per VU, good for a quick sanity check before a longer run.
const load = readLoadOptions(5, '15s');

export const options = {
  ...load,
  thresholds: standardThresholds(2000),
};

const targets = [
  { name: 'gateway healthz', url: () => `${gateway}/healthz` },
  { name: 'search healthz', url: () => `${search}/healthz` },
  { name: 'gateway readyz', url: () => `${gateway}/readyz` },
];

export default function smoke() {
  for (const t of targets) {
    const res = http.get(t.url());
    check(res, { [`${t.name} 200`]: (r) => r.status === 200 });
  }
}
