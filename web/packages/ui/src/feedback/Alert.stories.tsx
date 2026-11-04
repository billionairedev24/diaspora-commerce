import { type Meta, type StoryObj } from '@storybook/react-vite';
import { expect } from 'storybook/test';

import { mobile, rtl } from '../stories.helpers';
import { Alert, AlertAction } from './Alert';

const meta: Meta<typeof Alert> = {
  title: 'Feedback/Alert',
  component: Alert,
  args: { variant: 'warning', title: '1 SKU is running low on stock.', children: 'Send stock to AfroTransact or adjust counted quantities to avoid stockouts on the storefront.' }
};
export default meta;
type Story = StoryObj<typeof Alert>;

export const WithAction: Story = {
  args: { action: <AlertAction>Create inbound plan</AlertAction> },
  play: async ({ canvas }) => {
    await expect(canvas.getByRole('status')).toBeVisible();
    await expect(canvas.getByRole('button', { name: 'Create inbound plan' })).toBeVisible();
  }
};

/** Every variant on the white canvas: axe checks the contrast of each. */
export const Variants: Story = {
  render: () => (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      <Alert variant="info" title="Tip.">Add a size chart to cut returns.</Alert>
      <Alert variant="success" title="Done.">Your inbound plan was created.</Alert>
      <Alert variant="warning" title="1 SKU is running low on stock." action={<AlertAction>Create inbound plan</AlertAction>}>Send stock to AfroTransact to avoid stockouts.</Alert>
      <Alert variant="danger" title="Payment failed." action={<AlertAction>Update card</AlertAction>} onDismiss={() => undefined}>Your card was declined.</Alert>
      <Alert variant="neutral" title="Read-only.">Your role can view this page but not change it.</Alert>
    </div>
  ),
  play: async ({ canvas }) => {
    await expect(canvas.getAllByRole('status')).toHaveLength(4);
    await expect(canvas.getAllByRole('alert')).toHaveLength(1);
  }
};

export const Fill: Story = { args: { fill: true, variant: 'info', title: 'Full width:', children: 'only for the rare banner that spans the page.' } };

export const Rtl: Story = { ...rtl, args: { variant: 'danger', title: 'فشل الدفع.', children: 'تم رفض بطاقتك.', action: <AlertAction>تحديث البطاقة</AlertAction>, onDismiss: () => undefined, dismissLabel: 'إغلاق' } };

export const Mobile: Story = { ...mobile, args: { action: <AlertAction>Create inbound plan</AlertAction> } };
