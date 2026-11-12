import { describe, expect, it } from 'vitest';

import { createMemoryProgressStore } from '../src/progress';
import { defineTour, type Tour } from '../src/schema';
import { createTourService, routeMatches, type TourService } from '../src/service';
import { createFakeEngine, createFakeEnvironment, TEST_LABELS } from '../src/testing';

const subject = { userId: 'u1', roles: ['seller.owner'], permissions: [], flags: [] };

function tour(over: Partial<Parameters<typeof defineTour>[0]> = {}): Tour {
  return defineTour({
    id: 'products',
    version: 1,
    title: 'x.tour.products.title',
    summary: 'x.tour.products.summary',
    autoStart: { routes: ['/listings'] },
    steps: [
      { id: 'header', target: 'page-listings', title: 'x.s1.title', body: 'x.s1.body' },
      { id: 'add', target: 'add-product', title: 'x.s2.title', body: 'x.s2.body' },
      { id: 'table', target: 'table-listings', title: 'x.s3.title', body: 'x.s3.body' }
    ],
    ...over
  });
}

function setup(opts: { targets?: string[]; tours?: Tour[]; path?: string; guards?: Record<string, () => boolean> } = {}) {
  const engine = createFakeEngine();
  const env = createFakeEnvironment(opts.targets ?? ['page-listings', 'add-product', 'table-listings']);
  const store = createMemoryProgressStore();
  const nav: string[] = [];
  let path = opts.path ?? '/listings';
  const service: TourService = createTourService({
    engine,
    env,
    store,
    subject: () => subject,
    translate: (k) => `T(${k})`,
    labels: () => TEST_LABELS,
    navigate: (r) => {
      nav.push(r);
      path = r;
    },
    pathname: () => path,
    guards: opts.guards
  });
  service.register(opts.tours ?? [tour()]);
  return { engine, env, store, service, nav, setPath: (p: string) => (path = p) };
}

describe('TourService', () => {
  it('walks the steps with a counter and translated text', async () => {
    const { service, engine } = setup();
    expect(await service.start('products')).toBe('started');
    expect(engine.last).toMatchObject({ title: 'T(x.s1.title)', body: 'T(x.s1.body)', ordinal: 1, total: 3, isFirst: true, isLast: false });
    await service.next();
    expect(engine.last).toMatchObject({ ordinal: 2, total: 3, isFirst: false });
    await service.next();
    expect(engine.last).toMatchObject({ ordinal: 3, isLast: true });
    await service.back();
    expect(engine.last?.ordinal).toBe(2);
    expect(service.getState()).toMatchObject({ running: true, tourId: 'products', stepId: 'add' });
  });

  it('records "completed" when the last step is passed and tells the engine to hide', async () => {
    const { service, engine, store } = setup();
    await service.start('products');
    await service.next();
    await service.next();
    await service.next();
    expect(service.getState().running).toBe(false);
    expect(store.get('u1', 'products')?.status).toBe('completed');
    expect(engine.hidden).toBeGreaterThan(0);
    expect(engine.announcements.at(-1)).toBe(TEST_LABELS.finished);
  });

  it('skips a step whose target is missing, quietly, and only logs in development', async () => {
    const { service, engine, env } = setup({ targets: ['page-listings', 'table-listings'] });
    await service.start('products');
    await service.next();
    expect(engine.last).toMatchObject({ title: 'T(x.s3.title)', ordinal: 2, total: 2, isLast: true });
    expect(env.logs.some((l) => l.includes('data-tour="add-product"'))).toBe(true);
    await service.back();
    expect(engine.last?.title).toBe('T(x.s1.title)');
  });

  it('skips a step whose guard says no and counts the rest correctly', async () => {
    const { service, engine } = setup({ guards: { 'has-products': () => false }, tours: [tour({ steps: [
      { id: 'header', target: 'page-listings', title: 'x.s1.title', body: 'x.s1.body' },
      { id: 'add', target: 'add-product', title: 'x.s2.title', body: 'x.s2.body', when: 'has-products' },
      { id: 'table', target: 'table-listings', title: 'x.s3.title', body: 'x.s3.body' }
    ] })] });
    await service.start('products');
    await service.next();
    expect(engine.last).toMatchObject({ title: 'T(x.s3.title)', ordinal: 2, total: 2 });
  });

  it('reports a missing required target as drift when the tour ends, and a missing optional target as normal', async () => {
    const t = tour({ steps: [
      { id: 'header', target: 'page-listings', title: 'x.s1.title', body: 'x.s1.body' },
      { id: 'banner', target: 'gate-banner', optional: true, title: 'x.s2.title', body: 'x.s2.body' },
      { id: 'add', target: 'add-product', title: 'x.s3.title', body: 'x.s3.body' },
      { id: 'table', target: 'table-listings', title: 'x.s4.title', body: 'x.s4.body' }
    ] });
    const { service, env } = setup({ tours: [t], targets: ['page-listings', 'table-listings'] });
    await service.start('products');
    await service.next();
    await service.next();
    const ended = env.events.find((e) => e.type === 'ended');
    expect(ended).toMatchObject({ tourId: 'products', reason: 'completed', shown: 2, drift: ['add'] });
    expect(env.events.filter((e) => e.type === 'step')).toHaveLength(2);
  });

  it('ends quietly when every step is missing', async () => {
    const { service, engine } = setup({ targets: [] });
    expect(await service.start('products')).toBe('no-steps');
    expect(engine.shown).toHaveLength(0);
    expect(service.getState().running).toBe(false);
  });

  it('opens the page of a step before showing it', async () => {
    const t = tour({ steps: [
      { id: 'header', target: 'page-listings', title: 'x.s1.title', body: 'x.s1.body' },
      { id: 'orders', target: 'page-orders', route: '/orders', title: 'x.s2.title', body: 'x.s2.body' }
    ] });
    const { service, engine, nav, env } = setup({ tours: [t], targets: ['page-listings', 'page-orders'], path: '/listings' });
    await service.start('products');
    await service.next();
    expect(nav).toEqual(['/orders']);
    expect(engine.last?.title).toBe('T(x.s2.title)');
    expect(env.logs).toEqual([]);
  });

  it('a step without a route stays on the page the step before it opened', async () => {
    const t = tour({ steps: [
      { id: 'orders', target: 'page-orders', route: '/orders', title: 'x.s1.title', body: 'x.s1.body' },
      { id: 'more', target: 'table-listings', title: 'x.s2.title', body: 'x.s2.body' }
    ] });
    const { service, nav } = setup({ tours: [t], path: '/listings' });
    await service.start('products');
    await service.next();
    expect(nav).toEqual(['/orders']);
  });

  it('Skip and "Don\'t show again" record "dismissed"; closing part way keeps "in progress" with the last step', async () => {
    const a = setup();
    await a.service.start('products');
    a.service.skip();
    expect(a.store.get('u1', 'products')?.status).toBe('dismissed');

    const b = setup();
    await b.service.start('products');
    b.engine.callbacks?.setDontShowAgain(true);
    b.service.close();
    expect(b.store.get('u1', 'products')?.status).toBe('dismissed');

    const c = setup();
    await c.service.start('products');
    await c.service.next();
    c.service.close();
    expect(c.store.get('u1', 'products')).toMatchObject({ status: 'in-progress', lastStep: 'add', version: 1 });
    expect(c.service.info('products')).toMatchObject({ status: 'in-progress', resumable: true, hasUpdate: false });
  });

  it('closing a replay does not turn "completed" into "in progress"', async () => {
    const { service, store } = setup();
    store.set('u1', 'products', { status: 'completed', version: 1, at: 'x' });
    await service.start('products');
    await service.next();
    service.close();
    expect(store.get('u1', 'products')).toMatchObject({ status: 'completed', version: 1 });
  });

  it('resume starts at the last step', async () => {
    const { service, engine } = setup();
    await service.start('products', 'manual', { fromStep: 'table' });
    expect(engine.last?.title).toBe('T(x.s3.title)');
  });

  it('a chapter can be skipped from the hub without playing it', () => {
    const { service, store } = setup();
    service.dismiss('products');
    expect(store.get('u1', 'products')?.status).toBe('dismissed');
    expect(service.info('products').status).toBe('dismissed');
  });

  it('rejects a person outside the audience and unknown tours', async () => {
    const { service } = setup({ tours: [tour({ audience: { permissions: ['tax.review'] } })] });
    expect(await service.start('products')).toBe('not-allowed');
    expect(await service.start('nope')).toBe('unknown-tour');
    expect(service.available()).toEqual([]);
  });

  it('starting another tour replaces the running one and keeps where the first one was', async () => {
    const other = tour({ id: 'orders', autoStart: undefined });
    const { service, store } = setup({ tours: [tour(), other] });
    await service.start('products');
    await service.start('orders');
    expect(service.getState().tourId).toBe('orders');
    expect(store.get('u1', 'products')?.status).toBe('in-progress');
  });

  it('shows a success mark when the success target appears, without blocking Next', async () => {
    const t = tour({ steps: [
      { id: 'add', target: 'add-product', title: 'x.s1.title', body: 'x.s1.body', success: { target: 'product-form', when: 'appear' } },
      { id: 'table', target: 'table-listings', title: 'x.s2.title', body: 'x.s2.body' }
    ] });
    const { service, engine, env } = setup({ tours: [t] });
    await service.start('products');
    expect(engine.last?.succeeded).toBe(false);
    env.targets.add('product-form');
    env.notify();
    await Promise.resolve();
    expect(engine.last?.succeeded).toBe(true);
    await service.next();
    expect(engine.last?.title).toBe('T(x.s2.title)');
    expect(engine.last?.succeeded).toBe(false);
  });

  it('uses the sheet variant on narrow screens, the reading direction and reduced motion', async () => {
    const { service, engine, env } = setup();
    env.width = 390;
    env.rtl = true;
    env.reduced = true;
    await service.start('products');
    expect(engine.last).toMatchObject({ sheet: true, direction: 'rtl', reducedMotion: true });
  });

  it('a step without a target is centred', async () => {
    const t = tour({ steps: [{ id: 'welcome', title: 'x.s1.title', body: 'x.s1.body', placement: 'bottom' }] });
    const { service, engine } = setup({ tours: [t] });
    await service.start('products');
    expect(engine.last).toMatchObject({ placement: 'center', target: null });
  });
});

describe('auto-start', () => {
  it('starts the first-time tour of the page, once', async () => {
    const { service, engine, store } = setup();
    expect((await service.autoStart())?.id).toBe('products');
    expect(engine.shown).toHaveLength(1);
    // recorded as seen right away: a reload in the middle never repeats it
    expect(store.get('u1', 'products')).toMatchObject({ status: 'in-progress', lastStep: 'header' });
    service.close();
    expect(await service.autoStart()).toBeUndefined();
  });

  it('starts at most one tour by itself per visit', async () => {
    const other = tour({ id: 'orders', autoStart: { routes: ['/listings'] } });
    const { service } = setup({ tours: [tour(), other] });
    expect((await service.autoStart())?.id).toBe('products');
    service.close();
    expect(await service.autoStart()).toBeUndefined();
  });

  it('a prefix route covers the pages below it', async () => {
    const t = tour({ autoStart: { routes: ['/onboarding/*'] } });
    const { service } = setup({ tours: [t], path: '/onboarding/business' });
    expect((await service.autoStart())?.id).toBe('products');
  });

  it('waits until the first step can be drawn (the page has loaded) instead of wandering off', async () => {
    const { service, env } = setup({ targets: [] });
    expect(await service.autoStart()).toBeUndefined();
    env.targets.add('page-listings');
    expect((await service.autoStart())?.id).toBe('products');
  });

  it('does not start on another page', async () => {
    const { service } = setup({ path: '/orders' });
    expect(await service.autoStart()).toBeUndefined();
  });

  it('never starts over a dialog or menu, unsaved form data, or on a narrow screen', async () => {
    const { service, env, engine } = setup();
    env.blocking = true;
    expect(await service.autoStart()).toBeUndefined();
    env.blocking = false;
    env.unsaved = true;
    expect(await service.autoStart()).toBeUndefined();
    env.unsaved = false;
    env.width = 480;
    expect(await service.autoStart()).toBeUndefined();
    expect(engine.shown).toHaveLength(0);
    env.width = 1280;
    expect((await service.autoStart())?.id).toBe('products');
  });

  it('never starts by itself in an automated browser, but a manual start still works', async () => {
    const { service, env } = setup();
    env.robot = true;
    expect(await service.autoStart()).toBeUndefined();
    expect(await service.start('products')).toBe('started');
  });

  it('does not start while someone is typing in a field', async () => {
    const { service } = setup();
    const input = document.createElement('input');
    document.body.append(input);
    input.focus();
    expect(await service.autoStart()).toBeUndefined();
  });

  it('a new version never starts by itself: it is a "What\'s new" mark in the hub', async () => {
    const { service, store } = setup();
    store.set('u1', 'products', { status: 'dismissed', version: 1, at: 'x' });
    expect(await service.autoStart()).toBeUndefined();
    service.register([tour({ version: 2 })]);
    expect(await service.autoStart()).toBeUndefined();
    expect(service.info('products')).toMatchObject({ status: 'dismissed', hasUpdate: true });
    // replaying it and finishing acknowledges the update
    await service.start('products');
    service.close();
    expect(service.info('products').hasUpdate).toBe(false);
  });

  it('only people in the audience get it', async () => {
    const { service } = setup({ tours: [tour({ audience: { roles: ['seller.manager'] } })] });
    expect(await service.autoStart()).toBeUndefined();
  });

  it('reset makes first-time tours start again', async () => {
    const { service, store } = setup();
    store.set('u1', 'products', { status: 'completed', version: 1, at: 'x' });
    expect(await service.autoStart()).toBeUndefined();
    expect(await service.reset()).toBe(true);
    expect((await service.autoStart())?.id).toBe('products');
  });
});

describe('routeMatches', () => {
  it('compares the path and only the query parameters the route names', () => {
    expect(routeMatches('/orders', '/orders')).toBe(true);
    expect(routeMatches('/orders', '/orders', '?tab=x')).toBe(true);
    expect(routeMatches('/orders', '/listings')).toBe(false);
    expect(routeMatches('/settings?section=tax', '/settings', '?section=tax&tab=a')).toBe(true);
    expect(routeMatches('/settings?section=tax', '/settings', '?section=fees')).toBe(false);
    expect(routeMatches('/settings?section=tax', '/settings')).toBe(false);
  });
});
