import { ImagePlaceholder } from '@afrotransact/ui';
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

describe('image markup for Core Web Vitals and image search', () => {
  it('the LCP image can be eager with high fetch priority; every other image stays lazy by default', () => {
    render(
      <>
        <ImagePlaceholder src="https://media.afrotransact.com/p/a.jpg" alt="A bag of Ofada rice" loading="eager" fetchPriority="high" />
        <ImagePlaceholder src="https://media.afrotransact.com/p/b.jpg" alt="Another product" />
      </>
    );
    const main = screen.getByAltText('A bag of Ofada rice');
    expect(main).toHaveAttribute('loading', 'eager');
    expect(main).toHaveAttribute('fetchpriority', 'high');
    const other = screen.getByAltText('Another product');
    expect(other).toHaveAttribute('loading', 'lazy');
    expect(other).not.toHaveAttribute('fetchpriority');
  });

  it('the image sits in a box that reserves its aspect ratio (no layout shift), and without a photo nothing but the box renders', () => {
    const { container } = render(<ImagePlaceholder src="https://media.afrotransact.com/p/a.jpg" alt="Rice" ratio="4 / 3" />);
    expect((container.firstElementChild as HTMLElement).style.aspectRatio).toBe('4 / 3');
    const none = render(<ImagePlaceholder src={null} alt="Rice" />);
    expect(none.container.querySelector('img')).toBeNull();
  });
});
