/* The Scan tab is routed by the tab bar to the scan that applies now; this screen covers the idle case. */
import { BarcodeIcon as Barcode } from 'phosphor-react-native';

import { DriverScreen } from '@/ui/DriverScreen';
import { Empty } from '@/ui/primitives';

export default function ScanTab() {
  return (
    <DriverScreen title="Scan" inTabs>
      <Empty icon={Barcode} title="Nothing to scan right now" body="Load-out, seller pickups, deliveries and returns open the scanner when they need it." />
    </DriverScreen>
  );
}
