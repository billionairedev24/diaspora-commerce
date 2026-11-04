import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { ConfirmDelete, InlineEdit, type RowActionItem, RowActions, RowName } from '../src';

const trigger = () => screen.getByRole('button', { name: 'Actions for Cotton kaftan' });

function Row({ items }: { items: RowActionItem[] }) {
  return (
    <div data-testid="scroller" style={{ overflow: 'hidden' }}>
      <RowActions name="Cotton kaftan" items={items} />
    </div>
  );
}

afterEach(() => {
  document.documentElement.removeAttribute('dir');
});

describe('RowActions', () => {
  it('is a "⋯" menu button named after the row', () => {
    render(<Row items={[{ label: 'Edit', onSelect: vi.fn() }]} />);
    expect(trigger()).toHaveAttribute('aria-haspopup', 'menu');
    expect(trigger()).toHaveAttribute('aria-expanded', 'false');
  });

  it('uses a translated name when given', () => {
    render(<RowActions name="Kaftan" label="Actions pour Kaftan" items={[{ label: 'Edit' }]} />);
    expect(screen.getByRole('button', { name: 'Actions pour Kaftan' })).toBeInTheDocument();
  });

  it('renders nothing when no action is available', () => {
    const { container } = render(<RowActions name="Kaftan" items={[false, null, undefined]} />);
    expect(container).toBeEmptyDOMElement();
  });

  it('opens with Enter, moves with the arrows, selects with Enter and returns focus on Escape', async () => {
    const user = userEvent.setup();
    const edit = vi.fn();
    const pause = vi.fn();
    render(<Row items={[{ label: 'Edit', onSelect: edit }, { label: 'Pause', onSelect: pause }]} />);
    trigger().focus();
    await user.keyboard('{Enter}');
    expect(trigger()).toHaveAttribute('aria-expanded', 'true');
    expect(await screen.findByRole('menu', { name: 'Actions for Cotton kaftan' })).toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole('menuitem', { name: 'Edit' })).toHaveFocus());
    await user.keyboard('{ArrowDown}');
    expect(screen.getByRole('menuitem', { name: 'Pause' })).toHaveFocus();
    await user.keyboard('{Escape}');
    await waitFor(() => expect(screen.queryByRole('menu')).not.toBeInTheDocument());
    expect(trigger()).toHaveFocus();
    expect(edit).not.toHaveBeenCalled();

    await user.keyboard(' ');
    await user.keyboard('{ArrowDown}{Enter}');
    expect(pause).toHaveBeenCalledTimes(1);
  });

  it('opens its menu in a portal, outside the table cell that could clip it', async () => {
    const user = userEvent.setup();
    render(<Row items={[{ label: 'Edit' }]} />);
    await user.click(trigger());
    const menu = await screen.findByRole('menu');
    expect(screen.getByTestId('scroller')).not.toContainElement(menu);
    expect(menu.closest('[data-radix-popper-content-wrapper]')?.parentElement).toBe(document.body);
  });

  it('closes on an outside click and on scroll', async () => {
    const user = userEvent.setup();
    render(
      <div>
        <button type="button">elsewhere</button>
        <Row items={[{ label: 'Edit' }]} />
      </div>
    );
    await user.click(trigger());
    await screen.findByRole('menu');
    await user.click(screen.getByText('elsewhere'));
    await waitFor(() => expect(screen.queryByRole('menu')).not.toBeInTheDocument());

    await user.click(trigger());
    await screen.findByRole('menu');
    window.dispatchEvent(new Event('scroll'));
    await waitFor(() => expect(screen.queryByRole('menu')).not.toBeInTheDocument());
  });

  it('puts destructive items last, under a separator, in red', async () => {
    const user = userEvent.setup();
    render(
      <Row
        items={[
          { label: 'Discard', danger: true },
          { label: 'Edit' },
          'separator',
          { label: 'Duplicate' },
          { label: 'Withdraw', danger: true }
        ]}
      />
    );
    await user.click(trigger());
    const names = (await screen.findAllByRole('menuitem')).map((i) => i.textContent);
    expect(names).toEqual(['Edit', 'Duplicate', 'Discard', 'Withdraw']);
    expect(screen.getAllByRole('separator')).toHaveLength(2);
    expect(screen.getByRole('menuitem', { name: 'Discard' }).className).toMatch(/menuDanger/);
    expect(screen.getByRole('menuitem', { name: 'Edit' }).className).not.toMatch(/menuDanger/);
  });

  it('shows why an item is disabled and does not run it', async () => {
    const user = userEvent.setup();
    const onSelect = vi.fn();
    render(<Row items={[{ label: 'Resume', disabled: 'Taken down by moderation', onSelect }, { label: 'Edit' }]} />);
    await user.click(trigger());
    const item = await screen.findByRole('menuitem', { name: /Resume/ });
    expect(item).toHaveAttribute('aria-disabled', 'true');
    expect(item).toHaveAttribute('title', 'Taken down by moderation');
    expect(within(item).getByText('Taken down by moderation')).toBeInTheDocument();
    await user.click(item);
    expect(onSelect).not.toHaveBeenCalled();
  });

  it('links an item out in a new tab', async () => {
    const user = userEvent.setup();
    render(<Row items={[{ label: 'View on storefront', href: '/p/AT-1', external: true }]} />);
    await user.click(trigger());
    const link = await screen.findByRole('menuitem', { name: 'View on storefront' });
    expect(link).toHaveAttribute('href', '/p/AT-1');
    expect(link).toHaveAttribute('target', '_blank');
  });

  it('opens towards the page direction in right-to-left languages', async () => {
    document.documentElement.dir = 'rtl';
    const user = userEvent.setup();
    render(<Row items={[{ label: 'Edit' }, { label: 'Pause' }]} />);
    trigger().focus();
    await user.keyboard('{Enter}');
    const menu = await screen.findByRole('menu');
    expect(menu).toHaveAttribute('dir', 'rtl');
    await waitFor(() => expect(screen.getByRole('menuitem', { name: 'Edit' })).toHaveFocus());
    await user.keyboard('{ArrowDown}');
    expect(screen.getByRole('menuitem', { name: 'Pause' })).toHaveFocus();
  });
});

describe('RowName', () => {
  it('is the row link with a chip and a meta line', () => {
    render(<RowName href="/listings/1/edit" badge={<span>Live</span>} meta="AT-000123">Cotton kaftan</RowName>);
    expect(screen.getByRole('link', { name: 'Cotton kaftan' })).toHaveAttribute('href', '/listings/1/edit');
    expect(screen.getByText('Live')).toBeInTheDocument();
    expect(screen.getByText('AT-000123')).toBeInTheDocument();
  });

  it('is a button when it opens a sheet and plain text when it goes nowhere', async () => {
    const onClick = vi.fn();
    const { rerender } = render(<RowName onClick={onClick}>Cotton kaftan</RowName>);
    await userEvent.click(screen.getByRole('button', { name: 'Cotton kaftan' }));
    expect(onClick).toHaveBeenCalled();
    rerender(<RowName>Cotton kaftan</RowName>);
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
    expect(screen.queryByRole('link')).not.toBeInTheDocument();
  });
});

function Discard({ onConfirm }: { onConfirm: () => void }) {
  const [open, setOpen] = useState(false);
  return (
    <>
      <button type="button" onClick={() => setOpen(true)}>
        Open
      </button>
      <ConfirmDelete open={open} onOpenChange={setOpen} title="Discard “Cotton kaftan”?" description="The draft is deleted." verb="Discard" onConfirm={onConfirm} />
    </>
  );
}

describe('ConfirmDelete', () => {
  it('says what is affected, names the verb and focuses Cancel first', async () => {
    const user = userEvent.setup();
    const onConfirm = vi.fn();
    render(<Discard onConfirm={onConfirm} />);
    await user.click(screen.getByRole('button', { name: 'Open' }));
    const dialog = await screen.findByRole('dialog', { name: 'Discard “Cotton kaftan”?' });
    await waitFor(() => expect(within(dialog).getByRole('button', { name: 'Cancel' })).toHaveFocus());
    expect(onConfirm).not.toHaveBeenCalled();
    await user.click(within(dialog).getByRole('button', { name: 'Discard' }));
    expect(onConfirm).toHaveBeenCalledTimes(1);
  });

  it('cancels with the button or Escape without confirming', async () => {
    const user = userEvent.setup();
    const onConfirm = vi.fn();
    render(<Discard onConfirm={onConfirm} />);
    await user.click(screen.getByRole('button', { name: 'Open' }));
    await user.click(await screen.findByRole('button', { name: 'Cancel' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    await user.click(screen.getByRole('button', { name: 'Open' }));
    await screen.findByRole('dialog');
    await user.keyboard('{Escape}');
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(onConfirm).not.toHaveBeenCalled();
  });

  it('turns both buttons off while the request runs', async () => {
    render(<ConfirmDelete open onOpenChange={vi.fn()} title="Delete?" verb="Delete" onConfirm={vi.fn()} pending />);
    expect(await screen.findByRole('button', { name: 'Cancel' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Delete' })).toBeDisabled();
  });
});

describe('InlineEdit', () => {
  const setup = (onSave = vi.fn().mockResolvedValue(undefined)) => {
    render(
      <InlineEdit value="12" label="Edit stock of Cotton kaftan" saveLabel="Save stock" cancelLabel="Cancel edit" inputMode="numeric" validate={(v) => (/^\d+$/.test(v) ? undefined : 'Whole numbers only')} onSave={onSave}>
        12 units
      </InlineEdit>
    );
    return onSave;
  };

  it('opens on click, saves the changed value with Enter and returns to the value', async () => {
    const user = userEvent.setup();
    const onSave = setup();
    await user.click(screen.getByRole('button', { name: 'Edit stock of Cotton kaftan' }));
    const field = screen.getByRole('textbox', { name: 'Edit stock of Cotton kaftan' });
    expect(field).toHaveFocus();
    await user.clear(field);
    await user.type(field, '20{Enter}');
    expect(onSave).toHaveBeenCalledWith('20');
    await waitFor(() => expect(screen.getByRole('button', { name: 'Edit stock of Cotton kaftan' })).toHaveFocus());
  });

  it('cancels with Escape or the Cancel button and sends nothing', async () => {
    const user = userEvent.setup();
    const onSave = setup();
    await user.click(screen.getByRole('button', { name: 'Edit stock of Cotton kaftan' }));
    await user.type(screen.getByRole('textbox'), '5{Escape}');
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Edit stock of Cotton kaftan' }));
    await user.click(screen.getByRole('button', { name: 'Cancel edit' }));
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument();
    expect(onSave).not.toHaveBeenCalled();
  });

  it('keeps editing and says why when the value is invalid or the save fails', async () => {
    const user = userEvent.setup();
    const onSave = setup(vi.fn().mockRejectedValue(new Error('Stock is locked')));
    await user.click(screen.getByRole('button', { name: 'Edit stock of Cotton kaftan' }));
    await user.type(screen.getByRole('textbox'), 'x');
    await user.click(screen.getByRole('button', { name: 'Save stock' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Whole numbers only');
    expect(onSave).not.toHaveBeenCalled();
    await user.clear(screen.getByRole('textbox'));
    await user.type(screen.getByRole('textbox'), '30');
    await user.click(screen.getByRole('button', { name: 'Save stock' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Stock is locked');
    expect(screen.getByRole('textbox')).toBeInTheDocument();
  });

  it('does not save an unchanged value', async () => {
    const user = userEvent.setup();
    const onSave = setup();
    await user.click(screen.getByRole('button', { name: 'Edit stock of Cotton kaftan' }));
    await user.keyboard('{Enter}');
    expect(onSave).not.toHaveBeenCalled();
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument();
  });

  it('is plain text when read-only', () => {
    render(<InlineEdit value="12" label="Edit" readOnly onSave={vi.fn()}>12 units</InlineEdit>);
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
    expect(screen.getByText('12 units')).toBeInTheDocument();
  });
});
