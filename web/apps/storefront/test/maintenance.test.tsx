import { act, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { maintenanceProps } from '@/components/shell/maintenanceProps';
import { MaintenanceScreen } from '@/components/shell/MaintenanceScreen';
import { tEn } from '@/i18n/t';
import { maintenanceNow } from '@/lib/maintenance';

vi.mock('server-only', () => ({}));

const problem503 = () =>
  new Response(JSON.stringify({ type: 'about:blank', title: 'Back soon', status: 503, code: 'MAINTENANCE', detail: 'Updating the catalogue' }), {
    status: 503,
    headers: { 'content-type': 'application/problem+json', 'retry-after': '300' }
  });
const json = (body: unknown) => new Response(JSON.stringify(body), { status: 200, headers: { 'content-type': 'application/json' } });

describe('maintenance (503 MAINTENANCE from the BFF)', () => {
  const reload = vi.fn();
  const realLocation = window.location;
  beforeEach(() => {
    delete process.env.NEXT_PUBLIC_API_MOCKS;
    delete process.env.API_MOCKS;
    Object.defineProperty(window, 'location', { configurable: true, value: { ...realLocation, reload } });
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.useRealTimers();
    reload.mockReset();
    Object.defineProperty(window, 'location', { configurable: true, value: realLocation });
  });

  it('asks the BFF for its status and returns it while it is on, for shoppers and for everyone', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => json({ active: true, scope: 'SHOPPERS', title: 'Back soon', message: 'Updating the catalogue' })));
    expect(await maintenanceNow()).toMatchObject({ active: true, scope: 'SHOPPERS', title: 'Back soon' });
    vi.stubGlobal('fetch', vi.fn(async () => json({ active: true, scope: 'ALL' })));
    expect(await maintenanceNow()).toMatchObject({ scope: 'ALL' });
    vi.stubGlobal('fetch', vi.fn(async () => json({ active: false })));
    expect(await maintenanceNow()).toBeNull();
  });

  it('serves the page normally when the status cannot be read, including a 503 problem answer', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => problem503()));
    expect(await maintenanceNow()).toBeNull();
    vi.stubGlobal('fetch', vi.fn(async () => Promise.reject(new Error('down'))));
    expect(await maintenanceNow()).toBeNull();
  });

  it('shows the staff-written title, message and expected time on the maintenance page', async () => {
    const later = new Date(Date.now() + 3_600_000).toISOString();
    const props = maintenanceProps({ active: true, scope: 'ALL', title: 'Back soon', message: 'Updating the catalogue', expectedBackAt: later }, 'en', tEn);
    render(<MaintenanceScreen {...props} />);
    expect(screen.getByRole('heading', { name: 'Back soon' })).toBeInTheDocument();
    expect(screen.getByText('Updating the catalogue')).toBeInTheDocument();
    expect(await screen.findByText(/We expect to be back around \d{1,2}:\d{2}/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Check again' })).toBeInTheDocument();
  });

  it('falls back to the default title and the "still working" line when staff gave neither title nor a future time', () => {
    const past = new Date(Date.now() - 60_000).toISOString();
    const props = maintenanceProps({ active: true, scope: 'ALL', expectedBackAt: past }, 'en', tEn);
    expect(props.expectedBackAt).toBeUndefined();
    render(<MaintenanceScreen {...props} />);
    expect(screen.getByRole('heading', { name: 'We’ll be right back' })).toBeInTheDocument();
    expect(screen.getByText('We’re still working on it. This page updates by itself.')).toBeInTheDocument();
  });

  it('stays up while the BFF still answers 503 MAINTENANCE and reloads once maintenance has ended', async () => {
    const props = maintenanceProps({ active: true, scope: 'ALL', title: 'Back soon', message: 'm' }, 'en', tEn);
    render(<MaintenanceScreen {...props} />);
    const check = screen.getByRole('button', { name: 'Check again' });

    vi.stubGlobal('fetch', vi.fn(async () => problem503()));
    await act(async () => fireEvent.click(check));
    vi.stubGlobal('fetch', vi.fn(async () => json({ active: true, scope: 'ALL' })));
    await act(async () => fireEvent.click(check));
    expect(reload).not.toHaveBeenCalled();
    expect(screen.getByRole('heading', { name: 'Back soon' })).toBeInTheDocument();

    vi.stubGlobal('fetch', vi.fn(async () => json({ active: false })));
    await act(async () => fireEvent.click(check));
    expect(reload).toHaveBeenCalledTimes(1);
  });

  it('polls by itself every 20 seconds', async () => {
    vi.useFakeTimers();
    const fetchMock = vi.fn(async () => json({ active: false }));
    vi.stubGlobal('fetch', fetchMock);
    render(<MaintenanceScreen {...maintenanceProps({ active: true, scope: 'ALL', title: 't', message: 'm' }, 'en', tEn)} />);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(20_000);
    });
    expect(fetchMock).toHaveBeenCalledWith('/api/bff/v1/maintenance', { cache: 'no-store' });
    expect(reload).toHaveBeenCalled();
  });
});
