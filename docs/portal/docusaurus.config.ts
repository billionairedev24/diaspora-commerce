import type * as Preset from '@docusaurus/preset-classic';
import type { Config } from '@docusaurus/types';
import { themes as prismThemes } from 'prism-react-renderer';

// Deployed to Cloudflare Pages (project `afrotransact-docs`, see README.md). SITE_URL / BASE_URL let preview
// deployments and local builds override the production origin.
const config: Config = {
  title: 'AfroTransact Developers',
  tagline: 'Contracts, services, events and runbooks for the AfroTransact v2 marketplace',
  favicon: 'img/logo-mark.svg',
  url: process.env.SITE_URL ?? 'https://developers.afrotransact.com',
  baseUrl: process.env.BASE_URL ?? '/',
  trailingSlash: false,
  onBrokenLinks: 'throw',
  onBrokenAnchors: 'warn',
  onDuplicateRoutes: 'throw',
  markdown: {
    // .md files (copied READMEs, runbooks, guides) are CommonMark; .mdx pages may use components.
    format: 'detect',
    hooks: { onBrokenMarkdownLinks: 'throw' },
  },
  i18n: { defaultLocale: 'en', locales: ['en'] },
  presets: [
    [
      'classic',
      {
        docs: {
          routeBasePath: '/',
          sidebarPath: './sidebars.ts',
          showLastUpdateTime: false,
          breadcrumbs: true,
        },
        blog: false,
        theme: { customCss: './src/css/custom.css' },
      } satisfies Preset.Options,
    ],
  ],
  themeConfig: {
    colorMode: { defaultMode: 'light', disableSwitch: true, respectPrefersColorScheme: false },
    navbar: {
      title: 'AfroTransact Developers',
      logo: { alt: 'AfroTransact', src: 'img/logo-mark.svg' },
      items: [
        { to: '/user-guide', label: 'User guide', position: 'left' },
        { to: '/getting-started', label: 'Get started', position: 'left' },
        { to: '/services', label: 'Services', position: 'left' },
        { to: '/api', label: 'API reference', position: 'left' },
        { to: '/events', label: 'Events', position: 'left' },
        { to: '/mcp', label: 'MCP', position: 'left' },
        { to: '/backlog', label: 'Backlog', position: 'right' },
        { href: 'https://github.com/billionairedev24/diaspora-commerce', label: 'GitHub', position: 'right' },
      ],
    },
    footer: {
      style: 'light',
      copyright: `AfroTransact developer portal · generated from the diaspora-commerce monorepo`,
    },
    docs: { sidebar: { hideable: false, autoCollapseCategories: false } },
    tableOfContents: { minHeadingLevel: 2, maxHeadingLevel: 3 },
    prism: { theme: prismThemes.github, additionalLanguages: ['bash', 'java', 'go', 'yaml', 'json'] },
  } satisfies Preset.ThemeConfig,
};

export default config;
