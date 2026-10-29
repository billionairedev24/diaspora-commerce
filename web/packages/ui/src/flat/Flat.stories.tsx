import { type Meta, type StoryObj } from '@storybook/react-vite';
import { expect } from 'storybook/test';

import { Button } from '../button/Button';
import { fieldStyles } from '../form/Field';
import { TextField } from '../form/TextField';
import { mobile, rtl } from '../stories.helpers';
import {
  BigFigure,
  DataTable,
  DefinitionList,
  EmptyState,
  fieldWidth,
  FlatPage,
  FormActions,
  FormFields,
  InlineFormRow,
  LinkEntry,
  linkEntryClassName,
  LinkGroup,
  PageHeader,
  Pager,
  Row,
  RowList,
  Section,
  StatusText,
  TextLinkButton
} from './FlatPage';

const meta: Meta = { title: 'Account/Flat primitives' };
export default meta;
type Story = StoryObj;

const crumb = { label: 'Breadcrumb', items: [{ label: 'Your account', href: '/account' }, { label: 'Gift cards & credit' }] };
const history = [
  { id: '1', date: 'Oct 26, 2026', what: 'Order AT-2026-104221', amount: '−$50.00', balance: '$25.00' },
  { id: '2', date: 'Oct 20, 2026', what: 'Gift card redeemed', amount: '+$50.00', balance: '$75.00' }
];

export const Page: Story = {
  render: () => (
    <FlatPage>
      <PageHeader title="Gift cards & credit" breadcrumb={crumb} lead="Credit pays for products and services." actions={<a href="/gift-cards">Buy a gift card</a>} />
      <Section title="Balance">
        <BigFigure>$25.00</BigFigure>
      </Section>
      <Section title="Redeem a gift card">
        <InlineFormRow fieldId="code" label="Gift card code" button={<Button>Redeem</Button>} help="16 characters, from the email" message={{ tone: 'success', text: '$50.00 added to your credit.' }}>
          {(field) => <input {...field} className={fieldStyles.control} placeholder="XXXX-XXXX-XXXX-XXXX" />}
        </InlineFormRow>
      </Section>
      <Section title="Activity">
        <DataTable
          caption="Activity"
          rows={history}
          rowKey={(r) => r.id}
          columns={[
            { key: 'd', header: 'Date', label: 'Date', cell: (r) => r.date },
            { key: 'w', header: 'Description', label: 'Description', cell: (r) => r.what },
            { key: 'a', header: 'Amount', label: 'Amount', numeric: true, cell: (r) => r.amount },
            { key: 'b', header: 'Balance', label: 'Balance', numeric: true, cell: (r) => r.balance }
          ]}
        />
        <Pager info="1–2 of 2">
          <TextLinkButton disabled>Previous</TextLinkButton>
          <TextLinkButton disabled>Next</TextLinkButton>
        </Pager>
      </Section>
    </FlatPage>
  ),
  play: async ({ canvas }) => {
    await expect(canvas.getByRole('heading', { level: 1, name: 'Gift cards & credit' })).toBeVisible();
    await expect(canvas.getByRole('table', { name: 'Activity' })).toBeVisible();
  }
};
export const PageRtl: Story = { ...Page, ...rtl };
export const PageMobile: Story = { ...Page, ...mobile };

export const Rows: Story = {
  render: () => (
    <FlatPage>
      <Section title="Payment methods" action={<TextLinkButton>Add a payment method</TextLinkButton>}>
        <RowList>
          <Row title="Visa ending 4242" detail="Expires 12/2030 · Default" actions={<TextLinkButton danger>Remove</TextLinkButton>} />
          <Row title="Mastercard ending 4444" detail={<StatusText tone="warning">Expires soon</StatusText>} actions={<><TextLinkButton>Set as default</TextLinkButton><TextLinkButton danger>Remove</TextLinkButton></>} />
        </RowList>
      </Section>
      <Section title="Plan">
        <DefinitionList items={[{ term: 'Plan', value: 'Plus, monthly' }, { term: 'Next billing date', value: 'Nov 12, 2026' }, { term: 'Status', value: <StatusText tone="positive">Active</StatusText> }]} />
      </Section>
    </FlatPage>
  )
};

export const FormPattern: Story = {
  render: () => (
    <FlatPage>
      <Section title="Your name">
        <FormFields label="Your name">
          <TextField className={fieldWidth.fit} label="Full name" defaultValue="Adaeze N." help="As it appears on your orders" />
          <TextField className={fieldWidth.short} label="ZIP code" defaultValue="77042" />
          <FormActions>
            <Button type="submit">Save changes</Button>
            <TextLinkButton>Cancel</TextLinkButton>
          </FormActions>
        </FormFields>
      </Section>
    </FlatPage>
  )
};

export const Empty: Story = {
  render: () => (
    <FlatPage>
      <Section title="Activity">
        <EmptyState action={<a href="/account/refer">Refer a friend</a>}>No activity yet. Redeem a card or earn credit with Refer a friend.</EmptyState>
      </Section>
    </FlatPage>
  )
};

export const Hub: Story = {
  render: () => (
    <FlatPage width="wide">
      <PageHeader title="Your account" />
      <LinkGroup title="Payments">
        <LinkEntry link={<a className={linkEntryClassName} href="/account/credit">Gift cards & credit</a>} text="Balance, redeem a card, buy one" fact="Balance $25.00" />
        <LinkEntry link={<a className={linkEntryClassName} href="/account/payments">Payment methods</a>} text="Cards and bank accounts" fact="Default Visa 4242" />
      </LinkGroup>
    </FlatPage>
  )
};
