import * as WebBrowser from 'expo-web-browser';

import { Loading } from '@/ui/primitives';

WebBrowser.maybeCompleteAuthSession();

export default function AuthCallback() {
  return <Loading />;
}
