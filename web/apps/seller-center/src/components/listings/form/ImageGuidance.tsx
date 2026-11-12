'use client';

import { ProgressMeter } from '@afrotransact/ui';
import { CheckCircle, Circle, Hourglass, Warning, XCircle } from '@phosphor-icons/react/ssr';

import { type MessageKey, useT } from '@/i18n/t';

import { type FormImage, MAIN_FILL_PERCENT, MIN_IMAGE_PX } from '../form-model';
import { EXTRA_PHOTOS_TARGET, extraPhotoCount, mainRuleStates, RULE_IDS, type RuleId, type RuleState } from './image-rules';

const STATE_ICON = { ok: CheckCircle, fail: XCircle, warn: Warning, pending: Hourglass, todo: Circle } as const;
const STATE_TONE: Record<RuleState, string> = { ok: 'text-success', fail: 'text-danger', warn: 'text-warning', pending: 'text-info', todo: 'text-muted' };

/**
 * The main photo rules before the upload and live after it (S391): each rule is a line with its state written out and an icon, so
 * nothing is colour alone. The list is the same one media enforces: 1000 px on the longest side, a pure white background, the product
 * filling most of the frame, and no text, logos, watermarks or borders.
 */
export function MainImageRules({ main }: { main: FormImage | undefined }) {
  const t = useT();
  const states = mainRuleStates(main);
  const text = (id: RuleId) => t(`sc.pf.images.rule.${id}` as MessageKey, { px: MIN_IMAGE_PX, fill: MAIN_FILL_PERCENT });
  return (
    <div className="flex flex-col gap-1" data-testid="main-image-rules">
      <h3 className="m-0 text-sm font-semibold" id="pf-main-rules">{t('sc.pf.images.mainRules.title')}</h3>
      <ul className="m-0 flex list-none flex-col gap-1 p-0" aria-labelledby="pf-main-rules" aria-live="polite">
        {RULE_IDS.map((id) => {
          const state = states[id];
          const Icon = STATE_ICON[state];
          return (
            <li key={id} data-rule={id} data-state={state} className="flex items-start gap-2 text-sm">
              <Icon size={18} weight={state === 'todo' ? 'regular' : 'fill'} className={`mt-0.5 shrink-0 ${STATE_TONE[state]}`} aria-hidden />
              <span>
                {text(id)}
                <span className={`ms-2 text-xs ${STATE_TONE[state]}`}>{t(`sc.pf.images.rule.state.${state}` as MessageKey)}</span>
              </span>
            </li>
          );
        })}
      </ul>
    </div>
  );
}

/** "3 of 7 extra photos": a recommendation, never a requirement (more photos sell better). */
export function ExtraPhotoMeter({ images }: { images: FormImage[] }) {
  const t = useT();
  const n = extraPhotoCount(images);
  return (
    <ProgressMeter
      className="max-w-xl"
      label={t('sc.pf.images.meterTitle')}
      value={Math.min(n, EXTRA_PHOTOS_TARGET)}
      max={EXTRA_PHOTOS_TARGET}
      valueText={t('sc.pf.images.meter', { n, total: EXTRA_PHOTOS_TARGET })}
      help={t('sc.pf.images.meterHint')}
      attention={n < EXTRA_PHOTOS_TARGET}
    />
  );
}
