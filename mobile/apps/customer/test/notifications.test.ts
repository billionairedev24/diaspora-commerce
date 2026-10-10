import { describe, expect, it } from 'vitest';

import { DEFAULT_CATEGORY_PREFS, pathFromDeepLink, routeForNotification, shouldDisplay } from '../src/domain/notifications';

describe('notification deep links', () => {
  it('routes taps to the right screen', () => {
    expect(routeForNotification({ category: 'deliveries', orderNumber: 'AT-1049-2283' })).toBe('/orders/AT-1049-2283');
    expect(routeForNotification({ category: 'orders' })).toBe('/orders');
    expect(routeForNotification({ category: 'bookings', bookingId: 'b1' })).toBe('/bookings/b1');
    expect(routeForNotification({ category: 'restock', atin: 'AT12345678' })).toBe('/product/AT12345678');
    expect(routeForNotification({ url: '/cart' })).toBe('/cart');
    expect(routeForNotification({ url: 'https://evil.example' })).toBe('/inbox');
    expect(routeForNotification({})).toBe('/inbox');
  });

  it('respects category preferences', () => {
    const server = { push: true, marketingOptIn: false };
    expect(shouldDisplay('orders', server, DEFAULT_CATEGORY_PREFS)).toBe(true);
    expect(shouldDisplay('deals', server, { ...DEFAULT_CATEGORY_PREFS, deals: true })).toBe(false);
    expect(shouldDisplay('deals', { push: true, marketingOptIn: true }, { ...DEFAULT_CATEGORY_PREFS, deals: true })).toBe(true);
    expect(shouldDisplay('restock', server, { ...DEFAULT_CATEGORY_PREFS, restock: false })).toBe(false);
    expect(shouldDisplay('orders', { push: false, marketingOptIn: true }, DEFAULT_CATEGORY_PREFS)).toBe(false);
  });

  it('parses app deep links', () => {
    expect(pathFromDeepLink('com.afrotransact.customer://orders/AT-1')).toBe('/orders/AT-1');
    const web = 'https://shop.example.test';
    expect(pathFromDeepLink('https://shop.example.test/app/bookings/b1?utm=x', web)).toBe('/bookings/b1');
    expect(pathFromDeepLink('https://shop.example.test/app/bookings/b1', `${web}/`)).toBe('/bookings/b1');
    expect(pathFromDeepLink('https://shop.example.test/app/bookings/b1')).toBeNull();
    expect(pathFromDeepLink('https://other.example.test/app/orders', web)).toBeNull();
    expect(pathFromDeepLink('com.afrotransact.customer://admin/x')).toBe('/');
    expect(pathFromDeepLink('https://other.example/app/orders')).toBeNull();
  });
});
