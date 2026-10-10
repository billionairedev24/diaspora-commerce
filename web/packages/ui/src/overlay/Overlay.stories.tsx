import { type Meta, type StoryObj } from '@storybook/react-vite';
import { useState } from 'react';
import { expect, screen, waitFor } from 'storybook/test';

import { Button } from '../button/Button';
import { Package, SignOut, Sparkle, User } from '../icons';
import { mobile, rtl } from '../stories.helpers';
import { Dialog, Sheet } from './Dialog';
import { Menu, MenuGroup, MenuItem, MenuSeparator } from './Menu';
import { Tabs } from './Tabs';
import { ToastProvider, useToast } from './Toast';

const meta: Meta = { title: 'Overlays/Dialog, Sheet, Menu, Tabs, Toast' };
export default meta;
type Story = StoryObj;

function DialogDemo({ initial = false }: { initial?: boolean }) {
  const [open, setOpen] = useState(initial);
  return (
    <>
      <Button onClick={() => setOpen(true)}>Choose your location</Button>
      <Dialog open={open} onOpenChange={setOpen} title="Choose your location" description="Delivery options and speeds vary by location.">
        <Button block onClick={() => setOpen(false)}>Apply</Button>
      </Dialog>
    </>
  );
}
export const DialogStory: Story = {
  name: 'Dialog',
  render: () => <DialogDemo initial />,
  play: async ({ canvas, userEvent, step }) => {
    // Radix portals the dialog to <body>, outside the story canvas.
    const dialog = await screen.findByRole('dialog', { name: 'Choose your location' });
    await expect(dialog).toHaveAccessibleDescription('Delivery options and speeds vary by location.');
    await step('Esc closes it', async () => {
      await userEvent.keyboard('{Escape}');
      await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    });
    await step('the trigger reopens it; Apply closes it', async () => {
      const trigger = canvas.getByRole('button', { name: 'Choose your location' });
      await userEvent.click(trigger);
      await userEvent.click(await screen.findByRole('button', { name: 'Apply' }));
      await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    });
    await step('reopen for the snapshot; focus is trapped inside', async () => {
      await userEvent.click(canvas.getByRole('button', { name: 'Choose your location' }));
      const reopened = await screen.findByRole('dialog', { name: 'Choose your location' });
      await userEvent.tab();
      await userEvent.tab();
      await expect(reopened).toContainElement(document.activeElement as HTMLElement);
    });
  }
};

function SheetDemo() {
  const [open, setOpen] = useState(true);
  return (
    <>
      <Button onClick={() => setOpen(true)}>Open Lola</Button>
      <Sheet open={open} onOpenChange={setOpen} title="Lola">
        <p>Your AfroTransact shopping assistant.</p>
      </Sheet>
    </>
  );
}
export const SheetStory: Story = { name: 'Sheet', render: () => <SheetDemo /> };

export const MenuStory: Story = {
  name: 'Menu',
  render: () => (
    <div style={{ display: 'flex', justifyContent: 'flex-end', height: 360 }}>
      <Menu trigger={<Button variant="neutral">Account</Button>} defaultOpen inline label="Account menu">
        <MenuGroup>
          <MenuItem icon={<User aria-hidden />}>Your account</MenuItem>
          <MenuItem icon={<Package aria-hidden />} count={2}>Your orders</MenuItem>
          <MenuItem icon={<Sparkle aria-hidden />}>Ask Lola</MenuItem>
        </MenuGroup>
        <MenuSeparator />
        <MenuItem icon={<SignOut aria-hidden />}>Sign out</MenuItem>
      </Menu>
    </div>
  ),
  play: async ({ canvas, userEvent }) => {
    // Opens by default; a focus change from a previously rendered story (test runner reuses the page) can dismiss it.
    const trigger = canvas.getByRole('button', { name: 'Account' });
    if (trigger.getAttribute('aria-expanded') !== 'true') await userEvent.click(trigger);
    // The `label` prop names the menu (it replaces Radix's aria-labelledby pointing at the trigger).
    const menu = await screen.findByRole('menu', { name: 'Account menu' });
    await expect(menu).toBeVisible();
    await expect(screen.getAllByRole('menuitem')).toHaveLength(4);
    await expect(screen.getByRole('menuitem', { name: /Your orders/ })).toBeVisible();
    await expect(screen.getByRole('menuitem', { name: 'Sign out' })).toBeVisible();
  }
};

export const TabsStory: Story = {
  name: 'Tabs',
  render: () => <Tabs label="Product information" items={[{ value: 'about', label: 'About this item', content: 'Unpolished local rice.' }, { value: 'ship', label: 'Shipping', content: 'Ships from Houston FC.' }, { value: 'ret', label: 'Returns', content: 'Returnable within 30 days.' }]} />,
  play: async ({ canvas, userEvent }) => {
    await expect(canvas.getByRole('tabpanel')).toHaveTextContent('Unpolished local rice.');
    await userEvent.click(canvas.getByRole('tab', { name: 'Shipping' }));
    await expect(canvas.getByRole('tabpanel')).toHaveTextContent('Ships from Houston FC.');
    // Arrow keys move between tabs (roving tabindex).
    await userEvent.keyboard('{ArrowRight}');
    await expect(canvas.getByRole('tab', { name: 'Returns' })).toHaveAttribute('aria-selected', 'true');
    await expect(canvas.getByRole('tabpanel')).toHaveTextContent('Returnable within 30 days.');
  }
};

function ToastButton() {
  const t = useToast();
  return (
    <div style={{ display: 'flex', gap: 8 }}>
      <Button onClick={() => t.show('Added to cart')}>Show toast</Button>
      <Button variant="neutral" onClick={() => t.show('Only 3 available', 'error')}>Show error</Button>
    </div>
  );
}
export const ToastStory: Story = {
  name: 'Toast',
  render: () => <ToastProvider><ToastButton /></ToastProvider>,
  play: async ({ canvas, userEvent }) => {
    await userEvent.click(canvas.getByRole('button', { name: 'Show toast' }));
    await expect(canvas.getByRole('status')).toHaveTextContent('Added to cart');
    await userEvent.click(canvas.getByRole('button', { name: 'Show error' }));
    await expect(canvas.getByRole('alert')).toHaveTextContent('Only 3 available');
    // Toasts dismiss themselves (2.6 s); waiting also leaves a stable frame for the visual snapshot.
    await waitFor(() => expect(canvas.getByRole('status')).toBeEmptyDOMElement(), { timeout: 5000 });
    await waitFor(() => expect(canvas.getByRole('alert')).toBeEmptyDOMElement(), { timeout: 5000 });
  }
};
export const Empty: Story = { render: () => <Menu trigger={<Button variant="neutral">Empty menu</Button>} inline label="Empty"><MenuItem>Nothing here yet</MenuItem></Menu> };
export const RTL: Story = { ...rtl, render: () => <DialogDemo initial /> };
export const Mobile: Story = { ...mobile, render: () => <SheetDemo /> };
