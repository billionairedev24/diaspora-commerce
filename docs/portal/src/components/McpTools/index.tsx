import snapshot from '@site/src/generated/mcp-tools.json';
import clsx from 'clsx';
import { type ReactNode, useState } from 'react';

import styles from './styles.module.css';

interface ParamSchema {
  type?: string;
  description?: string;
}
interface Tool {
  name: string;
  title?: string;
  description?: string;
  toolset?: string;
  requiredScope?: string;
  requiredPermission?: string;
  readOnly?: boolean;
  destructive?: boolean;
  inputSchema?: { properties?: Record<string, ParamSchema>; required?: string[] };
}
interface Catalogue {
  source: string | null;
  tools: Tool[];
}

const built = snapshot as unknown as Catalogue;
const DEFAULT_BASE = 'http://localhost:8114';

function ToolCard({ tool }: { tool: Tool }): ReactNode {
  const props = Object.entries(tool.inputSchema?.properties ?? {});
  const required = new Set(tool.inputSchema?.required ?? []);
  return (
    <article className={styles.tool} id={`tool-${tool.name}`}>
      <header className={styles.head}>
        <code className={styles.name}>{tool.name}</code>
        <span className={clsx(styles.badge, tool.readOnly ? styles.read : styles.write)}>{tool.readOnly ? 'read-only' : 'writes / proposes'}</span>
      </header>
      {tool.title ? <div className={styles.title}>{tool.title}</div> : null}
      {tool.description ? <p>{tool.description}</p> : null}
      <div className={styles.access}>
        {tool.requiredScope ? (
          <span>
            scope <code>{tool.requiredScope}</code>
          </span>
        ) : null}
        {tool.requiredPermission ? (
          <span>
            permission <code>{tool.requiredPermission}</code>
          </span>
        ) : null}
      </div>
      {props.length ? (
        <div className={styles.tableScroll}>
        <table className={styles.params}>
          <thead>
            <tr>
              <th>Argument</th>
              <th>Type</th>
              <th>Description</th>
            </tr>
          </thead>
          <tbody>
            {props.map(([name, p]) => (
              <tr key={name}>
                <td>
                  <code>{name}</code>
                  {required.has(name) ? <span className={styles.required}>required</span> : null}
                </td>
                <td className={styles.type}>{p.type ?? 'any'}</td>
                <td>{p.description ?? ''}</td>
              </tr>
            ))}
          </tbody>
        </table>
        </div>
      ) : (
        <p className={styles.muted}>No arguments.</p>
      )}
    </article>
  );
}

/** MCP tool catalogue: the build-time snapshot, or live from a running mcp-server's GET /v1/tools. */
export default function McpTools(): ReactNode {
  const [catalogue, setCatalogue] = useState<Catalogue>(built);
  const [base, setBase] = useState(DEFAULT_BASE);
  const [state, setState] = useState<'idle' | 'loading' | 'error'>('idle');
  const [error, setError] = useState('');

  const loadLive = async (): Promise<void> => {
    setState('loading');
    try {
      const url = `${base.replace(/\/$/, '')}/v1/tools`;
      const res = await fetch(url, { headers: { Accept: 'application/json' } });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      setCatalogue({ source: url, tools: (await res.json()) as Tool[] });
      setState('idle');
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      setState('error');
    }
  };

  const toolsets = [...new Set(catalogue.tools.map((t) => t.toolset ?? 'OTHER'))];
  return (
    <div className={styles.wrapper}>
      <div className={styles.live}>
        <label>
          mcp-server base URL
          <input value={base} onChange={(e) => setBase(e.target.value)} aria-label="mcp-server base URL" />
        </label>
        <button type="button" onClick={() => void loadLive()} disabled={state === 'loading'}>
          {state === 'loading' ? 'Loading…' : 'Load live GET /v1/tools'}
        </button>
        {catalogue !== built ? (
          <button type="button" className={styles.secondary} onClick={() => setCatalogue(built)}>
            Back to snapshot
          </button>
        ) : null}
      </div>
      <p className={styles.muted}>
        Showing {catalogue.tools.length} tools from <code>{catalogue.source ?? 'nowhere'}</code>.
        {state === 'error' ? <span className={styles.error}> Live load failed: {error} (is the service running with CORS for this origin?)</span> : null}
      </p>
      {toolsets.map((ts) => (
        <section key={ts}>
          <h2 id={`toolset-${ts.toLowerCase()}`}>{ts.charAt(0) + ts.slice(1).toLowerCase()} tools</h2>
          <div className={styles.grid}>
            {catalogue.tools
              .filter((t) => (t.toolset ?? 'OTHER') === ts)
              .map((t) => (
                <ToolCard key={t.name} tool={t} />
              ))}
          </div>
        </section>
      ))}
    </div>
  );
}
