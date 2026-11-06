import { describe, expect, it } from 'vitest';

import { createApiClient } from '../src';
import { BFF_PROXY_FORWARD_REQUEST, EDGE_GEO_HEADERS } from '../src/correlation';
import { resetDb } from '../src/mocks';
import { mockServer } from '../src/mocks/node';

mockServer.listen({ onUnhandledRequest: 'error' });
const api = createApiClient({ baseUrl: 'http://bff.test/api/bff' });

describe('deliver-to location (mock BFF)', () => {
  it('starts detected with the city as its label and never the ZIP; a pick makes it chosen', async () => {
    resetDb();
    const first = await api.getDeliveryLocation();
    expect(first).toMatchObject({ label: 'Houston', source: 'detected' });
    expect(first.label).not.toMatch(/\d/);
    const picked = await api.setDeliveryLocation({ zip: '10456' });
    expect(picked).toMatchObject({ label: 'Bronx', city: 'Bronx', state: 'NY', source: 'chosen' });
  });

  it('place suggestions: cities list with their state, a ZIP answers its city, streets say "City, ST"', async () => {
    const cities = await api.searchAddresses('Georgetown', undefined, { kind: 'place' });
    expect(cities.suggestions.map((s) => `${s.label}|${s.secondary}`)).toEqual(['Georgetown|DE', 'Georgetown|KY', 'Georgetown|TX']);
    const zip = await api.searchAddresses('10456', undefined, { kind: 'place' });
    expect(zip.suggestions.map((s) => `${s.label}|${s.secondary}`)).toEqual(['Bronx|NY']);
    const streets = await api.searchAddresses('9800 West', undefined, { kind: 'place' });
    expect(streets.suggestions[0]).toMatchObject({ label: '9800 Westheimer Rd', secondary: 'Houston, TX' });
    expect(streets.suggestions.map((s) => s.secondary).join(' ')).not.toMatch(/\d{5}/);
  });

  it('a picked place is applied by its suggestion id (city kept, ZIP internal)', async () => {
    const [ky] = (await api.searchAddresses('Georgetown', undefined, { kind: 'place' })).suggestions.filter((s) => s.secondary === 'KY');
    const loc = await api.setDeliveryLocation({ suggestionId: ky?.id ?? '' });
    expect(loc).toMatchObject({ city: 'Georgetown', state: 'KY', label: 'Georgetown', zip: '40324', source: 'chosen' });
  });

  it('the /api/bff proxy forwards the edge location headers detection reads', () => {
    for (const h of EDGE_GEO_HEADERS) expect(BFF_PROXY_FORWARD_REQUEST).toContain(h);
    expect(EDGE_GEO_HEADERS).toContain('cf-ippostalcode');
  });
});
