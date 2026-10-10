/* GPS for event positions and photo stamps (expo-location). Never blocks an action: falls back to null. */
import * as Location from 'expo-location';

export async function ensureLocationPermission(): Promise<boolean> {
  try {
    const cur = await Location.getForegroundPermissionsAsync();
    return cur.granted || (await Location.requestForegroundPermissionsAsync()).granted;
  } catch {
    return false;
  }
}

export interface Fix {
  lat: number;
  lon: number;
  accuracyM: number | null;
}

function timeout<T>(p: Promise<T>, ms: number): Promise<T | null> {
  return Promise.race([p, new Promise<null>((r) => setTimeout(() => r(null), ms))]);
}

export async function currentFix(fresh = false): Promise<Fix | null> {
  try {
    if (!(await Location.getForegroundPermissionsAsync()).granted) return null;
    const last = fresh ? null : await Location.getLastKnownPositionAsync({ maxAge: 60_000, requiredAccuracy: 100 });
    const pos = last ?? (await timeout(Location.getCurrentPositionAsync({ accuracy: Location.Accuracy.High }), 6000));
    return pos ? { lat: pos.coords.latitude, lon: pos.coords.longitude, accuracyM: pos.coords.accuracy ?? null } : null;
  } catch {
    return null;
  }
}

/** S255: foreground-only position watch for shift pings. It never prompts (permission is asked at sign-in) and never
    requests background location; resolves null when foreground permission is not granted. */
export async function watchForegroundFixes(onFix: (fix: Fix & { at: number }) => void): Promise<(() => void) | null> {
  try {
    if (!(await Location.getForegroundPermissionsAsync()).granted) return null;
    const sub = await Location.watchPositionAsync({ accuracy: Location.Accuracy.Balanced, timeInterval: 15_000, distanceInterval: 50 }, (pos) =>
      onFix({ lat: pos.coords.latitude, lon: pos.coords.longitude, accuracyM: pos.coords.accuracy ?? null, at: pos.timestamp || Date.now() })
    );
    return () => sub.remove();
  } catch {
    return null;
  }
}
