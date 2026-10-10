'use client';

import { useReportWebVitals } from 'next/web-vitals';

import { getTelemetry } from '../index';

/** Reports Core Web Vitals (LCP, INP, CLS, FCP, TTFB) to telemetry; render once in the root layout. */
export function WebVitalsReporter(): null {
  useReportWebVitals((m) => {
    getTelemetry().reportWebVital({ name: m.name, value: m.value, rating: m.rating, id: m.id, navigationType: m.navigationType });
  });
  return null;
}
