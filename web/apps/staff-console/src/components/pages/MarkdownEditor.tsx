'use client';

import { type ContentIssue, outlineOf } from '@afrotransact/content';
import { cx, Menu, MenuItem } from '@afrotransact/ui';
import { Anchor, Link as LinkIcon, ListBullets, ListNumbers, Quotes, Table, TextB, TextHThree, TextHTwo, TextItalic } from '@phosphor-icons/react/ssr';
import { type ClipboardEvent, type ReactNode, useCallback, useLayoutEffect, useRef } from 'react';

import { type MessageKey, type Translator, useT } from '@/i18n/t';

import { type Edit, insertLink, insertSectionLink, insertTable, offsetOfLine, prefixLines, toggleHeading, wrap } from './edit';
import { hasStructure, htmlToMarkdown } from './paste';

/** The sentence for a check result. */
export function issueText(t: Translator<MessageKey>, i: ContentIssue): string {
  switch (i.code) {
    case 'empty':
      return t('st.pages.issue.empty');
    case 'duplicate-heading':
      return t('st.pages.issue.duplicateHeading', { subject: i.subject });
    case 'broken-link':
      return i.subject ? t('st.pages.issue.brokenLink', { subject: i.subject }) : t('st.pages.issue.emptyLink');
    case 'broken-anchor':
      return t('st.pages.issue.brokenAnchor', { subject: i.subject });
    case 'legal-marker':
      return t('st.pages.issue.marker', { n: i.count ?? 0 });
    case 'unknown-placeholder':
      return t('st.pages.issue.placeholder', { subject: i.subject });
    case 'text-before-heading':
      return t('st.pages.issue.beforeHeading');
    case 'empty-heading':
      return t('st.pages.issue.emptyHeading');
  }
}

function ToolButton({ label, onClick, children, disabled }: { label: string; onClick: () => void; children: ReactNode; disabled?: boolean }) {
  return (
    <button
      type="button"
      aria-label={label}
      title={label}
      disabled={disabled}
      // The textarea keeps its selection while a toolbar button is pressed.
      onMouseDown={(e) => e.preventDefault()}
      onClick={onClick}
      className="inline-flex size-9 items-center justify-center rounded-sm border border-transparent text-text hover:border-border hover:bg-surface-hover disabled:opacity-50 pointer-coarse:size-11"
    >
      {children}
    </button>
  );
}

export interface MarkdownEditorProps {
  id: string;
  label: string;
  value: string;
  onChange: (value: string) => void;
  issues: readonly ContentIssue[];
  /** The language being written (right to left for Arabic). */
  locale: string;
  disabled?: boolean;
  /** Help pages have no document title and no sub-sub-headings: H3 is still offered. */
  placeholder?: string;
}

/**
 * Markdown editor for non-developers: a toolbar (headings, bold, italic, lists, links, tables, quote, link to a section),
 * paste from Word / Docs / the web converted to Markdown, the outline of the page (click to jump) and the checks
 * (repeated section names, links that go nowhere, counsel markers) with the line each one is on.
 */
export function MarkdownEditor({ id, label, value, onChange, issues, locale, disabled, placeholder }: MarkdownEditorProps) {
  const t = useT();
  const ref = useRef<HTMLTextAreaElement>(null);
  const pending = useRef<{ start: number; end: number } | null>(null);

  // Applies the selection a command asked for once React has written the new text into the textarea.
  useLayoutEffect(() => {
    const ta = ref.current;
    const sel = pending.current;
    if (ta && sel) {
      ta.focus();
      ta.setSelectionRange(sel.start, sel.end);
      pending.current = null;
    }
  });

  const run = useCallback(
    (fn: (e: Edit) => Edit) => {
      const ta = ref.current;
      if (!ta || disabled) return;
      const next = fn({ text: ta.value, start: ta.selectionStart, end: ta.selectionEnd });
      pending.current = { start: next.start, end: next.end };
      onChange(next.text);
    },
    [onChange, disabled]
  );

  const onPaste = (e: ClipboardEvent<HTMLTextAreaElement>) => {
    const html = e.clipboardData.getData('text/html');
    if (!html || !hasStructure(html)) return;
    const md = htmlToMarkdown(html);
    if (!md) return;
    e.preventDefault();
    run((cur) => {
      const text = cur.text.slice(0, cur.start) + md + cur.text.slice(cur.end);
      return { text, start: cur.start + md.length, end: cur.start + md.length };
    });
  };

  const jump = (line: number) => {
    const ta = ref.current;
    if (!ta) return;
    const at = offsetOfLine(ta.value, line);
    ta.focus();
    ta.setSelectionRange(at, at + (ta.value.slice(at).split('\n')[0]?.length ?? 0));
    // Scroll the line into view: approximate by the share of the text above it.
    ta.scrollTop = Math.max(0, (at / Math.max(1, ta.value.length)) * ta.scrollHeight - ta.clientHeight / 3);
  };

  const outline = outlineOf(value);
  const lines = value.split('\n');
  const headingLines = lines.map((l, i) => (l.startsWith('## ') ? i + 1 : 0)).filter(Boolean);
  const errors = issues.filter((i) => i.level === 'error');
  const warnings = issues.filter((i) => i.level === 'warning');
  const duplicated = new Set(issues.filter((i) => i.code === 'duplicate-heading').map((i) => i.subject.toLowerCase()));

  return (
    <div className="flex min-w-0 flex-col gap-2">
      <label htmlFor={id} className="text-sm font-bold">
        {label}
      </label>
      <div className="flex min-w-0 flex-col rounded-md border border-border-strong focus-within:outline-2 focus-within:outline-offset-1 focus-within:outline-focus">
        <div role="toolbar" aria-label={t('st.pages.toolbar')} aria-controls={id} className="flex flex-wrap items-center gap-0.5 border-b border-border bg-surface-muted p-1">
          <ToolButton label={t('st.pages.tool.h2')} disabled={disabled} onClick={() => run((e) => toggleHeading(e, 2))}>
            <TextHTwo size={18} aria-hidden />
          </ToolButton>
          <ToolButton label={t('st.pages.tool.h3')} disabled={disabled} onClick={() => run((e) => toggleHeading(e, 3))}>
            <TextHThree size={18} aria-hidden />
          </ToolButton>
          <span aria-hidden className="mx-1 h-5 w-px bg-border" />
          <ToolButton label={t('st.pages.tool.bold')} disabled={disabled} onClick={() => run((e) => wrap(e, '**', t('st.pages.tool.boldSample')))}>
            <TextB size={18} aria-hidden />
          </ToolButton>
          <ToolButton label={t('st.pages.tool.italic')} disabled={disabled} onClick={() => run((e) => wrap(e, '*', t('st.pages.tool.italicSample')))}>
            <TextItalic size={18} aria-hidden />
          </ToolButton>
          <span aria-hidden className="mx-1 h-5 w-px bg-border" />
          <ToolButton label={t('st.pages.tool.bullets')} disabled={disabled} onClick={() => run((e) => prefixLines(e, 'ul'))}>
            <ListBullets size={18} aria-hidden />
          </ToolButton>
          <ToolButton label={t('st.pages.tool.numbers')} disabled={disabled} onClick={() => run((e) => prefixLines(e, 'ol'))}>
            <ListNumbers size={18} aria-hidden />
          </ToolButton>
          <ToolButton label={t('st.pages.tool.quote')} disabled={disabled} onClick={() => run((e) => prefixLines(e, 'quote'))}>
            <Quotes size={18} aria-hidden />
          </ToolButton>
          <span aria-hidden className="mx-1 h-5 w-px bg-border" />
          <ToolButton label={t('st.pages.tool.link')} disabled={disabled} onClick={() => run(insertLink)}>
            <LinkIcon size={18} aria-hidden />
          </ToolButton>
          <ToolButton label={t('st.pages.tool.table')} disabled={disabled} onClick={() => run(insertTable)}>
            <Table size={18} aria-hidden />
          </ToolButton>
          <Menu
            label={t('st.pages.tool.sectionLink')}
            align="start"
            trigger={
              <button
                type="button"
                aria-label={t('st.pages.tool.sectionLink')}
                title={t('st.pages.tool.sectionLink')}
                disabled={disabled || outline.length === 0}
                onMouseDown={(e) => e.preventDefault()}
                className="inline-flex size-9 items-center justify-center rounded-sm border border-transparent hover:border-border hover:bg-surface-hover disabled:opacity-50 pointer-coarse:size-11"
              >
                <Anchor size={18} aria-hidden />
              </button>
            }
          >
            {outline.map((h) => (
              <MenuItem key={h.id} onSelect={() => run((e) => insertSectionLink(e, h))}>
                {h.title}
              </MenuItem>
            ))}
          </Menu>
        </div>
        <textarea
          ref={ref}
          id={id}
          value={value}
          disabled={disabled}
          spellCheck
          lang={locale}
          dir={locale === 'ar' ? 'rtl' : 'ltr'}
          placeholder={placeholder}
          onChange={(e) => onChange(e.currentTarget.value)}
          onPaste={onPaste}
          aria-describedby={`${id}-checks`}
          aria-invalid={errors.length > 0 || undefined}
          className="min-h-[26rem] w-full resize-y rounded-b-md border-0 bg-surface p-3 font-mono text-sm leading-relaxed text-text outline-none"
        />
      </div>

      <div id={`${id}-checks`} className="grid grid-cols-1 gap-3 lg:grid-cols-2">
        <section aria-label={t('st.pages.outline')} className="flex min-w-0 flex-col gap-1">
          <h3 className="m-0 text-sm font-bold">{t('st.pages.outline')}</h3>
          {outline.length === 0 ? (
            <p className="m-0 text-sm text-muted">{t('st.pages.outlineEmpty')}</p>
          ) : (
            <ol className="m-0 flex list-none flex-col gap-0.5 p-0 text-sm">
              {outline.map((h, i) => (
                <li key={h.id}>
                  <button
                    type="button"
                    onClick={() => jump(headingLines[i] ?? 1)}
                    className={cx('flex w-full items-baseline gap-2 rounded-sm px-2 py-1 text-start hover:bg-surface-hover pointer-coarse:min-h-11', duplicated.has(h.title.toLowerCase()) && 'text-danger')}
                  >
                    <span className="w-6 shrink-0 text-end text-xs text-muted">{i + 1}</span>
                    <span className="min-w-0 flex-1 truncate">{h.title}</span>
                    <code className="shrink-0 text-2xs text-muted">#{h.id}</code>
                  </button>
                </li>
              ))}
            </ol>
          )}
        </section>
        <section aria-label={t('st.pages.checks')} className="flex min-w-0 flex-col gap-1" aria-live="polite">
          <h3 className="m-0 text-sm font-bold">{t('st.pages.checks')}</h3>
          {issues.length === 0 ? (
            <p className="m-0 text-sm text-success">{t('st.pages.checksOk')}</p>
          ) : (
            <ul className="m-0 flex list-none flex-col gap-0.5 p-0 text-sm" data-testid="content-checks">
              {[...errors, ...warnings].map((i, n) => (
                <li key={`${i.code}-${i.subject}-${n}`}>
                  <button
                    type="button"
                    disabled={!i.line}
                    onClick={() => i.line && jump(i.line)}
                    className="flex w-full items-baseline gap-2 rounded-sm px-2 py-1 text-start hover:bg-surface-hover disabled:hover:bg-transparent pointer-coarse:min-h-11"
                  >
                    <span className={cx('shrink-0 rounded-pill px-1.5 text-2xs font-bold', i.level === 'error' ? 'bg-danger-bg text-danger' : 'bg-warning-bg text-warning')}>
                      {i.level === 'error' ? t('st.pages.checkError') : t('st.pages.checkWarning')}
                    </span>
                    <span className="min-w-0 flex-1">{issueText(t, i)}</span>
                    {i.line ? <span className="shrink-0 text-xs text-muted">{t('st.pages.line', { n: i.line })}</span> : null}
                  </button>
                </li>
              ))}
            </ul>
          )}
        </section>
      </div>
    </div>
  );
}
