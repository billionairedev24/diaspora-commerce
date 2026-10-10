import { type ReactNode } from 'react';

import styles from './styles.module.css';
import type { JsonSchema } from './types';

const SCHEMA_PREFIX = '#/components/schemas/';

interface Ctx {
  schemas: Record<string, JsonSchema>;
  seen: string[];
  depth: number;
}

function refName(ref?: string): string | null {
  return ref?.startsWith(SCHEMA_PREFIX) ? decodeURIComponent(ref.slice(SCHEMA_PREFIX.length)) : null;
}

function deref(schema: JsonSchema, ctx: Ctx): { schema: JsonSchema; name: string | null } {
  const name = refName(schema.$ref);
  if (!name) return { schema, name: null };
  return { schema: ctx.schemas[name] ?? { description: `Unresolved reference ${schema.$ref}` }, name };
}

function typeLabel(raw: JsonSchema, ctx: Ctx): string {
  const { schema, name } = deref(raw, ctx);
  if (name) return name;
  const types = Array.isArray(schema.type) ? schema.type : schema.type ? [schema.type] : [];
  let label = types.filter((t) => t !== 'null').join(' | ') || (schema.properties ? 'object' : schema.allOf || schema.oneOf || schema.anyOf ? 'composed' : 'any');
  if (label === 'array' && schema.items) label = `${typeLabel(schema.items, ctx)}[]`;
  if (schema.format) label += ` (${schema.format})`;
  if (types.includes('null') || schema.nullable) label += ' | null';
  return label;
}

const fmt = (v: unknown): string => (typeof v === 'string' ? v : JSON.stringify(v));

function constraints(schema: JsonSchema): string[] {
  const out: string[] = [];
  if (schema.enum) out.push(`one of ${schema.enum.map(fmt).join(', ')}`);
  if (schema.const !== undefined) out.push(`always ${fmt(schema.const)}`);
  if (schema.pattern) out.push(`pattern ${schema.pattern}`);
  if (schema.minimum !== undefined) out.push(`≥ ${schema.minimum}`);
  if (schema.maximum !== undefined) out.push(`≤ ${schema.maximum}`);
  if (schema.minLength !== undefined) out.push(`min length ${schema.minLength}`);
  if (schema.maxLength !== undefined) out.push(`max length ${schema.maxLength}`);
  if (schema.minItems !== undefined) out.push(`min items ${schema.minItems}`);
  if (schema.maxItems !== undefined) out.push(`max items ${schema.maxItems}`);
  if (schema.default !== undefined) out.push(`default ${fmt(schema.default)}`);
  if (schema.example !== undefined) out.push(`e.g. ${fmt(schema.example)}`);
  return out;
}

/** The object-like schema to expand below a property row, if any. */
function nested(raw: JsonSchema, ctx: Ctx): { schema: JsonSchema; name: string | null } | null {
  let { schema, name } = deref(raw, ctx);
  if (schema.type === 'array' && schema.items) ({ schema, name } = deref(schema.items, ctx));
  if (schema.properties || schema.allOf || schema.oneOf || schema.anyOf) return { schema, name };
  if (schema.additionalProperties && typeof schema.additionalProperties === 'object') return { schema: schema.additionalProperties, name: 'map value' };
  return null;
}

function Properties({ schema, ctx }: { schema: JsonSchema; ctx: Ctx }): ReactNode {
  const props = Object.entries(schema.properties ?? {});
  const required = new Set(schema.required ?? []);
  if (!props.length) return null;
  return (
    <div className={styles.tableScroll}>
    <table className={styles.schemaTable}>
      <thead>
        <tr>
          <th>Field</th>
          <th>Type</th>
          <th>Description</th>
        </tr>
      </thead>
      <tbody>
        {props.map(([prop, raw]) => {
          const { schema: s } = deref(raw, ctx);
          const child = nested(raw, ctx);
          const childName = child?.name;
          const cyclic = childName ? ctx.seen.includes(childName) : false;
          const notes = constraints(s.type === 'array' && s.items ? { ...s, ...deref(s.items, ctx).schema, description: s.description } : s);
          return (
            <tr key={prop}>
              <td>
                <code>{prop}</code>
                {required.has(prop) ? <span className={styles.required}>required</span> : null}
              </td>
              <td className={styles.typeCell}>{typeLabel(raw, ctx)}</td>
              <td>
                {s.description ?? raw.description ?? ''}
                {notes.length ? <div className={styles.constraints}>{notes.join(' · ')}</div> : null}
                {child && !cyclic && ctx.depth < 6 ? (
                  <details className={styles.nested}>
                    <summary>{childName ? `${childName} fields` : 'fields'}</summary>
                    <SchemaBody schema={child.schema} ctx={{ ...ctx, depth: ctx.depth + 1, seen: childName ? [...ctx.seen, childName] : ctx.seen }} />
                  </details>
                ) : null}
                {cyclic ? <div className={styles.constraints}>recursive: see {childName} above</div> : null}
              </td>
            </tr>
          );
        })}
      </tbody>
    </table>
    </div>
  );
}

function SchemaBody({ schema, ctx }: { schema: JsonSchema; ctx: Ctx }): ReactNode {
  const composed = (['allOf', 'oneOf', 'anyOf'] as const).filter((k) => schema[k]?.length);
  return (
    <>
      <Properties schema={schema} ctx={ctx} />
      {composed.map((k) => (
        <div key={k} className={styles.composed}>
          <div className={styles.composedLabel}>{k === 'allOf' ? 'All of' : k === 'oneOf' ? 'One of' : 'Any of'}</div>
          {schema[k]!.map((variant, i) => {
            const { schema: v, name } = deref(variant, ctx);
            const cyclic = name ? ctx.seen.includes(name) : false;
            return (
              <div key={`${k}-${i}`} className={styles.variant}>
                <div className={styles.variantName}>{name ?? typeLabel(variant, ctx)}</div>
                {!cyclic && ctx.depth < 6 ? <SchemaBody schema={v} ctx={{ ...ctx, depth: ctx.depth + 1, seen: name ? [...ctx.seen, name] : ctx.seen }} /> : null}
              </div>
            );
          })}
        </div>
      ))}
      {!schema.properties && !composed.length ? (
        <p className={styles.constraints}>
          {typeLabel(schema, ctx)}
          {constraints(schema).length ? ` · ${constraints(schema).join(' · ')}` : ''}
        </p>
      ) : null}
    </>
  );
}

export default function SchemaView({ schema, schemas }: { schema: JsonSchema; schemas: Record<string, JsonSchema> }): ReactNode {
  const name = refName(schema.$ref);
  const ctx: Ctx = { schemas, seen: name ? [name] : [], depth: 0 };
  const { schema: resolved } = deref(schema, ctx);
  return (
    <div className={styles.schema}>
      {name ? <div className={styles.schemaName}>{name}</div> : null}
      {resolved.description ? <p className={styles.schemaDescription}>{resolved.description}</p> : null}
      <SchemaBody schema={resolved} ctx={ctx} />
    </div>
  );
}
