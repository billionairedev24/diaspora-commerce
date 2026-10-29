'use client';

import { isApiError, LEGAL_DOCUMENT_TITLES, type LegalDocumentKey, type OnboardingForm, type OnboardingStatus, sellerKeys } from '@afrotransact/api-client';
import { WarningCircle } from '@phosphor-icons/react/ssr';
import { useQueryClient } from '@tanstack/react-query';
import Link from 'next/link';
import { type ReactNode, useImperativeHandle, useRef, useState } from 'react';

import { sellerApi } from '@/lib/api';
import { displayPhone, ENTITY_TYPES, FOOD_PERMITS, OFFERINGS, PRODUCT_CATEGORIES } from '@/lib/onboarding-form';
import { onboardingHref } from '@/lib/onboarding-wizard';
import { useSeller } from '@/lib/session';

import { type StepProps } from '../WizardKit';

type Row = [string, ReactNode];

const dash = (v: string | null | undefined): string => (v && v.trim() ? v : '—');
const addressLine = (a: OnboardingForm['details']['address']): string =>
  a ? [a.line1, a.line2, a.city, [a.state, a.postalCode].filter(Boolean).join(' ')].filter(Boolean).join(', ') : '—';
const usd = (cents: number) => `$${(cents / 100).toFixed(2)}`;
const BRANDS: Record<string, string> = { visa: 'Visa', mastercard: 'Mastercard', amex: 'American Express', discover: 'Discover' };
const brandName = (b: string | null): string => (b ? (BRANDS[b.toLowerCase()] ?? b) : 'Card');

/** Rows of each section, keyed by the step's slug. */
function sectionRows(st: OnboardingStatus): Record<string, Row[]> {
  const f = st.form;
  const b = f.business;
  const d = f.details;
  const sole = b.entityType === 'SOLE_PROPRIETOR';
  const c = f.compliance;
  const permit = c.foodPermit;
  return {
    business: [
      ['Offering', OFFERINGS.find((o) => o.value === b.offering)?.label ?? '—'],
      ['Business type', ENTITY_TYPES.find((e) => e.value === b.entityType)?.label ?? '—'],
      ['Store name', dash(b.storeName)],
      ['Business email', dash(b.contactEmail)],
      ['Mobile', dash(displayPhone(b.phone))],
      ['Website', dash(b.website)]
    ],
    'business-details': sole
      ? [
          ['Legal name', dash([d.legalFirstName, d.legalLastName].filter(Boolean).join(' '))],
          ['Doing business as', dash(d.dba)],
          ['SSN or ITIN', dash(d.taxIdMasked)],
          ['Date of birth', dash(d.dateOfBirth)],
          ['Address', addressLine(d.address)]
        ]
      : [
          ['Legal name', dash(d.legalName)],
          ['Doing business as', dash(d.dba)],
          ['EIN', dash(d.taxIdMasked)],
          ['Formation', d.stateOfFormation ? `${d.stateOfFormation} · ${dash(d.dateOfFormation)}` : '—'],
          ['Registered address', addressLine(d.address)],
          ['Formation document', d.formationDocumentAssetId || d.irsDeterminationLetterAssetId ? 'Uploaded' : '—']
        ],
    owners: f.owners.map(
      (o): Row => [
        dash([o.firstName, o.lastName].filter(Boolean).join(' ')),
        `${dash(o.title)} · ${o.ownershipPercent ?? 0}%${o.significantControl ? ' · control person' : ''}`
      ]
    ),
    'stripe-verification': [
      ['Stripe Connect', st.stripe.status === 'VERIFIED' ? 'Verified' : st.stripe.status === 'PENDING' ? 'Submitted · Stripe is checking' : 'Not verified yet'],
      ['Identity (KYC)', st.stripe.identityStatus === 'verified' ? 'Verified' : 'Pending'],
      ['IRS name & TIN match', st.stripe.taxIdStatus === 'verified' ? 'Matched' : 'Pending']
    ],
    payouts: f.payouts.linked
      ? [
          ['Account holder', dash(f.payouts.accountHolderName)],
          ['Account', `${f.payouts.accountType === 'savings' ? 'Savings' : 'Checking'} ••${f.payouts.accountLast4 ?? ''}`],
          ['Bank', dash(f.payouts.bankName)],
          ['Verification', 'Linked to Stripe Connect']
        ]
      : [['Bank account', 'Not added yet']],
    plan: [
      ['Plan', dash(f.plan.planName)],
      ['Billing', f.plan.cadence ? f.plan.cadence.charAt(0) + f.plan.cadence.slice(1).toLowerCase() : '—'],
      ['First charge', f.plan.firstCharge ? (f.plan.firstCharge.amountCents ? f.plan.firstCharge.sentence : 'No subscription fee') : '—'],
      [
        'Pays with',
        f.plan.cardLast4 ? `${brandName(f.plan.cardBrand)} ••${f.plan.cardLast4}` : f.plan.firstCharge && !f.plan.firstCharge.amountCents ? '—' : 'Not added'
      ]
    ],
    'catalog-compliance': [
      ...(c.categories.length ? [['Categories', c.categories.map((k) => PRODUCT_CATEGORIES.find((x) => x.key === k)?.label ?? k).join(', ')] as Row] : []),
      ...(permit
        ? [
            [
              'Food permit',
              `${FOOD_PERMITS.find((x) => x.value === permit.permitType)?.label ?? 'Permit'} ${dash(permit.permitNumber)} · expires ${dash(permit.expiresOn)}`
            ] as Row
          ]
        : []),
      ...c.services.map((s): Row => [s.serviceName, `${dash(s.licenceNumber)} · ${dash(s.issuingState)} · expires ${dash(s.expiresOn)}`]),
      [
        'Agreements',
        c.agreementsAccepted
          ? c.agreements.length
            ? `Accepted: ${c.agreements.map((a) => `${LEGAL_DOCUMENT_TITLES[a.key as LegalDocumentKey] ?? a.key} ${a.version}`).join(', ')}`
            : 'Accepted'
          : 'Not accepted yet'
      ],
      ['W-9 certification', c.w9Certified ? `Certified${c.w9SignerName ? ` by ${c.w9SignerName}` : ''}` : 'Not certified yet']
    ]
  };
}

const STEP_OF_FIELD: [RegExp, string][] = [
  [/^business\./, 'business'],
  [/^businessDetails\./, 'business-details'],
  [/^owners/, 'owners'],
  [/^stripe\./, 'stripe-verification'],
  [/^payouts\./, 'payouts'],
  [/^plan\./, 'plan'],
  [/^compliance\./, 'catalog-compliance']
];

/** Step 8 "Review & submit": every section with an Edit link; submitting sends the application to staff review. */
export function ReviewStep({ status, ref }: StepProps) {
  const qc = useQueryClient();
  const { orgId } = useSeller();
  const [problems, setProblems] = useState<{ slug: string; label: string; count: number }[]>([]);
  const [failure, setFailure] = useState<string | null>(null);
  const box = useRef<HTMLDivElement>(null);
  const rows = sectionRows(status);
  const sections = status.steps.filter((s) => s.key !== 'REVIEW');

  useImperativeHandle(ref, () => ({
    save: async (mode) => {
      if (mode === 'draft') return true;
      setFailure(null);
      try {
        const st = await sellerApi().submitOnboarding();
        qc.setQueryData(sellerKeys.onboarding(orgId), st);
        void qc.invalidateQueries({ queryKey: sellerKeys.session() });
        return true;
      } catch (e) {
        if (isApiError(e) && Object.keys(e.fieldErrors).length) {
          const counts = new Map<string, number>();
          Object.keys(e.fieldErrors).forEach((k) => {
            const slug = STEP_OF_FIELD.find(([re]) => re.test(k))?.[1] ?? 'business';
            counts.set(slug, (counts.get(slug) ?? 0) + 1);
          });
          setProblems(
            sections
              .filter((s) => counts.has(s.slug))
              .map((s) => ({
                slug: s.slug,
                label: s.label,
                count: counts.get(s.slug) ?? 0
              }))
          );
          box.current?.focus();
        } else setFailure(isApiError(e) && e.problem.detail ? e.problem.detail : 'We couldn’t submit your application. Try again.');
        return false;
      }
    }
  }));

  return (
    <div className="flex flex-col gap-3">
      <div ref={box} tabIndex={-1} className="outline-none">
        {problems.length ? (
          <div role="alert" className="flex flex-col gap-1 rounded-md border border-danger bg-danger-bg px-4 py-3 text-sm">
            <strong className="inline-flex items-center gap-1.5">
              <WarningCircle size={18} weight="fill" aria-hidden className="text-danger" /> Some steps still need answers
            </strong>
            <ul className="m-0 pl-5">
              {problems.map((p) => (
                <li key={p.slug}>
                  <Link href={onboardingHref(p.slug)}>
                    {p.label} ({p.count} {p.count === 1 ? 'field' : 'fields'})
                  </Link>
                </li>
              ))}
            </ul>
          </div>
        ) : null}
      </div>
      {sections.map((s) => (
        <section key={s.slug} aria-label={s.label} className="overflow-hidden rounded-sm border border-border">
          <div className="flex items-center justify-between bg-surface-muted px-4 py-2">
            <strong className="text-sm">{s.label}</strong>
            {status.form.editable ? (
              <Link href={onboardingHref(s.slug)} className="text-sm" aria-label={`Edit ${s.label}`}>
                Edit
              </Link>
            ) : null}
          </div>
          <dl className="m-0 grid grid-cols-1 gap-x-4 gap-y-1 px-4 py-3 text-sm sm:grid-cols-[minmax(140px,max-content)_minmax(0,1fr)]">
            {(rows[s.slug] ?? []).map(([k, v], i) => (
              <div key={`${k}-${i}`} className="contents">
                <dt className="text-muted">{k}</dt>
                <dd className="m-0 mb-1 [overflow-wrap:anywhere] sm:mb-0">{v}</dd>
              </div>
            ))}
          </dl>
        </section>
      ))}
      <p className="m-0 text-xs text-muted">
        Our trust team reviews every application, usually within 2 business days. You can’t sell until it’s approved. Plan: {status.form.plan.planName ?? '—'}
        {status.form.plan.firstCharge?.amountCents ? ` (${usd(status.form.plan.firstCharge.amountCents)} after the trial)` : ''}.
      </p>
      {failure ? (
        <p role="alert" className="m-0 text-sm text-danger">
          {failure}
        </p>
      ) : null}
    </div>
  );
}
