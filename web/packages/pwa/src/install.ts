/**
 * Install affordance logic (pure, so it is unit-tested without a browser).
 *
 * Chromium browsers (Android, desktop) fire `beforeinstallprompt`, which the app keeps and replays from a quiet "Install"
 * entry. Safari on iPhone and iPad has no prompt: installing is Share > Add to Home Screen, so the entry shows those steps.
 */

export interface BeforeInstallPromptEvent extends Event {
  prompt: () => Promise<void>;
  userChoice: Promise<{ outcome: 'accepted' | 'dismissed' }>;
}

export type InstallMode = 'prompt' | 'ios' | 'none';

export interface InstallEnv {
  /** Running as the installed app already (display-mode standalone, or iOS `navigator.standalone`). */
  standalone: boolean;
  userAgent: string;
  /** iPadOS 13+ reports a Mac user agent; touch points tell it apart. */
  maxTouchPoints: number;
  platform: string;
  /** The browser handed over an install prompt. */
  hasPrompt: boolean;
}

export function isIosDevice(env: Pick<InstallEnv, 'userAgent' | 'maxTouchPoints' | 'platform'>): boolean {
  if (/iPad|iPhone|iPod/.test(env.userAgent)) return true;
  return env.platform === 'MacIntel' && env.maxTouchPoints > 1;
}

/** Which install entry, if any, to show. Installed apps and unsupported browsers show nothing. */
export function installMode(env: InstallEnv): InstallMode {
  if (env.standalone) return 'none';
  if (env.hasPrompt) return 'prompt';
  if (isIosDevice(env)) return 'ios';
  return 'none';
}
