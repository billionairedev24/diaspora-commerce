import { useCallback, useState } from 'react';

import { useDriver } from '@/session/DriverProvider';

/** Pull-to-refresh: send what is queued, then download the route again (a failure shows on the screen, not as a crash). */
export function useRouteRefresh() {
  const { engine } = useDriver();
  const [refreshing, setRefreshing] = useState(false);
  const onRefresh = useCallback(() => {
    setRefreshing(true);
    void engine
      .flush()
      .catch(() => undefined)
      .then(() => engine.refreshRoute())
      .catch(() => undefined)
      .finally(() => setRefreshing(false));
  }, [engine]);
  return { refreshing, onRefresh };
}
