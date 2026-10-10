/* Addresses (design: ADDRESSES). The BFF exposes the list; add/edit/default are not in the contract yet (gap) —
   adding opens the web account page. */
import * as WebBrowser from 'expo-web-browser';
import { MapPinIcon as MapPin, PlusIcon as Plus } from 'phosphor-react-native';
import { View } from 'react-native';

import { useAddresses } from '@/api/queries';
import { env } from '@/config/env';
import { c, sp, t } from '@/theme';
import { AppText, Button, Card, Empty, Loading } from '@/ui/primitives';
import { Screen, SubHeader } from '@/ui/Screen';

export default function Addresses() {
  const addresses = useAddresses();
  return (
    <Screen header={<SubHeader title="Addresses" />} footer={<Button kind="outline" icon={Plus} label="Add a new address" onPress={() => void WebBrowser.openBrowserAsync(`${env.webUrl}/account/addresses`)} />}>
      {addresses.isLoading ? <Loading /> : null}
      {addresses.data?.length === 0 ? <Empty icon={MapPin} title="No addresses yet" /> : null}
      {(addresses.data ?? []).map((a) => (
        <Card key={a.id} selected={a.isDefault}>
          <View style={{ flexDirection: 'row', justifyContent: 'space-between' }}>
            <AppText variant="bodyStrong">{a.name}</AppText>
            {a.isDefault ? <AppText variant="caption" style={{ color: c.success, fontWeight: t.fontWeight.bold }}>Default</AppText> : null}
          </View>
          <AppText color={c.textMuted}>{a.line1}{a.line2 ? `, ${a.line2}` : ''}{'\n'}{a.city}, {a.state} {a.zip}</AppText>
          {a.verified ? <AppText variant="caption" color={c.success}>Verified address</AppText> : null}
        </Card>
      ))}
      <View style={{ height: sp['2'] }} />
    </Screen>
  );
}
