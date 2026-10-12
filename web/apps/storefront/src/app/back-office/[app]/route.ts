import { notFound, redirect } from 'next/navigation';
import { type NextRequest } from 'next/server';

import { backOfficeUrl } from '@/lib/back-office';
import { sellerCenterUrl, staffConsoleUrl } from '@/lib/config';

export const dynamic = 'force-dynamic';

/** Account menu → Seller Center, Provider Hub or the staff console at this environment's configured URL, signed in. */
export async function GET(request: NextRequest, { params }: { params: Promise<{ app: string }> }): Promise<never> {
  const { app } = await params;
  const url = backOfficeUrl(app, request.nextUrl.searchParams.get('state'), {
    sellerCenterUrl: sellerCenterUrl(),
    staffConsoleUrl: staffConsoleUrl()
  });
  if (!url) notFound();
  redirect(url);
}
