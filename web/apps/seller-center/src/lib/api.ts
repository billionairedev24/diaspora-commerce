'use client';

import { createSellerApiClient, type SellerApiClient } from '@afrotransact/api-client';

import { BFF_BASE_PATH } from './config';

let client: SellerApiClient | null = null;

/** Browser client for the seller BFF routes: same-origin /api/bff (session cookie included). */
export function sellerApi(): SellerApiClient {
  client ??= createSellerApiClient({ baseUrl: BFF_BASE_PATH });
  return client;
}
