'use client';

import { Component, type ErrorInfo, type ReactNode, useEffect } from 'react';

import { getTelemetry } from './index';

/** Reports an error once (Next.js error.tsx / global-error.tsx receive it as a prop). */
export function useReportError(error: unknown, tags?: Record<string, string>): void {
  useEffect(() => {
    if (error) getTelemetry().captureException(error, { tags: { boundary: 'route', ...tags } });
    // tags are static per boundary
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [error]);
}

interface BoundaryProps {
  /** Rendered after an error; `reset` re-mounts the children. */
  fallback: (reset: () => void) => ReactNode;
  /** Tag that names where the boundary sits (e.g. "lola", "checkout"). */
  scope?: string;
  children: ReactNode;
}

/** React error boundary that reports to telemetry (for widgets that must not take the page down). */
export class TelemetryErrorBoundary extends Component<BoundaryProps, { failed: boolean }> {
  override state = { failed: false };

  static getDerivedStateFromError(): { failed: boolean } {
    return { failed: true };
  }

  override componentDidCatch(error: Error, info: ErrorInfo): void {
    getTelemetry().captureException(error, { tags: { boundary: this.props.scope ?? 'component' }, extra: { componentStack: info.componentStack ?? '' } });
  }

  private readonly reset = () => this.setState({ failed: false });

  override render(): ReactNode {
    return this.state.failed ? this.props.fallback(this.reset) : this.props.children;
  }
}
