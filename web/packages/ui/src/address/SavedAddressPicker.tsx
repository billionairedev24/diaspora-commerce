'use client';

import { type ReactNode, useId, useState } from 'react';

import { cx } from '../cx';
import { MapPin, Plus } from '../icons';
import styles from './SavedAddressPicker.module.css';

/** One saved address as the picker shows it. */
export interface SavedAddressOption {
  id: string;
  /** The recipient's name (first line, bold). */
  name: ReactNode;
  /** The address, one string per line ("9800 Westheimer Rd, Apt 4", "Houston, TX 77042"). */
  lines: string[];
  /** Phone or a note under the address. */
  meta?: ReactNode;
  isDefault?: boolean;
}

export interface SavedAddressPickerLabels {
  change: string;
  /** Collapses the list without changing the address. */
  keep: string;
  defaultTag: string;
  addNew: string;
  /** Heading of the other addresses ("Your other addresses"). */
  others: string;
  /** When there is no saved address yet. */
  empty: string;
  /** Accessible name of the Change button, e.g. "Change delivery address". */
  changeAria?: string;
  /** Accessible name of each option, e.g. (name) => `Deliver to ${name}`. */
  useAria?: (option: SavedAddressOption) => string;
}

export const DEFAULT_PICKER_LABELS: SavedAddressPickerLabels = {
  change: 'Change',
  keep: 'Cancel',
  defaultTag: 'Default',
  addNew: 'Add a new address',
  others: 'Your other addresses',
  empty: 'No saved addresses yet.'
};

export interface SavedAddressPickerProps {
  addresses: SavedAddressOption[];
  /** The address in use; null picks the default (or the first). */
  selectedId: string | null;
  onSelect: (id: string) => void;
  /** Opens the standard address form (prefilled with the person's name and phone by the caller). */
  onAddNew: () => void;
  labels?: Partial<SavedAddressPickerLabels>;
  /** Controlled list visibility (e.g. a "change address" link elsewhere on the page opens it). */
  expanded?: boolean;
  onExpandedChange?: (expanded: boolean) => void;
  /** Extra actions on the address in use (e.g. Edit). */
  actions?: (option: SavedAddressOption) => ReactNode;
  testId?: string;
  className?: string;
}

/** The selected address of `addresses`: the chosen one, else the default, else the first. */
export function selectedAddress<T extends { id: string; isDefault?: boolean }>(addresses: T[], selectedId: string | null): T | null {
  return addresses.find((a) => a.id === selectedId) ?? addresses.find((a) => a.isDefault) ?? addresses[0] ?? null;
}

/**
 * The standard saved-address chooser: only the address in use (the default until another is picked) with Change;
 * Change lists every other saved address, then "Add a new address". Picking one uses it and closes the list.
 */
export function SavedAddressPicker({
  addresses,
  selectedId,
  onSelect,
  onAddNew,
  labels: labelsProp,
  expanded: expandedProp,
  onExpandedChange,
  actions,
  testId = 'saved-address-picker',
  className
}: SavedAddressPickerProps) {
  const l = { ...DEFAULT_PICKER_LABELS, ...labelsProp };
  const listId = `sap${useId().replace(/:/g, '')}`;
  const [own, setOwn] = useState(false);
  const expanded = expandedProp ?? own;
  const setExpanded = (v: boolean) => {
    if (expandedProp === undefined) setOwn(v);
    onExpandedChange?.(v);
  };
  const current = selectedAddress(addresses, selectedId);
  const others = addresses.filter((a) => a.id !== current?.id);

  const card = (a: SavedAddressOption) => (
    <span className={styles.text}>
      <strong className={styles.name}>{a.name}</strong>
      {a.lines.map((line) => (
        <span key={line}>{line}</span>
      ))}
      {a.meta ? <span className={styles.meta}>{a.meta}</span> : null}
    </span>
  );

  return (
    <div className={cx(styles.picker, className)} data-testid={testId} data-expanded={expanded ? 'true' : 'false'}>
      {current ? (
        <div className={styles.current} data-testid="selected-address">
          <MapPin className={styles.pin} aria-hidden />
          {card(current)}
          {current.isDefault ? <span className={styles.tag}>{l.defaultTag}</span> : null}
          <span className={styles.currentActions}>
            {actions?.(current)}
            <button
              type="button"
              className={styles.link}
              aria-expanded={expanded}
              aria-controls={listId}
              aria-label={expanded ? undefined : l.changeAria}
              onClick={() => setExpanded(!expanded)}
            >
              {expanded ? l.keep : l.change}
            </button>
          </span>
        </div>
      ) : (
        <p className={styles.empty}>{l.empty}</p>
      )}
      {(expanded || !current) && (
        <div id={listId} className={styles.others}>
          {others.length > 0 && (
            <>
              <p className={styles.othersTitle}>{l.others}</p>
              <ul className={styles.list}>
                {others.map((a) => (
                  <li key={a.id}>
                    <button
                      type="button"
                      className={styles.option}
                      data-testid="other-address"
                      aria-label={l.useAria?.(a)}
                      onClick={() => {
                        onSelect(a.id);
                        setExpanded(false);
                      }}
                    >
                      {card(a)}
                      {a.isDefault ? <span className={styles.tag}>{l.defaultTag}</span> : null}
                    </button>
                  </li>
                ))}
              </ul>
            </>
          )}
          <button type="button" className={styles.addNew} onClick={onAddNew}>
            <Plus aria-hidden /> {l.addNew}
          </button>
        </div>
      )}
    </div>
  );
}
