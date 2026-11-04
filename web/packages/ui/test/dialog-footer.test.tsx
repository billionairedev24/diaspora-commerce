import { readFileSync } from 'node:fs';
import { join } from 'node:path';

import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';

import { Button, Dialog, DialogActions, Menu, MenuItem } from '../src';

const css = readFileSync(join(__dirname, '../src/overlay/overlay.module.css'), 'utf8');
const rule = (selector: string): string => {
  const at = css.indexOf(`${selector} {`);
  expect(at, `${selector} is styled`).toBeGreaterThanOrEqual(0);
  return css.slice(at, css.indexOf('}', at));
};

/*
 * Regression: a dialog footer written as `flex justify-end` with a long primary label ("Mark picked up · Shipment 1 of 2
 * · AfroTransact") pushed the Cancel button out of the dialog's start edge, where the dialog clipped it ("ancel").
 * DialogActions wraps instead. jsdom has no layout, so the guard is on the rules that do the work plus the markup.
 */
describe('Dialog footer', () => {
  it('wraps and stays inside the dialog instead of overflowing its start edge', () => {
    expect(rule('.actions')).toMatch(/flex-wrap:\s*wrap/);
    expect(rule('.actions')).toMatch(/justify-content:\s*flex-end/);
    expect(rule('.actions')).toMatch(/min-width:\s*0/);
    expect(rule('.actions > *')).toMatch(/max-width:\s*100%/);
    expect(rule('.dialog')).toMatch(/overflow-x:\s*hidden/);
  });

  it('renders Cancel before the primary button, both reachable, in one group', async () => {
    render(
      <Dialog open onOpenChange={() => undefined} title="Set shipment to Picked up?">
        <DialogActions>
          <Button variant="neutral">Cancel</Button>
          <Button>Set to Picked up</Button>
        </DialogActions>
      </Dialog>
    );
    const cancel = screen.getByRole('button', { name: 'Cancel' });
    const primary = screen.getByRole('button', { name: 'Set to Picked up' });
    expect(cancel.parentElement).toBe(primary.parentElement);
    expect(cancel.compareDocumentPosition(primary) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });
});

describe('MenuItem disabled', () => {
  it('lists an unavailable choice with its reason and does not select it', async () => {
    let picked = '';
    render(
      <Menu trigger={<button type="button">Set status</button>} label="Set status" inline defaultOpen>
        <MenuItem onSelect={() => (picked = 'ok')}>Picked up</MenuItem>
        <MenuItem disabled description="Earlier steps come first" onSelect={() => (picked = 'no')}>
          Delivered
        </MenuItem>
      </Menu>
    );
    const off = screen.getByRole('menuitem', { name: /Delivered/ });
    expect(off).toHaveAttribute('aria-disabled', 'true');
    expect(off).toHaveTextContent('Earlier steps come first');
    await userEvent.click(off);
    expect(picked).toBe('');
    await userEvent.click(screen.getByRole('menuitem', { name: 'Picked up' }));
    expect(picked).toBe('ok');
  });
});
