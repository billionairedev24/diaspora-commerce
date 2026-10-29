/* Addresses (design: ADDRESSES): the shopper's delivery addresses, added and edited in the app. */
import { useRouter } from 'expo-router';
import { MapPinIcon as MapPin, PlusIcon as Plus } from 'phosphor-react-native';
import { View } from 'react-native';

import { useAddresses } from '@/api/queries';
import { c, sp, t } from '@/theme';
import { AppText, Button, Card, Empty, Loading } from '@/ui/primitives';
import { ErrorView, useRefresh } from '@/ui/QueryStates';
import { Screen, SubHeader } from '@/ui/Screen';

export default function Addresses() {
  const router = useRouter();
  const addresses = useAddresses();
  const refresh = useRefresh(addresses);
  return (
    <Screen
      header={<SubHeader title="Addresses" />}
      refresh={refresh}
      footer={<Button kind="outline" icon={Plus} label="Add a new address" onPress={() => router.push('/addresses/edit')} />}
    >
      {addresses.isPending ? <Loading /> : null}
      {addresses.isError && !addresses.data ? <ErrorView error={addresses.error} onRetry={() => void addresses.refetch()} retrying={addresses.isFetching} /> : null}
      {addresses.data?.length === 0 ? <Empty icon={MapPin} title="No addresses yet" body="Add where you’d like your orders delivered." /> : null}
      {(addresses.data ?? []).map((a) => (
        <Card key={a.id} selected={a.isDefault} onPress={() => router.push({ pathname: '/addresses/edit', params: { id: a.id } })} accessibilityLabel={`Edit address ${a.name}`}>
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
