/* The Scan tab is routed by the tab bar to the scan that applies now; this screen covers the idle case. */
import { BarcodeIcon as Barcode } from 'phosphor-react-native';

import { useT } from '@/i18n';
import { DriverScreen } from '@/ui/DriverScreen';
import { Empty } from '@/ui/primitives';

export default function ScanTab() {
  const tr = useT();
  return (
    <DriverScreen title={tr('dr.scan.scan')} inTabs>
      <Empty icon={Barcode} title={tr('dr.scan.nothingToScanRightNow')} body="Load-out, seller pickups, deliveries and returns open the scanner when they need it." />
    </DriverScreen>
  );
}
