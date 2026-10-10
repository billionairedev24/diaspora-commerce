/* Secrets (tokens, passcode hash) live in the Keychain / Android Keystore via expo-secure-store. */
import * as SecureStore from 'expo-secure-store';

const OPTS: SecureStore.SecureStoreOptions = { keychainAccessible: SecureStore.WHEN_UNLOCKED_THIS_DEVICE_ONLY };

export const secureStorage = {
  get: (key: string): Promise<string | null> => SecureStore.getItemAsync(key, OPTS),
  set: (key: string, value: string): Promise<void> => SecureStore.setItemAsync(key, value, OPTS),
  remove: (key: string): Promise<void> => SecureStore.deleteItemAsync(key, OPTS)
};
