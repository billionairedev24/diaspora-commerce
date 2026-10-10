/* Web OIDC redirect target (http://localhost:8081/auth/callback in local dev); native uses the custom scheme. */
import * as WebBrowser from 'expo-web-browser';

import { Loading } from '@/ui/primitives';

WebBrowser.maybeCompleteAuthSession();

export default function AuthCallback() {
  return <Loading />;
}
