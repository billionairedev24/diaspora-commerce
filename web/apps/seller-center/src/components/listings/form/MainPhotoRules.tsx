'use client';

import { CheckCircle, Circle, Hourglass, Warning, XCircle } from '@phosphor-icons/react/ssr';

import { type MessageKey, useT } from '@/i18n/t';

import { type FormImage, IMAGE_TYPES, MAIN_FILL_PERCENT, MAX_IMAGE_BYTES, MIN_IMAGE_PX } from '../form-model';
import { mainRuleStates, RULE_IDS, type RuleId, type RuleState } from './image-rules';

const STATE_ICON = { ok: CheckCircle, fail: XCircle, warn: Warning, pending: Hourglass, todo: Circle } as const;
const STATE_TONE: Record<RuleState, string> = { ok: 'text-success', fail: 'text-danger', warn: 'text-warning', pending: 'text-info', todo: 'text-muted' };

/**
 * The content of the info popover next to the main photo: the rules media enforces (the numbers are the ones the form reads, not copies),
 * each with its live state written out and an icon, so nothing is colour alone. Before an upload every rule says "Not checked yet".
 */
export function MainPhotoRules({ main }: { main: FormImage | undefined }) {
  const t = useT();
  const states = mainRuleStates(main);
  const text = (id: RuleId) => t(`sc.pf.images.rule.${id}` as MessageKey, { px: MIN_IMAGE_PX, fill: MAIN_FILL_PERCENT });
  const types = IMAGE_TYPES.map((x) => x.replace('image/', '').replace('jpeg', 'jpg').toUpperCase()).join(', ');
  return (
    <div className="flex flex-col gap-2" data-testid="main-image-rules">
      <strong className="text-sm">{t('sc.pf.images.mainRules.title')}</strong>
      <ul className="m-0 flex list-none flex-col gap-1.5 p-0">
        {RULE_IDS.map((id) => {
          const state = states[id];
          const Icon = STATE_ICON[state];
          return (
            <li key={id} data-rule={id} data-state={state} className="flex items-start gap-2">
              <Icon size={16} weight={state === 'todo' ? 'regular' : 'fill'} className={`mt-0.5 shrink-0 ${STATE_TONE[state]}`} aria-hidden />
              <span>
                {text(id)}
                <span className={`ms-2 text-xs font-semibold ${STATE_TONE[state]}`}>{t(`sc.pf.images.rule.state.${state}` as MessageKey)}</span>
              </span>
            </li>
          );
        })}
      </ul>
      <span className="text-xs text-muted">{t('sc.pf.images.fileRules', { types, mb: MAX_IMAGE_BYTES / (1024 * 1024) })}</span>
    </div>
  );
}
