import { beforeEach, describe, expect, it, vi } from 'vitest';

/* Expo Go guards: the execution environment from expo-constants decides which native features the app uses. */
const native = vi.hoisted(() => ({ executionEnvironment: 'storeClient' as string | undefined, os: 'android' }));

vi.mock('expo-constants', () => ({
  default: { get executionEnvironment() { return native.executionEnvironment; } },
  ExecutionEnvironment: { Bare: 'bare', Standalone: 'standalone', StoreClient: 'storeClient' }
}));
vi.mock('react-native', () => ({ Platform: { get OS() { return native.os; } } }));
vi.mock('expo-linking', () => ({ createURL: (path: string) => `exp://192.168.1.50:8081${path ? `/--/${path}` : ''}` }));
vi.mock('expo-notifications', () => ({ getDevicePushTokenAsync: vi.fn(), addPushTokenListener: vi.fn() }));

async function load() {
  vi.resetModules();
  return import('@/platform/runtime');
}

describe('runtime capabilities', () => {
  beforeEach(() => {
    native.executionEnvironment = 'storeClient';
    native.os = 'android';
  });

  it('treats the store client as Expo Go: no remote push, no wallets, local notifications kept', async () => {
    const { capabilities, isExpoGo } = await load();
    expect(isExpoGo()).toBe(true);
    expect(capabilities).toEqual({ runtime: 'expo-go', remotePush: false, localNotifications: true, biometrics: true, walletPay: false });
  });

  it('has no Face ID in Expo Go on iOS (the passcode unlocks instead)', async () => {
    native.os = 'ios';
    const { capabilities } = await load();
    expect(capabilities.biometrics).toBe(false);
    expect(capabilities.remotePush).toBe(false);
  });

  it.each(['bare', 'standalone'])('enables everything in our own build (%s)', async (env) => {
    native.executionEnvironment = env;
    native.os = 'ios';
    const { capabilities, isExpoGo } = await load();
    expect(isExpoGo()).toBe(false);
    expect(capabilities).toEqual({ runtime: 'native-build', remotePush: true, localNotifications: true, biometrics: true, walletPay: true });
  });

  it('has no notifications at all on the web preview', async () => {
    native.os = 'web';
    native.executionEnvironment = undefined;
    const { capabilities } = await load();
    expect(capabilities).toMatchObject({ runtime: 'web', remotePush: false, localNotifications: false, walletPay: false });
  });

  it('never loads expo-notifications on the web', async () => {
    native.os = 'web';
    vi.resetModules();
    const { loadNotifications } = await import('@/push/notificationsModule');
    await expect(loadNotifications()).resolves.toBeNull();
  });

  it('loads expo-notifications lazily in Expo Go (local notifications only)', async () => {
    vi.resetModules();
    const { loadNotifications } = await import('@/push/notificationsModule');
    const m = await loadNotifications();
    expect(m).not.toBeNull();
    expect(m?.getDevicePushTokenAsync).not.toHaveBeenCalled();
    expect(m?.addPushTokenListener).not.toHaveBeenCalled();
  });

  it('returns Stripe hand-offs to Expo Go via exp:// links, to our build via its scheme', async () => {
    vi.resetModules();
    let links = await import('@/payments/stripeLinks');
    expect(links.stripeReturnUrl()).toBe('exp://192.168.1.50:8081/--/stripe-redirect');
    expect(links.walletPayAvailable).toBe(false);
    native.executionEnvironment = 'bare';
    vi.resetModules();
    links = await import('@/payments/stripeLinks');
    expect(links.stripeUrlScheme()).toBe('com.afrotransact.customer');
    expect(links.stripeReturnUrl()).toBe('com.afrotransact.customer://stripe-redirect');
    expect(links.walletPayAvailable).toBe(true);
  });
});
