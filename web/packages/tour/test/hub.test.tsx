import { act, cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it } from 'vitest';

import { createMemoryProgressStore, type RemoteTourProgress, type TourProgressRemote } from '../src/progress';
import { TourHub, TourProvider, type HubLabels } from '../src/react';
import { defineChapter } from '../src/schema';
import { createFakeEngine, createFakeEnvironment, TEST_LABELS } from '../src/testing';

afterEach(cleanup);

const LABELS: HubLabels = {
  title: 'Tour hub',
  intro: 'Intro',
  progress: '{done} of {total} chapters done',
  skippedCount: '{skipped} skipped',
  stepCount: '{n} steps',
  start: 'Start',
  resume: 'Resume',
  replay: 'Replay',
  continueJourney: 'Continue',
  skipJourney: 'Skip this journey',
  skipChapter: 'Skip',
  statusNotStarted: 'Not started',
  statusInProgress: 'In progress',
  statusDone: 'Done',
  statusSkipped: 'Skipped',
  whatsNew: "What's new",
  resetButton: 'Reset my progress',
  resetConfirm: 'Sure?',
  resetYes: 'Yes, reset',
  resetCancel: 'Cancel',
  resetFailed: 'Could not reset',
  resetDone: 'Reset',
  offline: 'Offline note',
  autoOff: 'Auto off note',
  empty: 'Nothing yet'
};

const chapter = (id: string, journey: string, version = 1) =>
  defineChapter({ id, prefix: `x.${id}`, journey, version, steps: [['one', 'a'], ['two', 'b'], ['three']] });

const CHAPTERS = [chapter('alpha', 'first'), chapter('beta', 'first', 2), chapter('gamma', 'second')];
const JOURNEYS = [
  { id: 'first', title: 'j.first.title', summary: 'j.first.summary' },
  { id: 'second', title: 'j.second.title', summary: 'j.second.summary' }
];

function fakeRemote(items: RemoteTourProgress[], opts: { fail?: boolean; autoStart?: boolean } = {}): TourProgressRemote & { resets: (string | undefined)[] } {
  const resets: (string | undefined)[] = [];
  return {
    resets,
    load: async () => {
      if (opts.fail) throw new Error('down');
      return { items, autoStartEnabled: opts.autoStart ?? true };
    },
    save: async (r) => r,
    merge: async () => ({ items, autoStartEnabled: true }),
    reset: async (id) => {
      resets.push(id);
    }
  };
}

function mount(remote: TourProgressRemote) {
  const engine = createFakeEngine();
  const environment = createFakeEnvironment(['a', 'b']);
  render(
    <TourProvider
      tours={CHAPTERS}
      journeys={JOURNEYS}
      subject={{ userId: 'u1', roles: [], permissions: [], flags: [] }}
      translate={(k) => k}
      labels={TEST_LABELS}
      pathname="/help/tours"
      navigate={() => undefined}
      remote={remote}
      namespace="sc"
      engine={engine}
      store={createMemoryProgressStore()}
      environment={environment}
    >
      <TourHub translate={(k) => k} labels={LABELS} />
    </TourProvider>
  );
  return { engine };
}

describe('Tour hub', () => {
  it('groups the chapters into journeys with progress from the server, a What\'s new mark and the right buttons', async () => {
    mount(
      fakeRemote([
        { tourId: 'sc.alpha', contentVersion: 1, status: 'COMPLETED', lastStep: 'three' },
        { tourId: 'sc.beta', contentVersion: 1, status: 'IN_PROGRESS', lastStep: 'two' }
      ])
    );
    expect(await screen.findByRole('heading', { level: 2, name: 'j.first.title' })).toBeTruthy();
    expect(screen.getByRole('heading', { level: 2, name: 'j.second.title' })).toBeTruthy();
    await waitFor(() => expect(screen.getByText('1 of 2 chapters done')).toBeTruthy());
    expect(screen.getByText('0 of 1 chapters done')).toBeTruthy();
    const beta = document.querySelector('[data-chapter="beta"]') as HTMLElement;
    expect(beta.textContent).toContain('In progress');
    // beta's content is newer than what the person last dealt with
    expect(beta.textContent).toContain("What's new");
    expect(beta.querySelector('[data-tour-resume="beta"]')).not.toBeNull();
    expect(document.querySelector('[data-chapter="alpha"] [data-tour-entry="alpha"]')?.textContent).toBe('Replay');
    expect(document.querySelector('[data-chapter="gamma"] [data-tour-entry="gamma"]')?.textContent).toBe('Start');
  });

  it('starting a chapter draws its first step; Resume starts at the last step', async () => {
    const { engine } = mount(fakeRemote([{ tourId: 'sc.beta', contentVersion: 2, status: 'IN_PROGRESS', lastStep: 'two' }]));
    await screen.findByRole('heading', { level: 2, name: 'j.first.title' });
    const user = userEvent.setup();
    await user.click(document.querySelector('[data-tour-resume="beta"]') as HTMLElement);
    await waitFor(() => expect(engine.last?.title).toBe('x.beta.two.title'));
    engine.callbacks?.close();
    await user.click(document.querySelector('[data-tour-entry="gamma"]') as HTMLElement);
    await waitFor(() => expect(engine.last?.title).toBe('x.gamma.one.title'));
  });

  it('skips a whole journey, only the chapters not started', async () => {
    mount(fakeRemote([{ tourId: 'sc.alpha', contentVersion: 1, status: 'COMPLETED', lastStep: null }]));
    await screen.findByRole('heading', { level: 2, name: 'j.first.title' });
    const user = userEvent.setup();
    await user.click(screen.getAllByRole('button', { name: 'Skip this journey' })[0] as HTMLElement);
    await waitFor(() => expect(document.querySelector('[data-chapter="beta"]')?.textContent).toContain('Skipped'));
    expect(document.querySelector('[data-chapter="alpha"]')?.textContent).toContain('Done');
    expect(document.querySelector('[data-chapter="gamma"]')?.textContent).toContain('Not started');
  });

  it('reset asks first, then forgets everything on the server and in the hub', async () => {
    const remote = fakeRemote([{ tourId: 'sc.alpha', contentVersion: 1, status: 'COMPLETED', lastStep: null }]);
    mount(remote);
    await screen.findByRole('heading', { level: 2, name: 'j.first.title' });
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: 'Reset my progress' }));
    expect(screen.getByText('Sure?')).toBeTruthy();
    await user.click(screen.getByRole('button', { name: 'Yes, reset' }));
    await waitFor(() => expect(screen.getByText('Reset')).toBeTruthy());
    expect(remote.resets).toEqual([undefined]);
    expect(document.querySelector('[data-chapter="alpha"]')?.textContent).toContain('Not started');
  });

  it('says so quietly when the server could not be reached, and when automatic tours are off', async () => {
    mount(fakeRemote([], { fail: true }));
    expect(await screen.findByText('Offline note')).toBeTruthy();
    cleanup();
    mount(fakeRemote([], { autoStart: false }));
    expect(await screen.findByText('Auto off note')).toBeTruthy();
    await act(async () => undefined);
  });
});
