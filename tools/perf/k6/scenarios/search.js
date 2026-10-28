import http from 'k6/http';
import { check, sleep } from 'k6';
import { search, market, searchQ, readLoadOptions, standardThresholds } from '../lib/env.js';

const load = readLoadOptions(15, '45s');

export const options = {
  ...load,
  thresholds: standardThresholds(1200),
};

export default function searchQuery() {
  const url = `${search}/v1/search?q=${encodeURIComponent(searchQ)}&market=${market}&limit=24`;
  const res = http.get(url, { headers: { Accept: 'application/json' } });
  check(res, {
    'search 200': (r) => r.status === 200,
    'search has hits': (r) => {
      try {
        const body = r.json();
        return Array.isArray(body.hits);
      } catch {
        return false;
      }
    },
  });
  sleep(0.3);
}
