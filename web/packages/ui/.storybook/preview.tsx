import '@afrotransact/tokens/fonts.css';
import '@afrotransact/tokens/tokens.css';
import '../src/global.css';

import { type Decorator, type Preview } from '@storybook/react-vite';

/** Global "Direction" toolbar + per-story `parameters.dir` for RTL stories. */
const withDirection: Decorator = (Story, ctx) => {
  const dir = (ctx.parameters.dir as string | undefined) ?? (ctx.globals.dir as string | undefined) ?? 'ltr';
  return (
    <div dir={dir} lang={dir === 'rtl' ? 'ar' : 'en'} style={{ padding: 16, background: 'var(--at-color-canvas)' }}>
      <Story />
    </div>
  );
};

/**
 * Under @storybook/test-runner (it tags the user agent) the addon's automatic scan is switched off: the runner's
 * postVisit hook (.storybook/test-runner.ts) runs axe itself and applies the serious/critical gate + known issues.
 */
const underTestRunner = typeof navigator !== 'undefined' && navigator.userAgent.includes('StorybookTestRunner');

const preview: Preview = {
  decorators: [withDirection],
  globalTypes: {
    dir: {
      description: 'Text direction',
      toolbar: { title: 'Direction', icon: 'transfer', items: [{ value: 'ltr', title: 'LTR' }, { value: 'rtl', title: 'RTL' }], dynamicTitle: true }
    }
  },
  initialGlobals: { dir: 'ltr', a11y: { manual: underTestRunner } },
  parameters: {
    layout: 'fullscreen',
    a11y: { test: 'error' },
    controls: { expanded: true },
    viewport: {
      options: {
        mobile: { name: 'Mobile (375)', styles: { width: '375px', height: '740px' }, type: 'mobile' },
        tablet: { name: 'Tablet (900)', styles: { width: '900px', height: '1000px' }, type: 'tablet' }
      }
    }
  }
};

export default preview;
