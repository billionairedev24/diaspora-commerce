import { type Meta, type StoryObj } from '@storybook/react-vite';

import { SavedPaymentCard } from './SavedPaymentCard';

const meta: Meta<typeof SavedPaymentCard> = { title: 'Commerce/Saved payment card', component: SavedPaymentCard };
export default meta;
type Story = StoryObj<typeof SavedPaymentCard>;

const labels = { defaultTag: 'Default', expires: (d: string) => `Expires ${d}`, expired: 'Expired' };

export const Networks: Story = {
  render: () => (
    <div style={{ display: 'grid', gap: 16, gridTemplateColumns: 'repeat(auto-fill, minmax(16rem, 1fr))' }}>
      <SavedPaymentCard type="card" brand="visa" last4="4242" expires="04/29" isDefault labels={labels} />
      <SavedPaymentCard type="card" brand="mastercard" last4="4444" expires="11/27" labels={labels} />
      <SavedPaymentCard type="card" brand="amex" last4="0005" expires="02/28" labels={labels} />
      <SavedPaymentCard type="card" brand="discover" last4="1117" expires="01/24" labels={labels} />
      <SavedPaymentCard type="card" brand="jcb" last4="0000" expires="09/30" labels={labels} />
      <SavedPaymentCard type="card" brand="diners" last4="0004" expires="09/30" labels={labels} />
      <SavedPaymentCard type="card" brand="unionpay" last4="0005" expires="09/30" labels={labels} />
    </div>
  )
};

export const Wallets: Story = {
  render: () => (
    <div style={{ display: 'grid', gap: 16, gridTemplateColumns: 'repeat(auto-fill, minmax(16rem, 1fr))' }}>
      <SavedPaymentCard type="us_bank_account" last4="6789" title="Chase" detail="Checking" labels={labels} />
      <SavedPaymentCard type="cashapp" labels={labels} />
      <SavedPaymentCard type="link" labels={labels} />
      <SavedPaymentCard type="amazon_pay" labels={labels} />
    </div>
  )
};

export const Selected: Story = { render: () => <SavedPaymentCard type="card" brand="visa" last4="4242" expires="04/29" selected labels={labels} /> };
