'use client';

import { createNavGuard } from '@afrotransact/ui';

/** The staff console's redirect-loop guard (see @afrotransact/ui createNavGuard): one per browser tab. */
export const navGuard = createNavGuard({ key: 'at.st.nav' });
