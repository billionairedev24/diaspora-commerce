'use client';

import { type BusinessPageSettings, isApiError, sellerKeys, sellerQueries } from '@afrotransact/api-client';
import { normaliseHex } from '@afrotransact/tokens';
import { Button, buttonClassName, Field, fieldStyles, useToast } from '@afrotransact/ui';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState } from 'react';

import { OfferServices } from '@/components/account/OfferServices';
import { LogoField } from '@/components/onboarding/steps/LogoField';
import { SettingsPage } from '@/components/settings/SettingsShell';
import { BrandColourField } from '@/components/ui/BrandColourField';
import { ErrorState, problemMessage, Spinner } from '@/components/ui/States';
import { sellerApi } from '@/lib/api';
import { useStorefrontUrl } from '@/lib/runtime-config';
import { useSeller } from '@/lib/session';

const MAX_DESCRIPTION = 500;

interface Values {
  description: string;
  brandColor: string;
  logoUrl: string;
}

const valuesOf = (p: BusinessPageSettings): Values => ({ description: p.description ?? '', brandColor: p.brandColor ?? '', logoUrl: p.logoUrl ?? '' });

export function validateBusinessPage(v: Values): Partial<Record<keyof Values, string>> {
  const e: Partial<Record<keyof Values, string>> = {};
  if (v.description.length > MAX_DESCRIPTION) e.description = `At most ${MAX_DESCRIPTION} characters`;
  if (v.brandColor.trim() && normaliseHex(v.brandColor) === null) e.brandColor = 'Choose a colour from the list, or enter a hex code';
  return e;
}

/** Business page settings: the logo, colour and description of the plain public page, changeable at any time. */
export function BusinessPageView() {
  const api = sellerApi();
  const qc = useQueryClient();
  const toast = useToast();
  const storefront = useStorefrontUrl();
  const { orgId } = useSeller();
  const q = useQuery(sellerQueries.businessPage(api, orgId));
  const [v, setV] = useState<Values | null>(null);
  const [errors, setErrors] = useState<Partial<Record<keyof Values | 'form', string>>>({});
  useEffect(() => {
    if (q.data && !v) setV(valuesOf(q.data));
  }, [q.data, v]);

  const save = useMutation({
    mutationFn: (input: Values) => api.updateBusinessPage({ description: input.description.trim(), brandColor: normaliseHex(input.brandColor) ?? input.brandColor.trim(), logoUrl: input.logoUrl }),
    onSuccess: (page) => {
      qc.setQueryData(sellerKeys.businessPage(orgId), page);
      setV(valuesOf(page));
      toast.show('Your business page is updated.');
    },
    onError: (e) => {
      const fields = isApiError(e) ? e.problem.fieldErrors : undefined;
      setErrors(fields ? { ...fields, form: problemMessage(e) } : { form: problemMessage(e) });
    }
  });

  if (!q.data || !v) {
    return <SettingsPage section="business">{q.isError ? <ErrorState error={q.error} onRetry={() => void q.refetch()} /> : <Spinner />}</SettingsPage>;
  }
  const page = q.data;
  const editable = page.canEdit;
  const patch = (p: Partial<Values>) => setV({ ...v, ...p });
  const submit = () => {
    const e = validateBusinessPage(v);
    setErrors(e);
    if (Object.keys(e).length === 0) save.mutate(v);
  };
  const dirty = JSON.stringify(v) !== JSON.stringify(valuesOf(page));
  const accent = normaliseHex(v.brandColor);
  const href = page.slug ? `${storefront.replace(/\/+$/, '')}/b/${page.slug}` : null;

  return (
    <SettingsPage section="business">
      <div className="grid items-start gap-4 lg:grid-cols-[minmax(0,3fr)_minmax(0,2fr)]">
        <form
          className="flex min-w-0 flex-col gap-4"
          onSubmit={(e) => {
            e.preventDefault();
            submit();
          }}
        >
          <LogoField value={v.logoUrl} onChange={(logoUrl) => patch({ logoUrl })} error={errors.logoUrl} disabled={!editable} />
          <BrandColourField value={v.brandColor} onChange={(brandColor) => patch({ brandColor })} error={errors.brandColor} disabled={!editable} />
          <Field label="What you sell or do" help={`A few sentences. ${v.description.length}/${MAX_DESCRIPTION}`} error={errors.description}>
            {(a11y) => (
              <textarea {...a11y} className={fieldStyles.control} rows={5} maxLength={MAX_DESCRIPTION} value={v.description} onChange={(e) => patch({ description: e.target.value })} disabled={!editable} />
            )}
          </Field>
          {errors.form ? (
            <p role="alert" className="m-0 text-sm text-danger">
              {errors.form}
            </p>
          ) : null}
          {editable ? (
            <div className="flex flex-wrap gap-2">
              <Button type="submit" loading={save.isPending} disabled={!dirty}>
                Save changes
              </Button>
              {dirty ? (
                <Button type="button" variant="neutral" onClick={() => { setV(valuesOf(page)); setErrors({}); }}>
                  Discard
                </Button>
              ) : null}
            </div>
          ) : (
            <p className="m-0 text-sm text-muted">Only the owner and admins can change the business page.</p>
          )}
        </form>
        <section aria-label="Preview" className="flex flex-col gap-3 rounded-lg border border-border p-4">
          <span className="text-xs font-semibold tracking-wide text-muted uppercase">Preview</span>
          <div className="flex items-center gap-3">
            {v.logoUrl ? <img src={v.logoUrl} alt="" width={56} height={56} className="size-14 rounded-sm bg-surface object-contain" /> : null}
            <strong className="text-lg" style={accent ? { color: accent } : undefined}>
              {page.name}
            </strong>
          </div>
          <p className="m-0 text-sm whitespace-pre-line">{v.description.trim() || 'Your description appears here.'}</p>
          {accent ? <div aria-hidden className="h-1 rounded-pill" style={{ background: accent }} /> : null}
          {href ? (
            <a href={href} target="_blank" rel="noreferrer" className={buttonClassName({ variant: 'neutral', size: 'sm' })}>
              Open your public page
            </a>
          ) : (
            <p className="m-0 text-xs text-muted">Your page address is made when your payout account is set up.</p>
          )}
        </section>
      </div>
      <OfferServices />
    </SettingsPage>
  );
}
