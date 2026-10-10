'use client';

import { type ApiClient, createApiClient } from '@afrotransact/api-client';

import { BFF_BASE_PATH } from '../config';

let client: ApiClient | null = null;

/** Browser API client: same-origin /api/bff (cookies included). */
export function browserApi(): ApiClient {
  client ??= createApiClient({ baseUrl: BFF_BASE_PATH });
  return client;
}
