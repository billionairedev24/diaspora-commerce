'use client';

import { staffPreorderKeys } from '@afrotransact/api-client';
import { PREORDER_SWITCH_LABEL, type PreorderSwitchState } from '@afrotransact/api-client/staff-preorders';
import { cx, useToast } from '@afrotransact/ui';
import { useMutation, useQueryClient } from '@tanstack/react-query';

import { problemMessage } from '@/components/ui/States';

import { NO_EDIT_TITLE } from './model';
import { Toggle, usePreorderAccess, usePreordersApi } from './primitives';

/** What a window's own switch needs: it is never the master switch. */
export interface SwitchableWindow {
  id: string;
  name: string;
  active: boolean;
  switchable: boolean;
  switchState: PreorderSwitchState;
}

/** Text next to the toggle: "On", "Off" (this pre-order), "Off (global switch)", "Area off". */
export const switchText = (w: Pick<SwitchableWindow, 'switchState'>) => PREORDER_SWITCH_LABEL[w.switchState];

/** Switches one or several pre-orders on or off (each its own switch; the master switch is untouched). */
export function useSwitchPreorders() {
  const api = usePreordersApi();
  const qc = useQueryClient();
  const toast = useToast();
  const refresh = () => qc.invalidateQueries({ queryKey: staffPreorderKeys.all });
  const one = useMutation({
    mutationFn: ({ w, active }: { w: SwitchableWindow; active: boolean }) => api.setActive(w.id, active),
    onSuccess: (next) => {
      qc.setQueryData(staffPreorderKeys.window(next.id), next);
      void refresh();
      toast.show(`${next.name} switched ${next.active ? 'on' : 'off'}${next.switchState === 'GLOBAL_OFF' ? ' (the global switch is still off)' : ''}`);
    },
    onError: (e) => {
      void refresh();
      toast.show(problemMessage(e), 'error');
    }
  });
  const many = useMutation({
    mutationFn: ({ ids, active }: { ids: string[]; active: boolean }) => api.setActiveMany(ids, active),
    onSuccess: (r, v) => {
      void refresh();
      const parts = [`${r.switched} switched ${v.active ? 'on' : 'off'}`];
      if (r.unchanged) parts.push(`${r.unchanged} already ${v.active ? 'on' : 'off'}`);
      if (r.skipped) parts.push(`${r.skipped} skipped (${[...new Set(r.items.filter((i) => i.outcome === 'SKIPPED').map((i) => i.reason ?? 'not switchable'))].join('; ')})`);
      toast.show(parts.join(' · '), r.skipped && !r.switched ? 'error' : undefined);
    },
    onError: (e) => toast.show(problemMessage(e), 'error')
  });
  return { one, many };
}

/** The per-pre-order on/off switch (list row and detail header). Drafts and delivered windows have none. */
export function WindowSwitchToggle({ w, size = 'sm', className }: { w: SwitchableWindow; size?: 'sm' | 'md'; className?: string }) {
  const access = usePreorderAccess();
  const { one } = useSwitchPreorders();
  if (!w.switchable) return <span className="text-2xs text-subtle">—</span>;
  const pending = one.isPending && one.variables?.w.id === w.id;
  const held = w.active && w.switchState !== 'ON';
  return (
    <span className={cx('flex items-center gap-2', className)} data-testid="window-switch" data-state={w.switchState}>
      <Toggle
        on={w.active}
        size={size}
        label={`Switch ${w.name} on or off`}
        disabled={!access.canEdit || pending}
        title={!access.canEdit ? NO_EDIT_TITLE : held ? `${w.name} is on, but ${w.switchState === 'GLOBAL_OFF' ? 'the global pre-order switch is off' : 'every one of its areas is off'}` : undefined}
        className={held ? 'opacity-60' : undefined}
        onClick={(e) => {
          e.stopPropagation();
          one.mutate({ w, active: !w.active });
        }}
      />
      <span className={cx('text-2xs whitespace-nowrap', w.switchState === 'ON' ? 'text-green-800' : 'text-muted')}>{switchText(w)}</span>
    </span>
  );
}
