'use client';

import { type HomeTracking } from '@afrotransact/api-client';
import { type MouseEvent, type ReactNode, useEffect, useRef } from 'react';

import { useConsentOptional } from '@/lib/consent/ConsentProvider';
import { rememberModuleClick, sendModuleEvent, targetOf } from '@/lib/home/moduleEvents';

/** How much of the module must be on screen, and for how long, to count one impression. */
const VISIBLE_RATIO = 0.5;
const VISIBLE_MS = 500;

/**
 * Wraps one organic home module: counts one impression per page view (half of it visible for half a second) and each click on
 * a link inside it, with the module key, position, variant and what was clicked. Only with Analytics consent; without it the
 * wrapper is a plain element. Tall modules count when half of the screen's height is theirs.
 */
export function ModuleFrame({ tracking, type, children }: { tracking?: HomeTracking; type: string; children: ReactNode }) {
  const consent = useConsentOptional();
  const on = !!tracking && !!consent?.allows('analytics');
  const ref = useRef<HTMLDivElement>(null);
  const module = tracking?.module;
  const position = tracking?.position;
  const variant = tracking?.variant;

  useEffect(() => {
    const el = ref.current;
    if (!on || !el || module === undefined || position === undefined || variant === undefined || typeof IntersectionObserver === 'undefined') return;
    const t: HomeTracking = { module, position, variant };
    let timer: ReturnType<typeof setTimeout> | undefined;
    let done = false;
    const io = new IntersectionObserver(
      (entries) => {
        for (const e of entries) {
          const need = Math.min(e.boundingClientRect.height, window.innerHeight) * VISIBLE_RATIO;
          const visible = e.isIntersecting && e.intersectionRect.height >= need - 1;
          if (visible && !timer && !done) {
            timer = setTimeout(() => {
              done = true;
              io.disconnect();
              sendModuleEvent(t, 'impression');
            }, VISIBLE_MS);
          } else if (!visible && timer) {
            clearTimeout(timer);
            timer = undefined;
          }
        }
      },
      { threshold: [0, 0.25, 0.5, 0.75, 1] }
    );
    io.observe(el);
    return () => {
      io.disconnect();
      if (timer) clearTimeout(timer);
    };
  }, [on, module, position, variant]);

  const onClick = (e: MouseEvent<HTMLDivElement>) => {
    if (!on || !tracking) return;
    const a = (e.target as Element).closest?.('a[href]');
    if (!a) return;
    sendModuleEvent(tracking, 'click', targetOf(a.getAttribute('href') ?? ''));
    rememberModuleClick(tracking, location.protocol === 'https:');
  };

  return (
    <div ref={ref} data-block={type} data-module={module} onClickCapture={onClick}>
      {children}
    </div>
  );
}
