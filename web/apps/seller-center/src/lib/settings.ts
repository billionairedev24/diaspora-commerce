'use client';

import { createSettingsApiClient, type SettingsApiClient } from '@afrotransact/api-client';

import { BFF_BASE_PATH } from './config';

let client: SettingsApiClient | null = null;

/** Browser client for My account (/v1/account/…) through the same-origin BFF proxy. */
export function settingsApi(): SettingsApiClient {
  client ??= createSettingsApiClient({ baseUrl: BFF_BASE_PATH });
  return client;
}
