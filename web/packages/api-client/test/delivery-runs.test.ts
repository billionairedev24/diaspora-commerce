import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';

import { createApiClient, deliveryRunKeys, grantedChannels, isApiError, isRunChannelOn } from '../src';
import { resetDb } from '../src/mocks';
import { mockServer } from '../src/mocks/node';

function browserLikeClient() {
  let cookie = '';
  const fetchLike = async (req: Request) => {
    if (cookie) req.headers.set('cookie', cookie);
    const res = await fetch(req);
    const set = res.headers.get('set-cookie');
    if (set) cookie = set.split(';')[0] ?? '';
    return res;
  };
  const api = createApiClient({ baseUrl: 'http://bff.test', fetch: fetchLike });
  const signIn = async () => {
    const res = await fetchLike(new Request(api.loginUrl('/'), { redirect: 'manual' }));
    expect(res.status).toBe(302);
  };
  return { api, signIn };
}

const LUBBOCK = '4b2f9d3a-0000-4000-8000-00000000000a';

beforeAll(() => mockServer.listen({ onUnhandledRequest: 'error' }));
afterAll(() => mockServer.close());
beforeEach(() => { resetDb(); });

describe('delivery run locations', () => {
  it('lists locations and gives a page with runs, the community link and attributed share links', async () => {
    const { api } = browserLikeClient();
    const { items } = await api.listRunLocations();
    expect(items.map((l) => l.slug)).toEqual(['lubbock-tx', 'amarillo-tx', 'midland-tx']);
    const page = await api.getRunLocation('lubbock-tx');
    expect(page.location.whatsappInviteUrl).toContain('chat.example');
    expect(page.runs[0]).toMatchObject({ acceptingOrders: true, full: false });
    expect(Object.values(page.shareLinks).every((u) => decodeURIComponent(u).includes('utm_source='))).toBe(true);
    await expect(api.getRunLocation('nowhere')).rejects.toMatchObject({ status: 404 });
    expect(deliveryRunKeys.location('lubbock-tx')).toEqual(['delivery-runs', 'location', 'lubbock-tx']);
  });
});

describe('alert subscriptions', () => {
  it('signs a guest up by email, then confirms and unsubscribes with link tokens', async () => {
    const { api } = browserLikeClient();
    expect(await api.subscribeRunAlerts({ locationId: LUBBOCK, email: 'ada@example.com', source: 'location-page' })).toEqual({ status: 'CONFIRMATION_SENT' });
    await expect(api.subscribeRunAlerts({ locationId: 'x', email: 'nope' })).rejects.toMatchObject({ status: 422 });
    const confirmed = await api.confirmRunAlerts('confirm-token-1');
    expect(grantedChannels(confirmed)).toEqual(['EMAIL']);
    const err = await api.confirmRunAlerts('invalid-token').catch((e: unknown) => e);
    expect(isApiError(err) && err.status === 422 && err.fieldErrors.token).toBeTruthy();
    const left = await api.unsubscribeRunAlerts('message-token-1');
    expect(grantedChannels(left)).toEqual([]);
    expect(left.consents[0]).toMatchObject({ channel: 'EMAIL', granted: false });
    expect(left.consents[0]?.withdrawnAt).toBeTruthy();
  });

  it('needs a sign-in for the account screen, then records consent per channel and its withdrawal', async () => {
    const { api, signIn } = browserLikeClient();
    await expect(api.listMyRunSubscriptions()).rejects.toMatchObject({ status: 401 });
    await signIn();
    expect((await api.listMyRunSubscriptions()).items).toEqual([]);
    const sub = await api.subscribeMyRunAlerts({ locationId: LUBBOCK, channels: ['EMAIL', 'SMS'], source: 'account' });
    expect(grantedChannels(sub).sort()).toEqual(['EMAIL', 'SMS']);
    expect(sub.consents.every((c) => c.consentedAt && c.source === 'account')).toBe(true);
    const more = await api.subscribeMyRunAlerts({ locationId: LUBBOCK, channels: ['WHATSAPP'] });
    expect(more.id).toBe(sub.id);
    expect(grantedChannels(more).sort()).toEqual(['EMAIL', 'SMS', 'WHATSAPP']);
    const { items } = await api.listMyRunSubscriptions();
    expect(isRunChannelOn(items, LUBBOCK, 'WHATSAPP')).toBe(true);
    expect(isRunChannelOn(items, LUBBOCK, 'PUSH')).toBe(false);
    const withdrawn = await api.unsubscribeMyRunAlerts(LUBBOCK, ['SMS']);
    expect(grantedChannels(withdrawn).sort()).toEqual(['EMAIL', 'WHATSAPP']);
    expect(withdrawn.consents.find((c) => c.channel === 'SMS')).toMatchObject({ granted: false, withdrawnAt: expect.any(String) });
    expect(grantedChannels(await api.unsubscribeMyRunAlerts(LUBBOCK))).toEqual([]);
    await expect(api.unsubscribeMyRunAlerts('4b2f9d3a-0000-4000-8000-0000000000ff')).rejects.toMatchObject({ status: 404 });
    await expect(api.subscribeMyRunAlerts({ locationId: LUBBOCK, channels: [] })).rejects.toMatchObject({ status: 422 });
  });
});

describe('location advertorials', () => {
  it('serves the shopper place its advertorial with attribution, and nothing elsewhere', async () => {
    const { api } = browserLikeClient();
    expect((await api.listRunAdvertorials({ city: 'Houston' })).items).toEqual([]);
    const { items } = await api.listRunAdvertorials({ zip: '79401', visitor: 'v1' });
    expect(items).toHaveLength(1);
    const ad = items[0]!;
    expect(ad.ctaUrl).toMatch(/src=page&cr=adv-[0-9a-f]{32}/);
    expect(Date.parse(ad.countdownTarget ?? '')).toBeGreaterThan(Date.now());
    // The same visitor keeps their variant; a different one may get another.
    const again = await api.listRunAdvertorials({ zip: '79401', visitor: 'v1' });
    expect(again.items[0]!.variant).toBe(ad.variant);
    await expect(api.recordRunAdvertorialEvent(ad.id, 'VIEW')).resolves.toBeUndefined();
    await expect(api.recordRunAdvertorialEvent(ad.id, 'CLICK')).resolves.toBeUndefined();
    await expect(api.recordRunAdvertorialEvent('5c3a0e4b-0000-4000-8000-0000000000ff', 'VIEW')).rejects.toMatchObject({ status: 404 });
  });
});
