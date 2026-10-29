import { type ButtonHTMLAttributes, type FormEvent, type ReactNode, useId } from 'react';

import { cx } from '../cx';
import styles from './flat.module.css';

/* Flat account-page primitives: no boxed panels, hairline rules, left-aligned headings. */

export interface FlatPageProps {
  /** 'form' keeps the page about 960px wide, 'wide' about 1200px (tables). */
  width?: 'form' | 'wide';
  children: ReactNode;
  className?: string;
}

export function FlatPage({ width = 'form', children, className }: FlatPageProps) {
  return <div className={cx(styles.page, width === 'form' ? styles.form : styles.wide, className)}>{children}</div>;
}

export interface BreadcrumbItem {
  label: ReactNode;
  /** The last item is the current page and needs none. */
  href?: string;
}

export interface PageHeaderProps {
  title: ReactNode;
  lead?: ReactNode;
  /** "Your account › Page": every item but the last is a link (rendered with `renderLink`, else a plain anchor). */
  breadcrumb?: { label: string; items: BreadcrumbItem[]; renderLink?: (item: BreadcrumbItem) => ReactNode };
  /** Secondary text links beside the title. */
  actions?: ReactNode;
  titleId?: string;
}

/** Breadcrumb, left-aligned page title (the only h1) and an optional one-line lead. */
export function PageHeader({ title, lead, breadcrumb, actions, titleId }: PageHeaderProps) {
  return (
    <header className={styles.header}>
      {breadcrumb && (
        <nav className={styles.crumb} aria-label={breadcrumb.label}>
          {breadcrumb.items.map((item, i) => {
            const last = i === breadcrumb.items.length - 1;
            const label = last || !item.href ? <span aria-current={last ? 'page' : undefined}>{item.label}</span> : breadcrumb.renderLink ? breadcrumb.renderLink(item) : <a href={item.href}>{item.label}</a>;
            return (
              <span key={i} className={styles.crumbItem}>
                {label}
                {!last && (
                  <span className={styles.crumbSep} aria-hidden>
                    ›
                  </span>
                )}
              </span>
            );
          })}
        </nav>
      )}
      <div className={styles.titleRow}>
        <h1 id={titleId} className={styles.title}>
          {title}
        </h1>
        {actions ? <div className={styles.headerActions}>{actions}</div> : null}
      </div>
      {lead ? <p className={styles.lead}>{lead}</p> : null}
    </header>
  );
}

export interface SectionProps {
  title: ReactNode;
  lead?: ReactNode;
  /** A text link or two aligned to the end of the heading line ("Manage", "Add"). */
  action?: ReactNode;
  id?: string;
  testId?: string;
  children?: ReactNode;
  className?: string;
}

/** A section: h2 over a hairline, then content. Grouping is by rule, never by a box. */
export function Section({ title, lead, action, id, testId, children, className }: SectionProps) {
  const auto = useId();
  const headingId = `${id ?? auto}-h`;
  return (
    <section className={cx(styles.section, className)} aria-labelledby={headingId} id={id} data-testid={testId}>
      <div className={styles.sectionHead}>
        <h2 id={headingId} className={styles.sectionTitle}>
          {title}
        </h2>
        {action ? <span className={styles.sectionAction}>{action}</span> : null}
      </div>
      {lead ? <p className={styles.sectionLead}>{lead}</p> : null}
      {children}
    </section>
  );
}

export interface TextLinkButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  danger?: boolean;
}

/** A button that reads as a text link (secondary actions: Edit, Remove, Set as default). */
export function TextLinkButton({ danger, className, type = 'button', ...rest }: TextLinkButtonProps) {
  return <button type={type} className={cx(styles.textLink, danger && styles.textLinkDanger, className)} {...rest} />;
}

/** Class for an anchor that should read like TextLinkButton. */
export const textLinkClassName = styles.textLink;

export function RowList({ children, label, testId }: { children: ReactNode; label?: string; testId?: string }) {
  return (
    <ul className={styles.rows} aria-label={label} data-testid={testId}>
      {children}
    </ul>
  );
}

export interface RowProps {
  title: ReactNode;
  /** Muted detail under the title. */
  detail?: ReactNode;
  actions?: ReactNode;
  testId?: string;
}

/** One row of a RowList: title and detail on the left, text-link actions at the end, a hairline above. */
export function Row({ title, detail, actions, testId }: RowProps) {
  return (
    <li className={styles.row} data-testid={testId}>
      <div className={styles.rowMain}>
        <div className={styles.rowTitle}>{title}</div>
        {detail ? <div className={styles.rowDetail}>{detail}</div> : null}
      </div>
      {actions ? <div className={styles.rowActions}>{actions}</div> : null}
    </li>
  );
}

export interface DefinitionItem {
  term: ReactNode;
  value: ReactNode;
  testId?: string;
}

/** Definition rows: a muted term, its value, a hairline between rows (plan, next billing date, payment method). */
export function DefinitionList({ items, testId }: { items: DefinitionItem[]; testId?: string }) {
  return (
    <dl className={styles.defs} data-testid={testId}>
      {items.map((it, i) => (
        <div key={i} className={styles.def} data-testid={it.testId}>
          <dt className={styles.defTerm}>{it.term}</dt>
          <dd className={styles.defValue}>{it.value}</dd>
        </div>
      ))}
    </dl>
  );
}

export interface DataColumn<T> {
  key: string;
  header: ReactNode;
  /** Plain-text name used as the cell label when the table stacks on a phone. */
  label: string;
  cell: (row: T) => ReactNode;
  /** Align numbers to the inline end. */
  numeric?: boolean;
}

export interface DataTableProps<T> {
  columns: DataColumn<T>[];
  rows: T[];
  rowKey: (row: T) => string;
  /** Accessible table name. */
  caption: string;
  testId?: string;
  rowTestId?: string;
}

/** A plain table (header rule, hairline rows); on a phone each row stacks as label/value lines. */
export function DataTable<T>({ columns, rows, rowKey, caption, testId, rowTestId }: DataTableProps<T>) {
  return (
    <div className={styles.tableWrap}>
      <table className={cx(styles.table, styles.stack)} data-testid={testId}>
        <caption className="at-visually-hidden">{caption}</caption>
        <thead>
          <tr>
            {columns.map((c) => (
              <th key={c.key} scope="col" className={c.numeric ? styles.num : undefined}>
                {c.header}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => (
            <tr key={rowKey(r)} data-testid={rowTestId}>
              {columns.map((c) => (
                <td key={c.key} data-label={c.label} className={c.numeric ? styles.num : undefined}>
                  {c.cell(r)}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/** Previous / next / more links under a table, with an optional "1–10 of 42" line. */
export function Pager({ info, children, label }: { info?: ReactNode; children: ReactNode; label?: string }) {
  return (
    <nav className={styles.pager} aria-label={label}>
      {info ? <span className={styles.pagerInfo}>{info}</span> : null}
      {children}
    </nav>
  );
}

export interface EmptyStateProps {
  title?: ReactNode;
  children?: ReactNode;
  action?: ReactNode;
  testId?: string;
}

/** What to show when there is nothing yet: one plain sentence and the next step. */
export function EmptyState({ title, children, action, testId }: EmptyStateProps) {
  return (
    <div className={styles.empty} data-testid={testId}>
      {title ? <p className={styles.emptyTitle}>{title}</p> : null}
      {children ? <p className={styles.emptyText}>{children}</p> : null}
      {action}
    </div>
  );
}

export type StatusTone = 'neutral' | 'positive' | 'warning' | 'negative' | 'info';
const TONE: Record<StatusTone, string | undefined> = {
  neutral: styles.statusNeutral,
  positive: styles.statusPositive,
  warning: styles.statusWarning,
  negative: styles.statusNegative,
  info: styles.statusInfo
};

/** A status as coloured words ("Active", "Free trial"), never a pill. */
export function StatusText({ tone = 'neutral', children, testId }: { tone?: StatusTone; children: ReactNode; testId?: string }) {
  return (
    <span className={cx(styles.status, TONE[tone])} data-testid={testId}>
      {children}
    </span>
  );
}

/** An inline message under or beside a form: error and success announce themselves. */
export function Message({ tone = 'muted', children, testId }: { tone?: 'error' | 'success' | 'muted'; children: ReactNode; testId?: string }) {
  return (
    <p
      className={cx(styles.message, tone === 'error' && styles.messageError, tone === 'success' && styles.messageSuccess, tone === 'muted' && styles.messageMuted)}
      role={tone === 'error' ? 'alert' : tone === 'success' ? 'status' : undefined}
      data-testid={testId}
    >
      {children}
    </p>
  );
}

/** Column of labelled fields: labels above, helper text under; size each field with `fieldWidth`. */
export function FormFields({ children, onSubmit, label, testId, className }: { children: ReactNode; onSubmit?: (e: FormEvent<HTMLFormElement>) => void; label?: string; testId?: string; className?: string }) {
  return (
    <form className={cx(styles.fields, className)} onSubmit={onSubmit} noValidate aria-label={label} data-testid={testId}>
      {children}
    </form>
  );
}

/** Field width fitted to its content: 'short' (12rem), 'fit' (24rem), 'wide' (36rem). */
export const fieldWidth = { short: styles.fieldShort, fit: styles.fieldFit, wide: styles.fieldWide } as const;

/** Actions at the bottom-start of a form: one primary button, then text links. */
export function FormActions({ children }: { children: ReactNode }) {
  return <div className={styles.actions}>{children}</div>;
}

export interface InlineFieldProps {
  id: string;
  'aria-describedby': string | undefined;
  'aria-invalid': boolean | undefined;
}

export interface InlineFormRowProps {
  label: ReactNode;
  /** The input: spread the given props on it (id, aria-describedby, aria-invalid). */
  children: (field: InlineFieldProps) => ReactNode;
  button: ReactNode;
  help?: ReactNode;
  /** Inline success or error text under the row (announced to screen readers). */
  message?: { tone: 'error' | 'success'; text: ReactNode; testId?: string } | null;
  fieldId: string;
  onSubmit?: (e: FormEvent<HTMLFormElement>) => void;
  testId?: string;
}

/** Label, field and button on one line ("Redeem a gift card"), wrapping on a phone; help and message under it. */
export function InlineFormRow({ label, children, button, help, message, fieldId, onSubmit, testId }: InlineFormRowProps) {
  const helpId = `${fieldId}-help`;
  const messageId = `${fieldId}-message`;
  const describedBy = [help ? helpId : null, message ? messageId : null].filter(Boolean).join(' ') || undefined;
  return (
    <form className={styles.inline} onSubmit={onSubmit} noValidate data-testid={testId}>
      <label htmlFor={fieldId} className={styles.inlineLabel}>
        {label}
      </label>
      <div className={styles.inlineRow}>
        <div className={styles.inlineField}>{children({ id: fieldId, 'aria-describedby': describedBy, 'aria-invalid': message?.tone === 'error' ? true : undefined })}</div>
        {button}
      </div>
      {help ? (
        <p id={helpId} className={styles.inlineHelp}>
          {help}
        </p>
      ) : null}
      {/* The live region exists before its text does, so a screen reader announces the result. */}
      <div id={messageId} aria-live="polite">
        {message ? (
          <p className={cx(styles.message, message.tone === 'error' ? styles.messageError : styles.messageSuccess)} data-testid={message.testId}>
            {message.text}
          </p>
        ) : null}
      </div>
    </form>
  );
}

/** A single large figure (a balance) in the heading face. */
export function BigFigure({ children, testId }: { children: ReactNode; testId?: string }) {
  return (
    <p className={styles.figure} data-testid={testId}>
      {children}
    </p>
  );
}

export function LinkGrid({ children }: { children: ReactNode }) {
  return <div className={styles.linkGrid}>{children}</div>;
}

/** A titled group of links in the account hub grid. */
export function LinkGroup({ title, children }: { title: ReactNode; children: ReactNode }) {
  const id = useId();
  return (
    <section className={styles.linkGroup} aria-labelledby={id}>
      <h2 id={id} className={styles.sectionTitle}>
        {title}
      </h2>
      <ul className={styles.linkList}>{children}</ul>
    </section>
  );
}

/** Class for the link inside a LinkEntry. */
export const linkEntryClassName = styles.linkItemLink ?? '';

export interface LinkEntryProps {
  /** The link itself (a Next <Link> or <a>) with className={linkEntryClassName}; its text is the name. */
  link: ReactNode;
  text?: ReactNode;
  /** A live fact in small text ("Balance $25.00"). */
  fact?: ReactNode;
  testId?: string;
}

export function LinkEntry({ link, text, fact, testId }: LinkEntryProps) {
  return (
    <li className={styles.linkItem} data-testid={testId}>
      {link}
      {text ? <span className={styles.linkItemText}>{text}</span> : null}
      {fact ? <span className={styles.linkItemFact}>{fact}</span> : null}
    </li>
  );
}

/** A row of filters above a table (labels beside short selects, wrapping on a phone). */
export function FilterBar({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className={styles.filters} role="group" aria-label={label}>
      {children}
    </div>
  );
}

export interface FilterSelectProps {
  label: string;
  value: string;
  onChange: (value: string) => void;
  options: Array<{ value: string; label: string }>;
  testId?: string;
}

export function FilterSelect({ label, value, onChange, options, testId }: FilterSelectProps) {
  return (
    <label className={styles.filter}>
      {label}
      <select className={styles.filterSelect} value={value} onChange={(e) => onChange(e.target.value)} data-testid={testId}>
        {options.map((o) => (
          <option key={o.value} value={o.value}>
            {o.label}
          </option>
        ))}
      </select>
    </label>
  );
}
