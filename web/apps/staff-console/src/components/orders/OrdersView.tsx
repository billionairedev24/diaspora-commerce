'use client';

import { Tabs } from '@afrotransact/ui';
import { ArrowRight, ArrowSquareOut } from '@phosphor-icons/react/ssr';
import Link from 'next/link';
import { usePathname, useRouter, useSearchParams } from 'next/navigation';

import { toneOf } from '@/components/inventory/shared';
import { ModulePage } from '@/components/module/ModulePage';
import { ResourceTable } from '@/components/resources/ResourceTable';
import { Card } from '@/components/ui/Card';
import { StatusPill } from '@/components/ui/StatusPill';
import { useDocsUrl } from '@/lib/runtime-config';

/** The guide on the developer portal (docs/portal/docs/architecture/orders-and-shipments.md). */
export const ORDERS_GUIDE_PATH = '/docs/architecture/orders-and-shipments';

const STEPS: { title: string; who: string; what: string; screen: [string, string] }[] = [
  { title: 'Checkout', who: 'Customer', what: 'The cart splits into packages per seller and fulfilment. The card is authorised, not charged, and AfroTransact stock is reserved.', screen: ['Orders', '/orders'] },
  { title: 'Pick and pack', who: 'FC staff, or the seller for its own packages', what: 'Open the order and tap its one next step: Mark packed, then Mark ready. Only the seller packs seller-fulfilled packages.', screen: ['Orders › the order’s next-step button', '/orders'] },
  { title: 'Hand over', who: 'Carrier or AfroTransact driver', what: 'Assign a driver in Delivery dispatch; the driver scans the packages at the dock and starts the route in the driver app, which moves the shipment to picked up and out for delivery. The shipment’s money is captured when it is picked up.', screen: ['Delivery dispatch', '/dispatch'] },
  { title: 'Deliver', who: 'Driver (photo, code) or carrier', what: 'The driver records proof in the app (customer’s code or signature, photo, safe place), which marks it delivered and shows on the order. Delivered starts the seller’s 7-day settlement hold. Failed attempts are retried or returned.', screen: ['Delivery dispatch', '/dispatch'] },
  { title: 'Returns and claims', who: 'Customer, then Trust & support', what: 'A return is approved, collected or dropped off, inspected and refunded. A-to-z claims are decided after the seller’s response.', screen: ['Disputes & returns', '/disputes'] },
  { title: 'Payout', who: 'Seller, then Finance', what: 'Matured holds are transferred to the seller’s Stripe account. The seller requests a payout and Finance approves it.', screen: ['Payouts', '/payouts'] }
];

const LIFECYCLES: { label: string; states: [string, string][]; note: string }[] = [
  { label: 'Shipment (package)', states: [['Placed', 'PLACED'], ['Confirmed', 'CONFIRMED'], ['Packed', 'PACKED'], ['Ready', 'READY'], ['Picked up', 'PICKED_UP'], ['In transit', 'IN_TRANSIT'], ['Out for delivery', 'OUT_FOR_DELIVERY'], ['Delivered', 'DELIVERED']], note: 'Customers cancel until it is packed. Delivery failed and returned are the other outcomes.' },
  { label: 'Order', states: [['Placed', 'PLACED'], ['In progress', 'IN_PROGRESS'], ['Completed', 'COMPLETED']], note: 'Worked out from its shipments. Cancelled when every shipment is cancelled.' },
  { label: 'Return', states: [['Requested', 'REQUESTED'], ['Approved', 'APPROVED'], ['In transit', 'IN_TRANSIT'], ['Received', 'RECEIVED'], ['Inspected', 'INSPECTED'], ['Refunded', 'REFUNDED']], note: 'Drop-off with a carrier label, or collected by a driver on a returns route.' }
];

/** How orders and shipments work: the steps, who does each one, and the status lifecycles (mirrors the inventory hub's Workflow tab). */
export function OrdersFlow() {
  const docs = useDocsUrl();
  return (
    <div className="flex flex-col gap-4">
      <Card>
        <h2 className="m-0 text-xl font-black">From checkout to the customer’s door</h2>
        <p className="m-0 text-sm text-muted">
          An order is split into shipments (packages): one per seller and fulfilment type, and one per pre-order window. Each shipment moves on its own. The fulfilment centre packs
          AfroTransact-fulfilled packages and sellers pack their own; a carrier or an AfroTransact driver delivers them. The card is authorised at checkout and each shipment’s share is
          captured when it is picked up. The order’s status follows its shipments.
        </p>
        <a href={`${docs}${ORDERS_GUIDE_PATH}`} target="_blank" rel="noreferrer" className="inline-flex items-center gap-1 text-sm font-semibold text-link">
          Read the guide: how orders and shipments work <ArrowSquareOut size={14} aria-hidden />
        </a>
      </Card>
      <ol className="m-0 grid list-none grid-cols-[repeat(auto-fill,minmax(min(100%,250px),1fr))] gap-3 p-0" aria-label="Order workflow">
        {STEPS.map((s, i) => (
          <li key={s.title}>
            <Link href={s.screen[1]} className="flex h-full flex-col gap-2 rounded-md border border-border bg-surface p-4 text-inherit no-underline hover:border-border-strong" data-testid="orders-flow-step">
              <span className="flex items-center gap-2">
                <span className="flex size-7 shrink-0 items-center justify-center rounded-full bg-ink-1000 text-sm font-bold text-gold-500">{i + 1}</span>
                <strong className="flex-1">{s.title}</strong>
              </span>
              <span className="text-xs text-muted">
                <strong className="text-text">Who:</strong> {s.who}
              </span>
              <span className="text-sm">{s.what}</span>
              <span className="mt-auto border-t border-border pt-2 text-xs font-bold text-success">{s.screen[0]} →</span>
            </Link>
          </li>
        ))}
      </ol>
      <Card title="Status lifecycles">
        {LIFECYCLES.map((lc) => (
          <div key={lc.label} className="flex flex-col gap-1.5">
            <span className="text-xs font-bold text-muted">{lc.label}</span>
            <div className="flex flex-wrap items-center gap-1.5">
              {lc.states.map(([label, status], i) => (
                <span key={label} className="inline-flex items-center gap-1.5">
                  {i > 0 ? <ArrowRight size={12} className="text-muted" aria-hidden /> : null}
                  <StatusPill tone={toneOf(status)}>{label}</StatusPill>
                </span>
              ))}
            </div>
            <span className="text-xs text-muted">{lc.note}</span>
          </div>
        ))}
      </Card>
    </div>
  );
}

/** Orders & shipments: the server-driven orders table, and a "How it works" tab. */
export function OrdersView() {
  const router = useRouter();
  const pathname = usePathname();
  const params = useSearchParams();
  const tab = params.get('tab') === 'flow' ? 'flow' : 'orders';
  return (
    <ModulePage module="orders">
      <Tabs
        label="Orders"
        value={tab}
        onValueChange={(v) => router.replace(v === 'orders' ? pathname : `${pathname}?tab=${encodeURIComponent(v)}`, { scroll: false })}
        items={[
          { value: 'orders', label: 'Orders', content: tab === 'orders' ? <ResourceTable resource="orders" /> : null },
          { value: 'flow', label: 'How it works', content: tab === 'flow' ? <OrdersFlow /> : null }
        ]}
      />
    </ModulePage>
  );
}
