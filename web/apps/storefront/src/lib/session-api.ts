'use client';

import { createSessionApi, type SessionApi } from '@afrotransact/api-client';

import { BFF_BASE_PATH } from './config';

let client: SessionApi | null = null;

/** Browser client of the sign-out timer (policy, activity, why signed out): same-origin /api/bff, session cookie included. */
export function sessionApi(): SessionApi {
  client ??= createSessionApi({ baseUrl: BFF_BASE_PATH });
  return client;
}
