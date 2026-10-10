import { type Meta, type StoryObj } from '@storybook/react-vite';

import { mobile, rtl } from '../stories.helpers';
import { Logo } from './Logo';
import { SocialButton } from './SocialButton';
import { SocialIconLink } from './SocialIcon';

const meta: Meta = { title: 'Brand/Logo & social' };
export default meta;
type Story = StoryObj;

export const LogoOnLight: Story = { render: () => <Logo href="#" /> };
export const LogoOnHeader: Story = { render: () => <div style={{ background: 'var(--at-color-header-bg)', color: 'var(--at-color-header-text)', padding: 12 }}><Logo href="#" /></div> };
export const SocialButtons: Story = {
  render: () => (
    <div style={{ display: 'grid', gap: 12, maxWidth: 420 }}>
      <SocialButton provider="google" href="#google" />
      <SocialButton provider="apple" href="#apple" />
      <SocialButton provider="facebook" href="#facebook" />
    </div>
  )
};
export const FooterSocialIcons: Story = {
  render: () => (
    <div style={{ display: 'flex', gap: 8, background: 'var(--at-color-footer-bg)', padding: 16 }}>
      {(['tiktok', 'instagram', 'facebook', 'x', 'youtube', 'linkedin', 'whatsapp'] as const).map((n) => (
        <SocialIconLink key={n} network={n} href={`https://example.com/${n}`} label={n} />
      ))}
    </div>
  )
};
export const RTL: Story = { ...rtl, render: () => <SocialButton provider="google" href="#" label="المتابعة باستخدام Google" /> };
export const Mobile: Story = { ...mobile, render: () => <SocialButton provider="google" href="#" /> };
