import { type Metadata } from 'next';

import { ImageLibraryView } from '@/components/listings/images/ImageLibraryView';

export const metadata: Metadata = { title: 'Product images' };

export default function ProductImagesPage() {
  return <ImageLibraryView />;
}
