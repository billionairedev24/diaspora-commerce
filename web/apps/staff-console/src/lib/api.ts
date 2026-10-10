'use client';

import { createStaffApiClient, type StaffApiClient } from '@afrotransact/api-client';

import { BFF_BASE_PATH } from './config';

let client: StaffApiClient | null = null;

/** Browser client for the staff BFF routes: same-origin /api/bff (session cookie included). */
export function staffApi(): StaffApiClient {
  client ??= createStaffApiClient({ baseUrl: BFF_BASE_PATH });
  return client;
}
