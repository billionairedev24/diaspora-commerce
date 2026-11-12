/** Web app manifest (https://www.w3.org/TR/appmanifest/), the subset the web apps use. */
export interface PwaIcon {
  src: string;
  sizes: string;
  type: 'image/png' | 'image/svg+xml';
  purpose?: 'any' | 'maskable' | 'monochrome';
}

export interface PwaManifest {
  id: string;
  name: string;
  short_name: string;
  description?: string;
  start_url: string;
  scope: string;
  display: 'standalone';
  theme_color: string;
  background_color: string;
  icons: PwaIcon[];
}

export interface PwaManifestInput {
  name: string;
  shortName: string;
  description?: string;
  /** Where the installed app opens; carries a `source=pwa` marker so installed use can be told apart. */
  startUrl: string;
  /** The installed app covers this path prefix; links outside it open in the browser. */
  scope: string;
  /** Browser chrome colour: pass a resolved design token value (--at-color-header-bg), never a literal. */
  themeColor: string;
  /** Splash screen colour (--at-color-canvas). */
  backgroundColor: string;
  icons: PwaIcon[];
}

/** Path of the manifest route; the layout links it. */
export const MANIFEST_PATH = '/manifest.webmanifest';

/** The manifest's media type (not application/json). */
export const MANIFEST_CONTENT_TYPE = 'application/manifest+json; charset=utf-8';

/** Builds the manifest. `id` is the scope root so the app identity survives a later change of start_url. */
export function buildManifest(input: PwaManifestInput): PwaManifest {
  return {
    id: input.scope,
    name: input.name,
    short_name: input.shortName,
    ...(input.description ? { description: input.description } : {}),
    start_url: input.startUrl,
    scope: input.scope,
    display: 'standalone',
    theme_color: input.themeColor,
    background_color: input.backgroundColor,
    icons: input.icons
  };
}

/** Headers of the manifest response: revalidated hourly so an icon or colour change reaches installed apps within a day. */
export const MANIFEST_HEADERS: Readonly<Record<string, string>> = {
  'Content-Type': MANIFEST_CONTENT_TYPE,
  'Cache-Control': 'public, max-age=3600'
};
