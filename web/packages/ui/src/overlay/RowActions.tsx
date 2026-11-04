'use client';

import { type ComponentType, type ReactNode, useEffect, useRef, useState } from 'react';

import { Button } from '../button/Button';
import { type LinkLikeProps } from '../commerce/ProductCard';
import { DotsThreeVertical } from '../icons';
import { Dialog } from './Dialog';
import { Menu, MenuItem, MenuSeparator } from './Menu';
import styles from './overlay.module.css';

/** One choice in a row's "⋯" menu. */
export interface RowAction {
  label: string;
  icon?: ReactNode;
  onSelect?: () => void;
  /** Opens a link instead (use `external` when it leaves the app: opens in a new tab). */
  href?: string;
  external?: boolean;
  /** Destructive (Delete, Discard, Withdraw): red, and always last, below a separator. Confirm it with <ConfirmDelete>. */
  danger?: boolean;
  /** Not available right now. A string says why: it shows under the label and as a tooltip. */
  disabled?: boolean | string;
}

/** Entries of a row menu. Falsy entries are skipped, so `cond && { ... }` works. */
export type RowActionItem = RowAction | 'separator' | false | null | undefined;

export interface RowActionsProps {
  /** The row's name ("Cotton kaftan"): the button's accessible name is "Actions for <name>". */
  name: string;
  /** Replaces "Actions for <name>" (translated). */
  label?: string;
  items: RowActionItem[];
  /** Menu width in px (default 220). */
  width?: number;
  disabled?: boolean;
}

const isAction = (i: RowActionItem): i is RowAction => typeof i === 'object' && i !== null;

/** Page direction, so the menu opens towards the right edge in both reading directions. */
function pageDir(): 'ltr' | 'rtl' {
  return typeof document !== 'undefined' && document.documentElement.dir === 'rtl' ? 'rtl' : 'ltr';
}

/**
 * The one way a table row offers actions: a "⋯" button (last column) that opens a menu. The menu opens in a portal
 * (never clipped by the table's scroll area), flips at the viewport edge, closes on Esc, an outside click or a scroll,
 * and returns focus to the button. Enter, Space or the Down arrow open it; the arrows move; Home/End jump. Destructive
 * items are pushed to the bottom under a separator and coloured red. Put the row's *primary* action in the name
 * (<RowName>), not here.
 */
export function RowActions({ name, label, items, width = 220, disabled }: RowActionsProps) {
  const [open, setOpen] = useState(false);
  const menu = useRef<HTMLDivElement | null>(null);
  const actions = items.filter(isAction);
  const hasSafe = actions.some((a) => !a.danger);
  const danger = actions.filter((a) => a.danger);
  const accessibleName = label ?? `Actions for ${name}`;

  // A scroll outside the menu moves the row away from it: close rather than float over unrelated content.
  useEffect(() => {
    if (!open) return;
    const close = (e: Event) => {
      if (e.target instanceof Node && menu.current?.contains(e.target)) return;
      setOpen(false);
    };
    window.addEventListener('scroll', close, { capture: true, passive: true });
    return () => window.removeEventListener('scroll', close, { capture: true });
  }, [open]);

  if (actions.length === 0) return null;
  const render = (a: RowAction) => (
    <MenuItem key={a.label} icon={a.icon} danger={a.danger} disabled={a.disabled} href={a.href} external={a.external} onSelect={a.onSelect}>
      {a.label}
    </MenuItem>
  );
  // Explicit separators inside the safe group are kept; leading, trailing and doubled ones are dropped.
  const safe: ReactNode[] = [];
  let pendingSeparator = false;
  for (const i of items) {
    if (i === 'separator') {
      pendingSeparator = safe.length > 0;
    } else if (isAction(i) && !i.danger) {
      if (pendingSeparator) safe.push(<MenuSeparator key={`sep-${safe.length}`} />);
      pendingSeparator = false;
      safe.push(render(i));
    }
  }
  return (
    <div className={styles.rowActions} data-no-row-click>
      <Menu
        open={open}
        onOpenChange={setOpen}
        label={accessibleName}
        width={width}
        dir={pageDir()}
        trigger={
          <button type="button" className={styles.rowTrigger} aria-label={accessibleName} title={accessibleName} disabled={disabled}>
            <DotsThreeVertical size={20} weight="bold" aria-hidden />
          </button>
        }
      >
        <div ref={menu}>
          {safe}
          {hasSafe && danger.length > 0 ? <MenuSeparator /> : null}
          {danger.map(render)}
        </div>
      </Menu>
    </div>
  );
}

export interface RowNameProps {
  children: ReactNode;
  /** Where the name goes (the detail or edit page). */
  href?: string;
  /** Or what it does (opens a sheet). One of href / onClick makes the name a link; neither leaves plain bold text. */
  onClick?: () => void;
  /** Link component (e.g. Next.js <Link>); defaults to <a>. */
  linkAs?: ComponentType<LinkLikeProps>;
  /** A status chip next to the name. */
  badge?: ReactNode;
  /** A quiet line under the name (ID, SKU, category). */
  meta?: ReactNode;
}

/** The first cell of a row: the name is the primary link (open / edit), with an optional chip and a small meta line. */
export function RowName({ children, href, onClick, linkAs: LinkAs, badge, meta }: RowNameProps) {
  let name: ReactNode = <strong className={styles.rowNameLink}>{children}</strong>;
  if (href) {
    name = LinkAs ? (
      <LinkAs href={href} className={styles.rowNameLink}>
        {children}
      </LinkAs>
    ) : (
      <a href={href} className={styles.rowNameLink}>
        {children}
      </a>
    );
  } else if (onClick) {
    name = (
      <button type="button" className={styles.rowNameLink} onClick={onClick}>
        {children}
      </button>
    );
  }
  return (
    <span className={styles.rowName}>
      <span className={styles.rowNameTop}>
        {name}
        {badge}
      </span>
      {meta ? <span className={styles.rowNameMeta}>{meta}</span> : null}
    </span>
  );
}

export interface ConfirmDeleteProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  /** Says what is affected: "Discard “Cotton kaftan”?". */
  title: string;
  /** What happens, in plain words ("The draft is deleted. This cannot be undone."). */
  description?: ReactNode;
  /** The destructive button names the verb: "Discard", "Delete", "Withdraw". Never "OK" or "Yes". */
  verb: string;
  cancelLabel?: string;
  closeLabel?: string;
  onConfirm: () => void | Promise<unknown>;
  /** The request is running: the verb button spins and both buttons are off. */
  pending?: boolean;
}

/** Confirmation for a destructive row action in the shared Dialog. Cancel has focus first; the red button is the verb. */
export function ConfirmDelete({ open, onOpenChange, title, description, verb, cancelLabel = 'Cancel', closeLabel, onConfirm, pending = false }: ConfirmDeleteProps) {
  const cancel = useRef<HTMLButtonElement>(null);
  return (
    <Dialog open={open} onOpenChange={(o) => (pending ? undefined : onOpenChange(o))} title={title} description={description} closeLabel={closeLabel} initialFocus={cancel}>
      <div className={styles.confirmActions}>
        <Button ref={cancel} variant="neutral" onClick={() => onOpenChange(false)} disabled={pending}>
          {cancelLabel}
        </Button>
        <Button variant="danger" loading={pending} onClick={() => void onConfirm()}>
          {verb}
        </Button>
      </div>
    </Dialog>
  );
}
