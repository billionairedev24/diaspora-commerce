'use client';

import { MAX_SMS_SEGMENTS, type NotificationChannel, type NotificationPreview, type SmsSegments } from '@afrotransact/api-client';
import { cx } from '@afrotransact/ui';
import { useCallback, useRef, useState } from 'react';

import { useT } from '@/i18n/t';

const chip = 'inline-flex items-center rounded-pill px-2 py-0.5 text-xs font-bold whitespace-nowrap';

/** Characters, encoding and segments of an SMS (UCS-2 and multi-part messages cost more; more than 3 parts is refused on save). */
export function SmsCount({ sms }: { sms: SmsSegments }) {
  const t = useT();
  const tone = sms.segments > MAX_SMS_SEGMENTS ? 'bg-danger-bg text-danger' : sms.segments > 1 || sms.encoding === 'UCS-2' ? 'bg-warning-bg text-warning' : 'bg-surface-muted text-muted';
  return (
    <p className="m-0 flex flex-wrap gap-1.5" data-testid="sms-count">
      <span className={cx(chip, 'bg-surface-muted text-muted')}>{t('st.ntf.sms.characters', { n: sms.characters })}</span>
      <span className={cx(chip, sms.encoding === 'UCS-2' ? 'bg-warning-bg text-warning' : 'bg-surface-muted text-muted')} title={sms.encoding === 'UCS-2' ? t('st.ntf.sms.ucs2Help') : t('st.ntf.sms.gsmHelp')}>
        {t('st.ntf.sms.encoding', { encoding: sms.encoding, units: sms.units })}
      </span>
      <span className={cx(chip, tone)}>{t('st.ntf.sms.segments', { n: sms.segments, per: sms.perSegment })}</span>
    </p>
  );
}

/** Email HTML in a sandboxed iframe (no scripts), sized to its content, at desktop (640 px) or mobile (375 px) width. */
function EmailFrame({ html, title }: { html: string; title: string }) {
  const t = useT();
  const [width, setWidth] = useState<'desktop' | 'mobile'>(() =>
    typeof window !== 'undefined' && window.matchMedia?.('(max-width: 700px)').matches ? 'mobile' : 'desktop'
  );
  const frame = useRef<HTMLIFrameElement>(null);
  const fit = useCallback(() => {
    const doc = frame.current?.contentDocument;
    if (!frame.current || !doc?.body) return;
    const bottom = doc.body.getBoundingClientRect().bottom + parseFloat(doc.defaultView?.getComputedStyle(doc.body).marginBottom ?? '0');
    frame.current.style.height = `${Math.max(160, Math.ceil(bottom) + 4)}px`;
  }, []);
  const pick = (w: 'desktop' | 'mobile') => {
    setWidth(w);
    setTimeout(fit, 50);
  };
  return (
    <div className="flex flex-col gap-2">
      <div role="group" aria-label={t('st.ntf.preview.width')} className="flex gap-1">
        {(['desktop', 'mobile'] as const).map((w) => (
          <button
            key={w}
            type="button"
            aria-pressed={width === w}
            onClick={() => pick(w)}
            className={cx('rounded-md border px-3 py-1 text-sm font-semibold', width === w ? 'border-ink-1000 bg-ink-1000 text-inverse' : 'border-border bg-surface text-text')}
          >
            {w === 'desktop' ? t('st.ntf.preview.desktop') : t('st.ntf.preview.mobile')}
          </button>
        ))}
      </div>
      {/* The iframe inside is focusable, so keyboard users can reach and scroll this region. */}
      <div className="overflow-x-auto rounded-md bg-surface-muted p-3" role="region" aria-label={t('st.ntf.preview.emailRegion')}>
        <div className={cx('mx-auto max-w-full', width === 'desktop' ? 'w-[640px]' : 'w-[375px]')}>
          {/* allow-same-origin without allow-scripts: no template script ever runs; the console only measures the height. */}
          <iframe ref={frame} title={title} sandbox="allow-same-origin" srcDoc={html} onLoad={fit} className="block h-[420px] w-full rounded-sm border border-border bg-surface" />
        </div>
      </div>
    </div>
  );
}

/** A rendered template as the recipient sees it: email (subject, HTML, text part), SMS bubble, push card or WhatsApp bubble. */
export function PreviewPane({ channel, preview, templateName }: { channel: NotificationChannel; preview: NotificationPreview; templateName?: string }) {
  const t = useT();
  if (channel === 'email') {
    return (
      <div className="flex flex-col gap-3" data-testid="preview-email">
        <p className="m-0 text-md">
          <span className="text-muted">{t('st.ntf.preview.subject')}</span> <strong data-testid="preview-subject">{preview.subject}</strong>
        </p>
        <EmailFrame html={preview.html} title={t('st.ntf.preview.emailTitle', { subject: preview.subject })} />
        <details>
          <summary className="cursor-pointer text-sm font-semibold">{t('st.ntf.preview.textPart')}</summary>
          <pre className="mt-2 mb-0 rounded-sm bg-surface-muted p-3 font-mono text-xs whitespace-pre-wrap">{preview.text}</pre>
        </details>
      </div>
    );
  }
  if (channel === 'push') {
    return (
      <div className="flex max-w-sm items-start gap-3 rounded-lg border border-border bg-surface p-3 shadow-md" data-testid="preview-push">
        <span aria-hidden className="grid size-9 flex-none place-items-center rounded-md bg-ink-1000 text-xs font-black text-gold-500">
          AT
        </span>
        <div className="min-w-0">
          <p className="m-0 text-xs text-muted">{t('st.ntf.preview.pushApp')}</p>
          <p className="m-0 font-bold">{preview.subject}</p>
          <p className="m-0 text-sm">{preview.text}</p>
        </div>
      </div>
    );
  }
  return (
    <div className="flex flex-col gap-2" data-testid={`preview-${channel}`}>
      <div className="max-w-sm rounded-[22px] border border-border bg-surface-sunken p-3">
        <p className="m-0 mb-2 text-center text-xs text-muted">
          <strong className="block text-sm text-text">AfroTransact</strong>
          {channel === 'sms' ? t('st.ntf.preview.smsMeta') : t('st.ntf.preview.chatMeta', { name: templateName || '—' })}
        </p>
        <p className={cx('m-0 max-w-[88%] rounded-[18px] px-3 py-2 text-sm whitespace-pre-wrap', channel === 'sms' ? 'rounded-bl-sm bg-ink-100' : 'rounded-tl-sm bg-green-50')}>
          {preview.text}
        </p>
      </div>
      {preview.sms ? <SmsCount sms={preview.sms} /> : null}
    </div>
  );
}
