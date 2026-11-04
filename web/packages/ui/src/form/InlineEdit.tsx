'use client';

import { type FormEvent, type KeyboardEvent, type ReactNode, useEffect, useId, useRef, useState } from 'react';

import { Check, PencilSimple, X } from '../icons';
import styles from './InlineEdit.module.css';

export interface InlineEditProps {
  /** The current value as the input shows it ("12", "19.99"). */
  value: string;
  /** How the value reads when not editing (formatted price, stock with a low-stock colour). */
  children: ReactNode;
  /** Accessible name of the cell button: "Edit stock of Cotton kaftan". */
  label: string;
  /** Names of the field and its two buttons (translated). */
  inputLabel?: string;
  saveLabel?: string;
  cancelLabel?: string;
  inputMode?: 'numeric' | 'decimal' | 'text';
  /** Returns an error message to keep editing, or nothing when the draft is fine. */
  validate?: (draft: string) => string | undefined;
  /** Saves the draft. Reject (throw) to keep the editor open: the message shows under the field. */
  onSave: (draft: string) => Promise<unknown> | void;
  /** Read-only: shows the value without the click-to-edit affordance. */
  readOnly?: boolean;
  /** Text width of the editor in characters (default 8). */
  width?: number;
}

/**
 * Click-to-edit cell (price, stock): looks like the value with a faint dotted underline and a pencil that appears on
 * hover and focus. Click or Enter opens a small field with Save and Cancel; Enter saves, Esc cancels, and nothing is
 * sent when the value did not change.
 */
export function InlineEdit({ value, children, label, inputLabel, saveLabel = 'Save', cancelLabel = 'Cancel', inputMode = 'text', validate, onSave, readOnly, width = 8 }: InlineEditProps) {
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState(value);
  const [error, setError] = useState<string | undefined>();
  const [saving, setSaving] = useState(false);
  const input = useRef<HTMLInputElement>(null);
  const trigger = useRef<HTMLButtonElement>(null);
  const errorId = useId();
  const wasEditing = useRef(false);

  useEffect(() => {
    if (editing) {
      input.current?.focus();
      input.current?.select();
    } else if (wasEditing.current) {
      trigger.current?.focus();
    }
    wasEditing.current = editing;
  }, [editing]);

  if (readOnly) return <span>{children}</span>;

  const start = () => {
    setDraft(value);
    setError(undefined);
    setEditing(true);
  };
  const cancel = () => {
    if (saving) return;
    setEditing(false);
    setError(undefined);
  };
  const save = async (e?: FormEvent) => {
    e?.preventDefault();
    if (saving) return;
    const text = draft.trim();
    if (text === value) {
      setEditing(false);
      return;
    }
    const problem = validate?.(text);
    if (problem) {
      setError(problem);
      return;
    }
    setSaving(true);
    try {
      await onSave(text);
      setEditing(false);
      setError(undefined);
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setSaving(false);
    }
  };
  const keys = (e: KeyboardEvent) => {
    if (e.key === 'Escape') {
      e.preventDefault();
      e.stopPropagation();
      cancel();
    }
  };

  if (!editing) {
    return (
      <button ref={trigger} type="button" className={styles.value} onClick={start} aria-label={label} title={label}>
        <span>{children}</span>
        <PencilSimple size={12} aria-hidden className={styles.pencil} />
      </button>
    );
  }
  return (
    <form className={styles.editor} onSubmit={(e) => void save(e)} noValidate data-no-row-click>
      <span className={styles.row}>
        <input
          ref={input}
          className={styles.input}
          style={{ width: `${width}ch` }}
          value={draft}
          inputMode={inputMode}
          aria-label={inputLabel ?? label}
          aria-invalid={error ? true : undefined}
          aria-describedby={error ? errorId : undefined}
          disabled={saving}
          onKeyDown={keys}
          onChange={(e) => setDraft(e.currentTarget.value)}
        />
        <button type="submit" className={styles.iconButton} aria-label={saveLabel} title={saveLabel} disabled={saving}>
          <Check size={16} weight="bold" aria-hidden />
        </button>
        <button type="button" className={styles.iconButton} aria-label={cancelLabel} title={cancelLabel} onClick={cancel} disabled={saving}>
          <X size={16} aria-hidden />
        </button>
      </span>
      {error ? (
        <span id={errorId} role="alert" className={styles.error}>
          {error}
        </span>
      ) : null}
    </form>
  );
}
