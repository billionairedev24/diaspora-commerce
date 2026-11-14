'use client';

import { Component, type ErrorInfo, type ReactNode, useEffect } from 'react';

import { getTelemetry } from './index';

/** The component that threw: the first `at Name (` frame of the stack that is not React internals or a hook shim. */
export function componentFromStack(stack: string | undefined): string | undefined {
  for (const line of (stack ?? '').split('\n')) {
    const m = /^\s*at (?:async )?([A-Z][A-Za-z0-9_$]*)(?: \[as [^\]]+\])? \(/.exec(line);
    if (m && m[1] !== 'Object') return m[1];
  }
  return undefined;
}

/**
 * Reports an error once (Next.js error.tsx / global-error.tsx receive it as a prop). Tags the route and the component
 * that threw and sends the top of the stack, so a crash that lands on the error page is findable in telemetry.
 */
export function useReportError(error: unknown, tags?: Record<string, string>): void {
  useEffect(() => {
    if (!error) return;
    const err = error instanceof Error ? error : undefined;
    const route = typeof window === 'undefined' ? undefined : window.location.pathname;
    const component = componentFromStack(err?.stack);
    getTelemetry().captureException(error, {
      tags: { boundary: 'route', ...(route ? { route } : {}), ...(component ? { component } : {}), ...tags },
      extra: { digest: (err as { digest?: string } | undefined)?.digest ?? '', stack: (err?.stack ?? '').split('\n').slice(0, 8).join('\n') }
    });
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
