/* The machine running Metro, as the phone sees it. A development phone (Expo Go or a development build) loads its JS
   from Metro, so the host in that address is the dev machine: the right place for the local stack too. Without this,
   a phone that started without EXPO_PUBLIC_DEV_HOST asks "localhost", which is the phone itself ("could not reach
   localhost:8108"). Undefined on the web, on a simulator that really is localhost, and in release builds. */
import Constants from 'expo-constants';

export function metroHost(): string | undefined {
  const uri = Constants.expoConfig?.hostUri ?? Constants.expoGoConfig?.debuggerHost;
  const host = uri?.split(':')[0]?.trim();
  return host && host !== 'localhost' && host !== '127.0.0.1' ? host : undefined;
}
