import { cloneElement, isValidElement, type ReactElement, type ReactNode } from 'react';

import { renderWithProviders } from './render';

const isAsync = (type: unknown): type is (props: unknown) => Promise<ReactNode> => typeof type === 'function' && type.constructor.name === 'AsyncFunction';

/** jsdom has no React Server Components runtime: this awaits every async (server) component in a tree so the result can be rendered. */
export async function resolveServer(node: ReactNode): Promise<ReactNode> {
  if (Array.isArray(node)) return Promise.all(node.map((n) => resolveServer(n)));
  if (!isValidElement(node)) return node;
  const el = node as ReactElement<{ children?: ReactNode }>;
  if (isAsync(el.type)) return resolveServer(await el.type(el.props));
  const children = el.props.children;
  if (children === undefined) return el;
  return cloneElement(el, undefined, await resolveServer(children));
}

export async function renderServer(node: ReactNode) {
  return renderWithProviders(<>{await resolveServer(node)}</>);
}
