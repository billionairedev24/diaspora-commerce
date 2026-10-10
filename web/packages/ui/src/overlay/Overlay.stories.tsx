import { type Meta, type StoryObj } from '@storybook/react-vite';
import { useState } from 'react';

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
export const DialogStory: Story = { name: 'Dialog', render: () => <DialogDemo initial /> };

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
  )
};

export const TabsStory: Story = {
  name: 'Tabs',
  render: () => <Tabs label="Product information" items={[{ value: 'about', label: 'About this item', content: 'Unpolished local rice.' }, { value: 'ship', label: 'Shipping', content: 'Ships from Houston FC.' }, { value: 'ret', label: 'Returns', content: 'Returnable within 30 days.' }]} />
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
export const ToastStory: Story = { name: 'Toast', render: () => <ToastProvider><ToastButton /></ToastProvider> };
export const Empty: Story = { render: () => <Menu trigger={<Button variant="neutral">Empty menu</Button>} inline label="Empty"><MenuItem>Nothing here yet</MenuItem></Menu> };
export const RTL: Story = { ...rtl, render: () => <DialogDemo initial /> };
export const Mobile: Story = { ...mobile, render: () => <SheetDemo /> };
