import Link from '@docusaurus/Link';
import backlog from '@site/src/generated/backlog.json';
import contracts from '@site/src/generated/contracts.json';
import events from '@site/src/generated/events.json';
import mcp from '@site/src/generated/mcp-tools.json';
import { type ReactNode } from 'react';

import styles from './styles.module.css';

interface Card {
  to: string;
  title: string;
  body: string;
  stat?: string;
}

const stories = (backlog as { stories: { status: string }[] }).stories;
const done = stories.filter((s) => s.status === 'Done').length;

const CARDS: Card[] = [
  { to: '/getting-started', title: 'Getting started', body: 'Run the local stack, find every port and make target.' },
  { to: '/architecture', title: 'Architecture', body: 'Services, data stores, the event bus and the BFF.' },
  { to: '/engineering', title: 'Engineering standards', body: 'Code rules enforced in CI, new-service checklist, CI/CD, clients.' },
  { to: '/services', title: 'Services', body: 'One page per service, generated from its README.', stat: `${(events as { services: string[] }).services.length} services` },
  { to: '/api', title: 'API reference', body: 'Every OpenAPI 3.1 contract in Redoc and Scalar, plus the raw YAML.', stat: `${(contracts as unknown[]).length} contracts` },
  { to: '/events', title: 'Event catalog', body: 'Kafka topics, event types, payload schemas, producers and consumers.', stat: `${(events as { eventTypes: unknown[] }).eventTypes.length} event types` },
  { to: '/mcp', title: 'MCP', body: 'Model Context Protocol tools for shoppers, sellers and staff.', stat: `${(mcp as { tools: unknown[] }).tools.length} tools` },
  { to: '/runbooks', title: 'Runbooks', body: 'One page per alert: what it means, how to triage, how to fix.' },
  { to: '/backlog', title: 'Backlog status', body: 'Epics and stories from docs/backlog/backlog.csv.', stat: `${done}/${stories.length} stories done` },
];

export default function HomeCards(): ReactNode {
  return (
    <div className={styles.grid}>
      {CARDS.map((c) => (
        <Link key={c.to} to={c.to} className={styles.card}>
          <span className={styles.title}>{c.title}</span>
          <span className={styles.body}>{c.body}</span>
          {c.stat ? <span className={styles.stat}>{c.stat}</span> : null}
        </Link>
      ))}
    </div>
  );
}
