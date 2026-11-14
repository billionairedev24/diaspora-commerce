import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';

import { resetSellerDb } from '@afrotransact/api-client/mocks/seller';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { ShipFromView } from '@/components/orders/ShipFromView';

import { asRole } from './mock-api';
import { renderScreen } from './render';

/*
 * Regression: typing in the ship-from address form threw "Cannot read properties of null (reading 'value')" and took the
 * page down to the error boundary. The field handler read e.currentTarget.value inside the setState updater, which React
 * runs later (during render) when React has already cleared currentTarget.
 */
describe('pickup and address forms survive typing', () => {
  let errors: ReturnType<typeof vi.spyOn>;
  beforeEach(() => {
    resetSellerDb(new Date());
    asRole(null);
    errors = vi.spyOn(console, 'error').mockImplementation(() => undefined);
  });
  afterEach(() => errors.mockRestore());

  it('types into every field of the address form without a single error', async () => {
    const user = userEvent.setup();
    await renderScreen(<ShipFromView />);
    await screen.findAllByTestId('ship-from-address');
    await user.click(screen.getAllByRole('button', { name: 'Edit' })[0]!);
    const dialog = await screen.findByRole('dialog');
    for (const label of [/^Name of this location/, /^Contact name/, /^Company/, /^Street address/, /^City/, /^ZIP code/]) {
      const input = within(dialog).getByLabelText(label);
      await user.clear(input);
      await user.type(input, 'Ade 1');
      expect(input).toHaveValue('Ade 1');
    }
    expect(errors).not.toHaveBeenCalled();
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
          for (const m of src.matchAll(/set\w+\(\(\w*\) =>[^;]{0,300}?\bcurrentTarget\b/g)) bad.push(`${p}: ${m[0].slice(0, 80)}`);
        }
      }
    };
    for (const area of ['apps', 'packages']) walk(join(root, area));
    expect(bad).toEqual([]);
  });
});
