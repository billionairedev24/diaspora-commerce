import { render } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import { HandoffLink } from '@/components/shell/HandoffLink';
import { isHandoffHref } from '@/lib/sell/handoff';

// next/link marks itself so a test can tell it from a plain anchor.
vi.mock('next/link', () => ({
  default: ({ href, children, ...rest }: { href: string; children?: React.ReactNode }) => (
    <a href={href} data-next-link="true" {...rest}>
      {children}
    </a>
  )
}));

describe('/sell and /provide hand over to Seller Center (a cross-origin redirect): never a Next <Link>', () => {
  it('recognises the hand-over addresses only', () => {
    for (const href of ['/sell', '/sell?x=1', '/sell/', '/provide', '/provide#top']) expect(isHandoffHref(href), href).toBe(true);
    for (const href of ['/selling-tips', '/sellers/x', '/search?q=sell', '/p/B0123', '/membership', 'https://example.com/sell']) expect(isHandoffHref(href), href).toBe(false);
  });

  it('renders a plain anchor (no RSC prefetch, no client navigation) for /sell and /provide', () => {
    const { container } = render(
      <>
        <HandoffLink href="/sell" prefetch className="a">
          Start selling
        </HandoffLink>
        <HandoffLink href="/provide">Offer services</HandoffLink>
      </>
    );
    const anchors = [...container.querySelectorAll('a')];
    expect(anchors.map((a) => a.getAttribute('href'))).toEqual(['/sell', '/provide']);
    expect(anchors.some((a) => a.hasAttribute('data-next-link'))).toBe(false);
    expect(anchors[0]?.getAttribute('class')).toBe('a');
  });

  it('keeps ordinary addresses on <Link>', () => {
    const { container } = render(<HandoffLink href="/search?q=rice">Rice</HandoffLink>);
    expect(container.querySelector('a')?.getAttribute('data-next-link')).toBe('true');
  });
});
