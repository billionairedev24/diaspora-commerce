import Link from '@docusaurus/Link';
import useBrokenLinks from '@docusaurus/useBrokenLinks';
import useBaseUrl from '@docusaurus/useBaseUrl';
import rawEvents from '@site/src/generated/events.json';
import { type ReactNode, useMemo, useState } from 'react';

import SchemaView from './SchemaView';
import styles from './styles.module.css';
import type { EventsData, Message, Party, Topic } from './types';

const events = rawEvents as unknown as EventsData;
const knownServices = new Set(events.services);

export const messageAnchor = (key: string): string => `msg-${key}`;
const topicPath = (t: Topic): string => `/events/topics/${t.slug}`;
const topicByAddress = new Map(events.topics.map((t) => [t.address, t]));

function Service({ name }: { name: string }): ReactNode {
  return knownServices.has(name) ? (
    <Link className={styles.service} to={`/services/${name}`}>
      {name}
    </Link>
  ) : (
    <span className={styles.service}>{name}</span>
  );
}

function Services({ names, empty }: { names: string[]; empty: string }): ReactNode {
  if (!names.length) return <span className={styles.missing}>{empty}</span>;
  return (
    <span className={styles.serviceList}>
      {names.map((n) => (
        <Service key={n} name={n} />
      ))}
    </span>
  );
}

function Parties({ title, parties, empty }: { title: string; parties: Party[]; empty: string }): ReactNode {
  return (
    <div className={styles.parties}>
      <h3>{title}</h3>
      {parties.length ? (
        <ul className={styles.partyList}>
          {parties.map((p) => (
            <li key={p.operation}>
              <div className={styles.partyHead}>
                <Service name={p.service} />
                <code className={styles.op}>{p.operation}</code>
              </div>
              {p.summary ? <p>{p.summary}</p> : null}
            </li>
          ))}
        </ul>
      ) : (
        <p className={styles.missing}>{empty}</p>
      )}
    </div>
  );
}

function MessageCard({ msg, topic }: { msg: Message; topic: Topic }): ReactNode {
  const producers = topic.producers.filter((p) => p.messageKeys.includes(msg.key)).map((p) => p.service);
  const consumers = topic.consumers.filter((p) => p.messageKeys.includes(msg.key)).map((p) => p.service);
  useBrokenLinks().collectAnchor(messageAnchor(msg.key));
  return (
    <article className={styles.message} id={messageAnchor(msg.key)}>
      <header className={styles.messageHeader}>
        <code className={styles.eventName}>{msg.name}</code>
        {msg.contentType ? <span className={styles.chip}>{msg.contentType}</span> : null}
      </header>
      {msg.title ? <p className={styles.messageTitle}>{msg.title}</p> : null}
      {msg.summary && msg.summary !== msg.title ? <p>{msg.summary}</p> : null}
      {msg.description ? <p className={styles.muted}>{msg.description}</p> : null}
      <dl className={styles.facts}>
        <dt>Produced by</dt>
        <dd>
          <Services names={[...new Set(producers)]} empty="no send operation documented" />
        </dd>
        <dt>Consumed by</dt>
        <dd>
          <Services names={[...new Set(consumers)]} empty="no receive operation documented" />
        </dd>
      </dl>
      {msg.payload ? (
        <details className={styles.block} open>
          <summary>Payload</summary>
          <SchemaView schema={msg.payload} schemas={events.schemas} />
        </details>
      ) : null}
      {msg.headers ? (
        <details className={styles.block}>
          <summary>Headers</summary>
          <SchemaView schema={msg.headers} schemas={events.schemas} />
        </details>
      ) : null}
      {msg.examples.length ? (
        <details className={styles.block}>
          <summary>Examples</summary>
          {msg.examples.map((ex, i) => (
            <div key={ex.name ?? i}>
              {ex.name || ex.summary ? <p className={styles.muted}>{ex.summary ?? ex.name}</p> : null}
              <pre className={styles.code}>{JSON.stringify(ex.payload ?? ex, null, 2)}</pre>
            </div>
          ))}
        </details>
      ) : null}
    </article>
  );
}

/** One Kafka topic: its channels, producers, consumers and every message with its payload schema. */
export function TopicView({ address }: { address: string }): ReactNode {
  const topic = topicByAddress.get(address);
  if (!topic) return <p className={styles.missing}>Topic {address} is not in contracts/asyncapi/events.yaml.</p>;
  return (
    <div className={styles.topic}>
      <div className={styles.topicMeta}>
        <span className={styles.chip}>Kafka topic</span>
        <span className={styles.chip}>{topic.messageKeys.length} message types</span>
        {events.servers.map((s) => (
          <span key={s.id} className={styles.chipMuted}>
            {s.id}: {s.protocol}://{s.host}
          </span>
        ))}
      </div>
      {topic.channels.map((c) => (
        <div key={c.id} className={styles.channel}>
          <div className={styles.channelId}>
            channel <code>{c.id}</code>
          </div>
          {c.description ? <p>{c.description}</p> : null}
        </div>
      ))}
      <div className={styles.partiesGrid}>
        <Parties title="Producers" parties={topic.producers} empty="No producer documents this topic yet (no send operation)." />
        <Parties title="Consumers" parties={topic.consumers} empty="No consumer documented." />
      </div>
      <h2 id="messages">Messages</h2>
      {topic.messageKeys.map((k) => {
        const msg = events.messages[k];
        return msg ? <MessageCard key={k} msg={msg} topic={topic} /> : null;
      })}
    </div>
  );
}

/** Every event type on the bus with its topic, producers and consumers, filterable. */
export function EventIndex(): ReactNode {
  const [query, setQuery] = useState('');
  const [onlyGaps, setOnlyGaps] = useState(false);
  const download = useBaseUrl('/contracts/asyncapi/events.yaml');
  const rows = useMemo(() => {
    const q = query.trim().toLowerCase();
    return events.eventTypes.filter((t) => {
      if (onlyGaps && t.producers.length) return false;
      if (!q) return true;
      return [t.name, t.title, ...t.topics, ...t.producers, ...t.consumers].join(' ').toLowerCase().includes(q);
    });
  }, [query, onlyGaps]);
  const published = events.eventTypes.filter((t) => t.producers.length).length;

  return (
    <div className={styles.index}>
      <div className={styles.stats}>
        <div>
          <strong>{events.topics.length}</strong>
          <span>topics</span>
        </div>
        <div>
          <strong>{events.eventTypes.length}</strong>
          <span>event types</span>
        </div>
        <div>
          <strong>{published}</strong>
          <span>with a documented producer</span>
        </div>
        <div>
          <strong>{Object.keys(events.schemas).length}</strong>
          <span>payload schemas</span>
        </div>
      </div>
      <div className={styles.filters}>
        <input
          className={styles.search}
          type="search"
          placeholder="Filter by event type, topic or service…"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          aria-label="Filter events"
        />
        <label className={styles.toggle}>
          <input type="checkbox" checked={onlyGaps} onChange={(e) => setOnlyGaps(e.target.checked)} /> only events without a producer
        </label>
        <a className={styles.downloadLink} href={download} download="events.yaml">
          Download events.yaml (AsyncAPI {events.info.asyncapi})
        </a>
      </div>
      <div className={styles.tableWrap}>
        <table className={styles.eventTable}>
          <thead>
            <tr>
              <th>Event type</th>
              <th>Topic</th>
              <th>Producer</th>
              <th>Consumers</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((t) => (
              <tr key={t.name}>
                <td>
                  <code className={styles.eventName}>{t.name}</code>
                  {t.title ? <div className={styles.muted}>{t.title}</div> : null}
                </td>
                <td>
                  {t.topics.map((a) => {
                    const topic = topicByAddress.get(a);
                    return topic ? (
                      <div key={a}>
                        <Link to={`${topicPath(topic)}#${messageAnchor(t.keys.find((k) => topic.messageKeys.includes(k)) ?? t.keys[0])}`}>{a}</Link>
                      </div>
                    ) : (
                      <div key={a}>{a}</div>
                    );
                  })}
                </td>
                <td>
                  <Services names={t.producers} empty="not documented" />
                </td>
                <td>
                  <Services names={t.consumers} empty="—" />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {events.problems.length ? (
        <div className={styles.problems}>
          <h3>Contract gaps</h3>
          <ul>
            {events.problems.map((p) => (
              <li key={p}>{p}</li>
            ))}
          </ul>
        </div>
      ) : null}
    </div>
  );
}

/** Topic cards for the catalog landing page. */
export function TopicList(): ReactNode {
  return (
    <div className={styles.topicGrid}>
      {events.topics.map((t) => (
        <Link key={t.address} className={styles.topicCard} to={topicPath(t)}>
          <code>{t.address}</code>
          <span className={styles.muted}>
            {t.messageKeys.length} messages · {t.producers.length ? `from ${[...new Set(t.producers.map((p) => p.service))].join(', ')}` : 'producer not documented'}
          </span>
        </Link>
      ))}
    </div>
  );
}
