import rawBacklog from '@site/src/generated/backlog.json';
import clsx from 'clsx';
import { type ReactNode, useMemo, useState } from 'react';

import styles from './styles.module.css';

interface Story {
  id: string;
  type: string;
  summary: string;
  description: string;
  acceptance: string;
  parent: string;
  priority: string;
  points: number;
  labels: string;
  component: string;
  status: string;
}
interface Epic {
  id: string;
  summary: string;
  description: string;
  status: string;
  priority: string;
  total: number;
  done: number;
  inProgress: number;
  points: number;
  donePoints: number;
}
interface Backlog {
  epics: Epic[];
  stories: Story[];
}

const backlog = rawBacklog as unknown as Backlog;
const STATUSES = ['All', 'Done', 'In Progress', 'To Do'] as const;

function StatusBadge({ status }: { status: string }): ReactNode {
  const kind = status === 'Done' ? styles.done : status === 'In Progress' ? styles.progress : styles.todo;
  return <span className={clsx(styles.badge, kind)}>{status}</span>;
}

function Progress({ done, inProgress, total }: { done: number; inProgress: number; total: number }): ReactNode {
  const pct = (n: number): string => `${total ? (n / total) * 100 : 0}%`;
  return (
    <div className={styles.bar} role="img" aria-label={`${done} of ${total} stories done`}>
      <span className={styles.barDone} style={{ width: pct(done) }} />
      <span className={styles.barProgress} style={{ width: pct(inProgress) }} />
    </div>
  );
}

/** Epics with done/total and every story with its status, from docs/backlog/backlog.csv at build time. */
export default function BacklogStatus(): ReactNode {
  const [epic, setEpic] = useState('All');
  const [status, setStatus] = useState<(typeof STATUSES)[number]>('All');
  const [query, setQuery] = useState('');
  const totals = useMemo(() => {
    const s = backlog.stories;
    return {
      stories: s.length,
      done: s.filter((x) => x.status === 'Done').length,
      inProgress: s.filter((x) => x.status === 'In Progress').length,
      points: s.reduce((n, x) => n + x.points, 0),
      donePoints: s.filter((x) => x.status === 'Done').reduce((n, x) => n + x.points, 0),
    };
  }, []);
  const stories = useMemo(() => {
    const q = query.trim().toLowerCase();
    return backlog.stories.filter(
      (s) =>
        (epic === 'All' || s.parent === epic) &&
        (status === 'All' || s.status === status) &&
        (!q || `${s.id} ${s.summary} ${s.labels} ${s.component}`.toLowerCase().includes(q)),
    );
  }, [epic, status, query]);
  const epicName = new Map(backlog.epics.map((e) => [e.id, e.summary]));

  return (
    <div className={styles.wrapper}>
      <div className={styles.stats}>
        <div>
          <strong>
            {totals.done}/{totals.stories}
          </strong>
          <span>stories done</span>
        </div>
        <div>
          <strong>{totals.inProgress}</strong>
          <span>in progress</span>
        </div>
        <div>
          <strong>
            {totals.donePoints}/{totals.points}
          </strong>
          <span>story points done</span>
        </div>
        <div>
          <strong>{backlog.epics.length}</strong>
          <span>epics</span>
        </div>
      </div>

      <h2 id="epics">Epics</h2>
      <p className={styles.legend}>
        <span className={styles.legendDone} /> done <span className={styles.legendProgress} /> in progress — click an epic to filter its stories.
      </p>
      <div className={styles.tableWrap}>
        <table className={styles.table}>
          <thead>
            <tr>
              <th>Epic</th>
              <th>Status</th>
              <th className={styles.num}>Done</th>
              <th className={styles.progressCol}>Progress</th>
              <th className={styles.num}>Points</th>
            </tr>
          </thead>
          <tbody>
            {backlog.epics.map((e) => (
              <tr key={e.id} className={clsx(epic === e.id && styles.selected)}>
                <td>
                  <button type="button" className={styles.epicButton} onClick={() => setEpic(epic === e.id ? 'All' : e.id)} title="Show this epic's stories">
                    <span className={styles.id}>{e.id}</span> {e.summary}
                  </button>
                </td>
                <td>
                  <StatusBadge status={e.status} />
                </td>
                <td className={styles.num}>
                  {e.done}/{e.total}
                </td>
                <td className={styles.progressCol}>
                  <Progress done={e.done} inProgress={e.inProgress} total={e.total} />
                </td>
                <td className={styles.num}>
                  {e.donePoints}/{e.points}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <h2 id="stories">Stories</h2>
      <div className={styles.filters}>
        <select value={epic} onChange={(ev) => setEpic(ev.target.value)} aria-label="Epic">
          <option value="All">All epics</option>
          {backlog.epics.map((e) => (
            <option key={e.id} value={e.id}>
              {e.id} · {e.summary}
            </option>
          ))}
        </select>
        <div className={styles.segmented} role="group" aria-label="Status">
          {STATUSES.map((s) => (
            <button key={s} type="button" className={clsx(status === s && styles.segActive)} onClick={() => setStatus(s)}>
              {s}
            </button>
          ))}
        </div>
        <input type="search" placeholder="Search stories…" value={query} onChange={(ev) => setQuery(ev.target.value)} aria-label="Search stories" />
        <span className={styles.count}>{stories.length} stories</span>
      </div>
      <div className={styles.tableWrap}>
        <table className={styles.table}>
          <thead>
            <tr>
              <th>Story</th>
              <th>Epic</th>
              <th>Priority</th>
              <th className={styles.num}>Pts</th>
              <th>Status</th>
            </tr>
          </thead>
          <tbody>
            {stories.map((s) => (
              <tr key={s.id}>
                <td>
                  <details className={styles.story}>
                    <summary>
                      <span className={styles.id}>{s.id}</span> {s.summary}
                    </summary>
                    {s.description ? <p>{s.description}</p> : null}
                    {s.acceptance ? (
                      <ul>
                        {s.acceptance
                          .split('\n')
                          .map((l) => l.replace(/^\s*-\s*/, '').trim())
                          .filter(Boolean)
                          .map((l) => (
                            <li key={l}>{l}</li>
                          ))}
                      </ul>
                    ) : null}
                  </details>
                </td>
                <td className={styles.muted} title={epicName.get(s.parent)}>
                  {s.parent}
                </td>
                <td className={styles.muted}>{s.priority}</td>
                <td className={styles.num}>{s.points || ''}</td>
                <td>
                  <StatusBadge status={s.status} />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
