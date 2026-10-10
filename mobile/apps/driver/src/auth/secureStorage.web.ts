/* Web preview only (expo start --web / expo export): expo-secure-store has no web implementation, so the browser's
   sessionStorage stands in. Native builds use secureStorage.ts (Keychain / Keystore). */
function store(): Storage | null {
  try {
    return typeof sessionStorage === 'undefined' ? null : sessionStorage;
  } catch {
    return null;
  }
}

export const secureStorage = {
  get: async (key: string): Promise<string | null> => store()?.getItem(key) ?? null,
  set: async (key: string, value: string): Promise<void> => { store()?.setItem(key, value); },
  remove: async (key: string): Promise<void> => { store()?.removeItem(key); }
};
