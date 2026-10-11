/** Next.js `images.remotePatterns` for the media service's public URL (see next.config.ts). */

export interface MediaRemotePattern {
  protocol: 'http' | 'https';
  hostname: string;
  port: string;
  pathname: string;
}

export const LOCAL_MEDIA_PUBLIC_URL = 'http://localhost:9000/media';

export function mediaRemotePatterns(urls: (string | undefined)[]): MediaRemotePattern[] {
  const patterns: MediaRemotePattern[] = [];
  for (const raw of urls) {
    if (!raw) continue;
    let url: URL;
    try {
      url = new URL(raw);
    } catch {
      continue;
    }
    if (url.protocol !== 'http:' && url.protocol !== 'https:') continue;
    const pattern = {
      protocol: url.protocol === 'http:' ? ('http' as const) : ('https' as const),
      hostname: url.hostname,
      port: url.port,
      pathname: `${url.pathname.replace(/\/+$/, '')}/**`
    };
    if (!patterns.some((p) => p.hostname === pattern.hostname && p.port === pattern.port && p.pathname === pattern.pathname)) patterns.push(pattern);
  }
  return patterns;
}
