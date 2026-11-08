/* Receives Content-Security-Policy violation reports (report-uri / report-to) and logs one compact, PII-free line each.
   Rate limited and size capped; see createCspReportHandler. */
import { createCspReportHandler } from '@afrotransact/ui/csp';

export const dynamic = 'force-dynamic';

const handle = createCspReportHandler('seller-center');

export async function POST(request: Request): Promise<Response> {
  return handle(request);
}
