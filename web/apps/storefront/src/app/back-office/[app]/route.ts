import { notFound, redirect } from 'next/navigation';
import { type NextRequest } from 'next/server';

import { serverApi } from '@/lib/api/server';
import { backOfficeUrl, liveState } from '@/lib/back-office';
import { sellerCenterUrl, staffConsoleUrl } from '@/lib/config';

export const dynamic = 'force-dynamic';

/**
 * Account menu → Seller Center, Provider Hub or the staff console at this environment's configured URL, signed in.
 * The person's back offices are read again here, so an approved seller always lands on the dashboard (a menu rendered
 * while the application was in progress says ?state=application, which would open onboarding).
 */
export async function GET(request: NextRequest, { params }: { params: Promise<{ app: string }> }): Promise<never> {
  const { app } = await params;
  const me = await serverApi()
    .then((api) => api.getMe())
    .catch(() => null);
  const url = backOfficeUrl(app, liveState(app, request.nextUrl.searchParams.get('state'), me?.user?.backOffices), {
    sellerCenterUrl: sellerCenterUrl(),
    staffConsoleUrl: staffConsoleUrl()
  });
  if (!url) notFound();
  redirect(url);
}
