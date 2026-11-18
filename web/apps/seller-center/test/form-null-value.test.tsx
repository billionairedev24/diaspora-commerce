import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';

import { resetSellerDb } from '@afrotransact/api-client/mocks/seller';
import { TelemetryErrorBoundary } from '@afrotransact/telemetry/react';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { type ReactNode } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { LocationsView } from '@/components/settings/LocationsView';
import { PickupView } from '@/components/settings/PickupView';
import { ShippingView } from '@/components/settings/ShippingView';

import { asRole, mockApi } from './mock-api';
import { renderScreen } from './render';

/*
 * Regression: typing in the ship-from address form threw "Cannot read properties of null (reading 'value')" and took the
 * page down to the error boundary. The field handler read e.currentTarget.value inside the setState updater, which React
 * runs later (during render) when React has already cleared currentTarget.
 */
/** Anything thrown while typing would land on the app error boundary; this one records it like the real page would. */
function Guarded({ children }: { children: ReactNode }) {
  return <TelemetryErrorBoundary scope="test" fallback={() => <p data-testid="error-boundary">Something went wrong. Please try again.</p>}>{children}</TelemetryErrorBoundary>;
}

describe('settings forms survive typing', () => {
  let errors: ReturnType<typeof vi.spyOn>;
  beforeEach(() => {
    resetSellerDb(new Date());
    asRole(null);
    errors = vi.spyOn(console, 'error').mockImplementation(() => undefined);
  });
  afterEach(() => errors.mockRestore());
  const clean = () => {
    expect(screen.queryByTestId('error-boundary')).toBeNull();
    expect(errors).not.toHaveBeenCalled();
  };

  it('types into every field of the location form (name, address, ZIP, phone) without a single error', async () => {
    const user = userEvent.setup();
    await renderScreen(
      <Guarded>
        <LocationsView />
      </Guarded>
    );
    await screen.findAllByTestId('ship-from-address');
    await user.click(screen.getAllByRole('button', { name: 'Edit' })[0]!);
    const dialog = await screen.findByRole('dialog');
    for (const label of [/^Name of this location/, /^Contact name/, /^Company/, /^Street address/, /^Apartment/, /^City/, /^ZIP code/]) {
      const input = within(dialog).getByLabelText(label);
      await user.clear(input);
      await user.type(input, label.source.includes('ZIP') ? '77002' : 'Ade 1');
      expect(input).toHaveValue(label.source.includes('ZIP') ? '77002' : 'Ade 1');
    }
    const phone = within(dialog).getByLabelText(/^Phone/);
    await user.clear(phone);
    await user.type(phone, '7135550142');
    expect((phone as HTMLInputElement).value.replace(/\D/g, '')).toContain('7135550142');
    clean();
  });

  it('uses every control of the pickup form (switch, day toggles, time selects, ready time) without a single error', async () => {
    const user = userEvent.setup();
    await mockApi().setCustomerPickup('sfa-store', { enabled: false, hours: [], readyHours: 0 });
    await renderScreen(
      <Guarded>
        <PickupView />
      </Guarded>
    );
    const form = (await screen.findAllByTestId('pickup-location'))[0] as HTMLElement;
    await user.click(within(form).getByRole('switch', { name: 'Let customers collect orders' }));
    // Three days, each with its own hours.
    for (const day of ['Monday', 'Tuesday', 'Wednesday']) {
      await user.click(within(form).getByRole('switch', { name: day }));
      await user.selectOptions(within(form).getByLabelText(`From, ${day}`), '09:00');
      await user.selectOptions(within(form).getByLabelText(`To, ${day}`), '17:30');
      expect(within(form).getByLabelText(`From, ${day}`)).toHaveValue('09:00');
      expect(within(form).getByLabelText(`To, ${day}`)).toHaveValue('17:30');
    }
    await user.selectOptions(within(form).getByLabelText('Ready to collect'), '24');
    expect(within(form).getByLabelText('Ready to collect')).toHaveValue('24');
    await user.click(within(form).getByRole('button', { name: 'Save customer pickup' }));
    await waitFor(async () => expect((await mockApi().listShipFrom())[0]?.customerPickup).toMatchObject({ enabled: true, readyHours: 24 }));
    clean();
  });

  it('uses the driver pickup hours without a single error', async () => {
    const user = userEvent.setup();
    await renderScreen(
      <Guarded>
        <ShippingView />
      </Guarded>
    );
    const katy = within((await screen.findAllByTestId('shipping-location'))[1] as HTMLElement).getByRole('form', { name: 'Fulfilled by AfroTransact' });
    await user.click(within(katy).getByRole('switch', { name: /AfroTransact collects from here/ }));
    await user.click(within(katy).getByRole('switch', { name: 'Monday' }));
    await user.selectOptions(within(katy).getByLabelText('From, Monday'), '08:30');
    expect(within(katy).getByLabelText('From, Monday')).toHaveValue('08:30');
    clean();
  });
});

describe('no event reads inside state updaters', () => {
  it('never touches e.currentTarget in a setState updater function (React clears it before the updater runs)', () => {
    const root = join(process.cwd(), '..', '..');
    const bad: string[] = [];
    const walk = (dir: string) => {
      for (const ent of readdirSync(dir, { withFileTypes: true })) {
        if (ent.name === 'node_modules' || ent.name === '.next') continue;
        const p = join(dir, ent.name);
        if (ent.isDirectory()) walk(p);
        else if (/\.tsx$/.test(ent.name) && !/\.(test|stories)\./.test(ent.name)) {
          const src = readFileSync(p, 'utf8');
          for (const m of src.matchAll(/set\w+\(\(\w*\) =>(?:(?!\bon[A-Z]\w*=)[^;]){0,300}?\bcurrentTarget\b/g)) bad.push(`${p}: ${m[0].slice(0, 80)}`);
        }
      }
    };
    for (const area of ['apps', 'packages']) walk(join(root, area));
    expect(bad).toEqual([]);
  });
});
