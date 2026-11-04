import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import { Alert, AlertAction, alertRole, type AlertVariant, PageStack, StatStrip } from '../src';

const css = (name: string): string => readFileSync(resolve(__dirname, name), 'utf8');
const alertCss = css('../src/feedback/Alert.module.css');
const statCss = css('../src/layout/StatStrip.module.css');

const VARIANTS: AlertVariant[] = ['info', 'success', 'warning', 'danger', 'neutral'];

describe('Alert', () => {
  it.each(VARIANTS)('%s: renders a title then the message, with a decorative icon', (variant) => {
    const { container } = render(<Alert variant={variant} title="Heads up.">Something to know.</Alert>);
    const el = container.firstElementChild as HTMLElement;
    expect(el).toHaveAttribute('data-variant', variant);
    expect(el).toHaveClass(variant);
    expect(el.querySelector('strong')).toHaveTextContent('Heads up.');
    expect(el).toHaveTextContent('Heads up. Something to know.');
    expect(el.querySelector('svg')).toHaveAttribute('aria-hidden', 'true');
  });

  it('is a polite status for info, success, neutral and plain warnings; an alert for danger and urgent warnings', () => {
    expect(alertRole('info')).toBe('status');
    expect(alertRole('success')).toBe('status');
    expect(alertRole('neutral')).toBe('status');
    expect(alertRole('warning')).toBe('status');
    expect(alertRole('warning', true)).toBe('alert');
    expect(alertRole('danger')).toBe('alert');
    const { rerender } = render(<Alert variant="warning">Low stock</Alert>);
    expect(screen.getByRole('status')).toHaveTextContent('Low stock');
    rerender(<Alert variant="warning" urgent>Low stock</Alert>);
    expect(screen.getByRole('alert')).toBeInTheDocument();
    rerender(<Alert variant="danger">Failed</Alert>);
    expect(screen.getByRole('alert')).toHaveTextContent('Failed');
    rerender(<Alert variant="info" role="alert">Forced</Alert>);
    expect(screen.getByRole('alert')).toHaveTextContent('Forced');
  });

  it('shows the inline action as a link-style button that is keyboard reachable and fires', async () => {
    const onClick = vi.fn();
    render(<Alert variant="warning" action={<AlertAction onClick={onClick}>Create inbound plan</AlertAction>}>1 SKU is low.</Alert>);
    await userEvent.tab();
    const action = screen.getByRole('button', { name: 'Create inbound plan' });
    expect(action).toHaveFocus();
    expect(action).toHaveClass('link');
    expect(action).toHaveAttribute('type', 'button');
    await userEvent.keyboard('{Enter}');
    expect(onClick).toHaveBeenCalledTimes(1);
  });

  it('renders the action as a link when it has an href', () => {
    render(<Alert action={<AlertAction href="/inventory?tab=send">Open</AlertAction>}>Go.</Alert>);
    expect(screen.getByRole('link', { name: 'Open' })).toHaveAttribute('href', '/inventory?tab=send');
  });

  it('dismisses through a labelled button, only when onDismiss is given', async () => {
    const onDismiss = vi.fn();
    const { rerender } = render(<Alert>Plain</Alert>);
    expect(screen.queryByRole('button')).toBeNull();
    rerender(<Alert onDismiss={onDismiss} dismissLabel="Fermer">Plain</Alert>);
    await userEvent.click(screen.getByRole('button', { name: 'Fermer' }));
    expect(onDismiss).toHaveBeenCalledTimes(1);
    rerender(<Alert onDismiss={onDismiss}>Plain</Alert>);
    expect(screen.getByRole('button', { name: 'Dismiss' })).toBeInTheDocument();
  });

  it('fits its content up to a readable maximum by default, and fills only with `fill`', () => {
    const { container, rerender } = render(<Alert>Short</Alert>);
    const el = container.firstElementChild as HTMLElement;
    expect(el).not.toHaveClass('fill');
    expect(el).not.toHaveAttribute('data-fill');
    expect(alertCss).toMatch(/\.alert\s*\{[^}]*inline-size:\s*fit-content/);
    expect(alertCss).toMatch(/\.alert\s*\{[^}]*max-inline-size:\s*min\(100%,\s*var\(--at-alert-max,\s*720px\)\)/);
    rerender(<Alert fill>Short</Alert>);
    expect((container.firstElementChild as HTMLElement)).toHaveClass('fill');
    expect(alertCss).toMatch(/\.fill\s*\{[^}]*inline-size:\s*100%/);
  });

  it('is RTL safe: logical properties only, nothing physical left/right', () => {
    const { container } = render(
      <div dir="rtl">
        <Alert title="تنبيه" onDismiss={() => undefined} action={<AlertAction>إجراء</AlertAction>}>رسالة</Alert>
      </div>
    );
    expect(container.querySelector('[role="status"]')).toHaveTextContent('تنبيه رسالة');
    expect(alertCss).toContain('border-inline-start');
    expect(alertCss).not.toMatch(/(?:^|[\s;{])(?:margin|padding|border)-(?:left|right)\b|(?:^|[\s;{])(?:left|right)\s*:/m);
  });

  it('uses only alert and design tokens: no raw colours', () => {
    expect(alertCss).not.toMatch(/#[0-9a-f]{3,8}\b/i);
    for (const v of VARIANTS) expect(alertCss).toContain(`--at-color-alert-${v}-text`);
  });
});

describe('StatStrip', () => {
  it('lists label, large value and optional sub-text with its tone', () => {
    render(<StatStrip label="Stock" items={[{ label: 'Available', value: 12 }, { label: 'Revenue', value: '$1,200', sub: '+4% vs last week', tone: 'positive' }]} />);
    const list = screen.getByRole('list', { name: 'Stock' });
    expect(list.querySelectorAll('li')).toHaveLength(2);
    expect(screen.getByText('Available')).toHaveClass('label');
    expect(screen.getByText('12').tagName).toBe('STRONG');
    expect(screen.getByText('+4% vs last week')).toHaveClass('positive');
  });

  it('is flat: no borders around each stat, no shadows, no card background; numbers are tabular', () => {
    expect(statCss).toMatch(/\.value\s*\{[^}]*font-variant-numeric:\s*tabular-nums/);
    expect(statCss).not.toMatch(/box-shadow|background:\s*var\(--at-color-surface\)|border-radius/);
    expect(statCss).toMatch(/\.stat\s*\{[^}]*border-inline-start:\s*1px solid/);
    expect(statCss).not.toMatch(/border(?:-block)?(?:-(?:top|bottom))?:\s*1px/);
  });

  it('wraps: two columns on phones, one flat row (wrapping when it must) from 640px', () => {
    expect(statCss).toMatch(/\.strip\s*\{[^}]*grid-template-columns:\s*repeat\(2,/);
    expect(statCss).toMatch(/@media \(min-width: 640px\)\s*\{[^}]*\.strip\s*\{[^}]*display:\s*flex;[^}]*flex-wrap:\s*wrap/);
  });

  it('turns a stat into a link or a button when asked, leaving the others plain', async () => {
    const onSelect = vi.fn();
    render(<StatStrip items={[{ label: 'Available', value: 1 }, { label: 'Low-stock SKUs', value: 3, onSelect }, { label: 'Orders', value: 9, href: '/orders' }]} />);
    expect(screen.getAllByRole('link')).toHaveLength(1);
    expect(screen.getByRole('link', { name: /Orders/ })).toHaveAttribute('href', '/orders');
    const button = screen.getByRole('button', { name: /Low-stock SKUs/ });
    await userEvent.click(button);
    expect(onSelect).toHaveBeenCalledTimes(1);
    expect(screen.getAllByRole('listitem')).toHaveLength(3);
  });

  it('is RTL safe (logical properties only)', () => {
    expect(statCss).not.toMatch(/(?:margin|padding|border)-(?:left|right)\b/);
  });
});

describe('PageStack', () => {
  it('spaces blocks from the page-rhythm tokens, with the tighter alert gap', () => {
    const { container } = render(
      <PageStack>
        <Alert>One</Alert>
        <StatStrip items={[{ label: 'A', value: 1 }]} />
      </PageStack>
    );
    expect(container.querySelector('[data-page-part="alert"]')).not.toBeNull();
    expect(container.querySelector('[data-page-part="stats"]')).not.toBeNull();
    const stackCss = css('../src/layout/PageStack.module.css');
    expect(stackCss).toContain('--at-page-gap-section');
    expect(stackCss).toContain("[data-page-part='alert'] + *");
    expect(stackCss).toContain('--at-page-gap-alert');
  });
});
