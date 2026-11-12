/* Web app manifest (/manifest.webmanifest): served with the manifest media type; the same for everyone. */
import { MANIFEST_HEADERS } from '@afrotransact/pwa';

import { storefrontManifest } from '@/lib/pwa';

export function GET(): Response {
  return new Response(JSON.stringify(storefrontManifest()), { headers: MANIFEST_HEADERS });
}
