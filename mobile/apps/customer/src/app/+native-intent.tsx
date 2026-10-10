/* Deep-link routing (S168): custom-scheme and universal links (https://www.afrotransact.com/app/…) map to in-app
   routes; anything unknown opens Home. Notification taps are routed by PushProvider. */
import { pathFromDeepLink } from '@/domain/notifications';

export function redirectSystemPath({ path }: { path: string; initial: boolean }): string {
  if (path === '/app' || path.startsWith('/app/')) return path.slice(4) || '/';
  if (path.startsWith('/')) return path;
  return pathFromDeepLink(path) ?? '/';
}
