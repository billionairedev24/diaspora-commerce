# Run the apps on your phone

The customer app (`mobile/apps/customer`, **AfroTransact**) and the driver app (`mobile/apps/driver`,
**AfroTransact Delivery**) are Expo SDK 57 apps. There are three ways onto a real phone:

| Route | Setup | What works | Use it for |
|---|---|---|---|
| [Expo Go over Wi-Fi](#1-expo-go-over-wi-fi-quickest) | Expo Go from the App Store / Play Store, `make mobile-lan` | Everything except remote push, Face ID (iPhone), Apple Pay / Google Pay | UI work, sign-in, browsing, checkout with cards, driver flows |
| [Development build over USB](#2-development-build-over-usb) | Android Studio or Xcode on your Mac, a cable | Everything: push, Face ID, wallets, the app's own icon and splash | Native features, push, payments end to end |
| [Development build from EAS](#3-development-build-from-eas-no-usb-no-xcode) | A free Expo account, `eas build` | Same as USB | No Android Studio / Xcode, or Windows/Linux + iPhone |
| [Standalone Android app](#4-standalone-android-app-no-metro-no-cable) | Android Studio on your Mac, `make mobile-apk` | Everything; no Metro needed, installs over Wi-Fi, USB or Bluetooth | Using the app like a real one against your local stack |

The first three load the JavaScript from Metro on your Mac, so code changes reload instantly. Every route uses the
**[LAN mode](#lan-mode-the-whole-stack-on-your-wi-fi-address)** of the local stack unless you use Android over USB with
`adb reverse`.

## LAN mode: the whole stack on your Wi-Fi address

On a phone, `localhost` is the phone. Pointing only the app at the Mac's address is not enough: identity issues tokens
for `http://localhost:8100`, its sign-in pages redirect there, and the web apps, cookies and CORS origins all name
localhost. LAN mode switches **the whole stack** to one address so tokens, cookies and origins agree:

```bash
make up MOBILE_HOST=lan            # or: make mobile-lan; or MOBILE_HOST=192.168.1.50 to pick the address
make lan-env MOBILE_HOST=lan       # dry run: print the addresses it would use
```

| What | localhost mode (default) | LAN mode |
|---|---|---|
| identity issuer (`AUTH_ISSUER_URL`; every service validates tokens against it, the BFF's `OIDC_ISSUER` follows it) | `http://localhost:8100` | `http://<ip>:8100` |
| Web apps (`PUBLIC_WEB_URL`, `SELLER_CENTER_URL`, `STAFF_CONSOLE_URL`; sign-in callbacks, links) | `http://localhost:3000/3001/3002` | `http://<ip>:3000/3001/3002` |
| CORS / CSRF origins (`WEB_ORIGINS`, `AT_SECURITY_CORS_ALLOWED_ORIGINS`, identity's list via `LAN_WEB_ORIGINS`) | localhost | the LAN origins (+ the localhost Expo web previews) |
| Mobile OIDC redirect URIs (identity `customer-app` / `driver-app`) | app scheme, `http://localhost:808x/auth/callback`, `exp://localhost:808x/--/auth/callback` | + `exp://<ip>:808x/--/auth/callback` and `http://<ip>:808x/auth/callback` (`CUSTOMER_APP_DEV_REDIRECT_URIS`, `DRIVER_APP_DEV_REDIRECT_URIS`, local profile only) |
| Apps' API host (`EXPO_PUBLIC_DEV_HOST`, inlined by Metro) | `localhost` | `<ip>` |
| Product images (`http://localhost:9000/media/…` stored with the data) | as stored | gateway-bff rewrites them to `http://<ip>:9000/media/…` (`LAN_MEDIA_URL`, local only) |
| Upload links (media presigned URLs) | MinIO on localhost | MinIO on `<ip>` (`LAN_MINIO_ENDPOINT`) |
| Next.js dev servers | — | allow `<ip>` for dev assets / HMR (`NEXT_ALLOWED_DEV_ORIGINS`) |

`make up` then prints the phone URLs, an `exp://` link per app (with a QR code in the terminal when `qrencode` is
installed: `brew install qrencode`) and the development-build deep links. In LAN mode use `http://<ip>:3000` on the Mac
as well: a session started on `localhost:3000` is a different origin.

**Switching back:** `make up` without `MOBILE_HOST`. Each service, web app and Metro server remembers the address it was
started with (`.local/pids/<name>.env`); only those whose address changed restart, nothing is rebuilt and Docker keeps
running. The LAN address comes from your default network interface (Wi-Fi or Ethernet); if your Mac gets a new address
(another network, DHCP), run `make up MOBILE_HOST=lan` again.

Not in LAN mode: passkeys (WebAuthn needs `localhost` or HTTPS; use password + code), and Mailpit stays on the Mac
(`http://localhost:8025`).

## 1. Expo Go over Wi-Fi (quickest)

1. Install **Expo Go** on the phone (App Store / Google Play). It must support SDK 57.
2. Phone and Mac on the **same Wi-Fi** (not a guest network, see [troubleshooting](#troubleshooting)).
3. On the Mac: `make up MOBILE_HOST=lan` (the first run takes a while; later runs restart only what changed).
4. Open the app:
   - **iPhone:** point the Camera app at the QR code `make up` printed, or type the `exp://<ip>:8081` URL in Safari.
   - **Android:** in Expo Go, *Scan QR code*, or *Enter URL manually* → `exp://<ip>:8081`.
   - Driver app: the same with port **8082**.
5. Sign in with a [test user](local-development.md#test-users): `customer.houston@afrotransact.test` or
   `driver@afrotransact.test`, password `Test1234!Local`; the 6-digit code lands in Mailpit (`http://localhost:8025`
   on the Mac).

**What is limited in Expo Go** (the app detects Expo Go, `src/platform/runtime.ts`, and falls back; nothing crashes):

| Feature | In Expo Go | Fallback |
|---|---|---|
| Remote push (FCM/APNs) | Removed from Expo Go in SDK 53 | No device registration; a dismissible note says "Push notifications need the development build" (dev only). Local notification APIs (inbox, permission prompt) still work. |
| Face ID (iPhone) | Our usage string is not in Expo Go | Biometrics report unavailable; the app's own passcode unlocks (Android fingerprint works) |
| Apple Pay / Google Pay | No merchant set-up in Expo Go | PaymentSheet offers cards (Stripe's SDK is part of Expo Go); 3-D Secure returns to `exp://…` |
| App icon, splash, name | Expo Go's | — |
| Camera, barcode scanning, GPS, SQLite, secure storage (driver) | Work | — |
| Background location | Not used by the app | — |

Expo Go allows plain HTTP to your Mac by itself. Sign-in uses the system browser and returns to
`exp://<ip>:8081/--/auth/callback`, which LAN mode registers with identity.

## 2. Development build over USB

A development build is the app with its own native code (`expo-dev-client`), icon and permissions, loading JS from
your Metro. Build it once; rebuild only when native dependencies or `app.config.ts` change.

### Android (macOS, Windows, Linux)

1. Install **Android Studio** (it brings the SDK and `adb`) and **JDK 17** (`brew install --cask zulu@17`, or Android
   Studio's bundled JBR: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`). Add the SDK
   to your shell: `export ANDROID_HOME=$HOME/Library/Android/sdk; export PATH=$ANDROID_HOME/platform-tools:$PATH`
   (Linux: `$HOME/Android/Sdk`; Windows: `%LOCALAPPDATA%\Android\Sdk`).
2. On the phone: *Settings → About phone →* tap **Build number** seven times (Developer options), then *Settings →
   System → Developer options →* **USB debugging** on.
3. Plug it in, accept *Allow USB debugging?* on the phone, and check: `adb devices` lists it as `device`.
4. Build and install:

   ```bash
   make mobile-android-device APP=customer     # or APP=driver; = cd mobile/apps/customer && npx expo run:android --device
   ```

   The first build takes several minutes. If Metro from `make up` is running, the build is installed with
   `--no-bundler` and connects to it.
5. Pick a network route:
   - **Over the cable (localhost mode):** `make mobile-adb-reverse` maps the phone's `localhost:8081, 8100, 8200, 3000, …`
     to the Mac. Works with the default `make up`, also on guest Wi-Fi. Repeat after re-plugging.
   - **Over Wi-Fi (LAN mode):** `make up MOBILE_HOST=lan`, then open the app: the development launcher lists the Metro
     servers on your network, or scan the QR code / open the `exp+afrotransact-customer://expo-development-client/?url=…`
     link `make up` printed.

**Installing an EAS-built APK over USB:** download the `.apk` from the build page (or `eas build:list`), then
`adb install -r path/to/app.apk`.

### iOS (macOS only)

1. Install **Xcode** from the App Store, open it once (it installs components), then *Xcode → Settings → Accounts →* add
   your **Apple ID**. A free *Personal Team* works; its builds expire after **7 days** and allow 3 apps per device. A
   paid Apple Developer account ($99/year) lasts a year and is needed for push (APNs) and Apple Pay.
2. Connect the iPhone with a cable, unlock it and tap **Trust This Computer**.
3. Turn on **Developer Mode**: *Settings → Privacy & Security → Developer Mode* → on, restart, confirm. (The switch
   appears after the phone has been connected to Xcode once.)
4. Build and install:

   ```bash
   make mobile-ios-device APP=customer         # = cd mobile/apps/customer && npx expo run:ios --device
   ```

   Pick the phone when asked. If Xcode reports a signing error, open `mobile/apps/customer/ios/*.xcworkspace`, select
   the target → *Signing & Capabilities* → choose your team (a unique bundle id may be needed for a Personal Team, e.g.
   `com.<you>.afrotransact.customer.local`).
5. First launch: *Settings → General → VPN & Device Management →* your developer profile → **Trust**.
6. Use LAN mode (`make up MOBILE_HOST=lan`) and open the app; allow **Local Network** access when iOS asks.

The local and dev builds allow HTTP to your LAN (iOS: `NSAllowsLocalNetworking`; Android: `usesCleartextTraffic`);
uat and prod builds do not (`app.config.ts`, `LOCAL_NETWORK`).

## 3. Development build from EAS (no USB, no Xcode)

EAS builds in Expo's cloud. You need a free Expo account (`npx eas-cli login`); the free plan includes a monthly quota
of builds in a shared queue (enough for development builds). The project must be linked once: `cd mobile/apps/customer
&& npx eas-cli init` (sets `EAS_PROJECT_ID` / `EAS_OWNER`, read by `app.config.ts`).

```bash
cd mobile/apps/customer
npx eas-cli build --profile development --platform android   # APK, installable directly
npx eas-cli build --profile development --platform ios       # ad-hoc build for registered iPhones
npx eas-cli build --profile development-simulator --platform ios   # iOS simulator build (.app)
```

- **Android:** open the build link or scan its QR code on the phone, download and install the APK (allow *Install
  unknown apps* for the browser).
- **iOS:** needs a paid Apple Developer account. Register each iPhone first with `npx eas-cli device:create` (open the
  link on the phone, install the profile), then build; install from the build page's QR code. Enable Developer Mode
  as above.

Profiles (`eas.json`): `development` (device build, dev client, APK on Android), `development-simulator`,
`development-mocks` (in-app mock backend, no stack needed), `preview` (uat), `production`. Then start the stack in LAN
mode and open the app; it finds Metro like the USB build.

**Push notifications** need credentials in the build: `GOOGLE_SERVICES_JSON` (EAS file variable, Android/FCM) and the
APNs key (`eas credentials`, iOS), plus the sending side in `.local/secrets.env` (`FCM_SERVICE_ACCOUNT_JSON`,
`APNS_*`). See [secrets and configuration](secrets-and-configuration.md#mobile-apps-eas).

## 4. Standalone Android app (no Metro, no cable)

A release build with the JavaScript inside: it installs and opens like a store app, keeps working when Metro is not
running, and talks to the stack on your Mac over Wi-Fi. Use it to try the app as a customer or driver would.

**Build** (Android Studio for the SDK, JDK 17; the same as [the USB build](#android-macos-windows-linux)):

```bash
make mobile-lan                         # the stack on your Wi-Fi address (the app needs it running to sign in and shop)
make mobile-apk APP=customer            # or APP=driver; MOBILE_HOST=<ip> to pick the address
```

The APK lands in `.local/apk/afrotransact-<app>.apk` (package `com.afrotransact.<app>.local`, so it sits next to a store
or development build). The Mac's address and your `STRIPE_PUBLISHABLE_KEY` are **baked in** when it is built: after the
Mac gets another address (another network, a new DHCP lease) run `make mobile-apk` again, or give the Mac a fixed
address in your router (DHCP reservation) so the APK keeps working. It is signed with the debug key: fine for
sideloading, not for the Play Store (that is `eas build --profile production`). Push needs `google-services.json`
(see [EAS](#3-development-build-from-eas-no-usb-no-xcode)); everything else works.

**Get it onto the phone**, any of:

| Route | How |
|---|---|
| Over Wi-Fi (built in) | With no phone on USB, `make mobile-apk` ends by serving the folder: open `http://<mac-ip>:8765/afrotransact-customer.apk` in the phone's browser (same Wi-Fi). Later, without rebuilding: `python3 -m http.server 8765 --directory .local/apk`. |
| USB cable | Connected with USB debugging on, `make mobile-apk` installs it with `adb` by itself (`adb install -r .local/apk/afrotransact-customer.apk` later). |
| Wireless adb (Android 11+) | Phone: *Developer options → Wireless debugging → Pair device with pairing code*. Mac: `adb pair <ip>:<pair-port>` (enter the code), then `adb connect <ip>:<port>` (the port shown on the Wireless debugging screen). Then `adb install -r .local/apk/afrotransact-customer.apk`, or `make mobile-apk` installs it. |
| Bluetooth | Pair the phone and the Mac (*System Settings → Bluetooth*), turn on *System Settings → General → Sharing → Bluetooth Sharing*, then open **Bluetooth File Exchange** (Applications → Utilities), pick the APK and the phone, and accept the file on the phone. Slow: tens of MB at Bluetooth speed take minutes. |
| Cloud / chat | Upload the APK to Google Drive (or send it to yourself), open it on the phone. |

**Install on the phone:** open the downloaded file. The first time Android asks to allow *Install unknown apps* for
that app (browser, Files, Bluetooth): allow it, go back, install. Play Protect may warn about an unknown developer:
*More details → Install anyway*. Updating: install the new APK over the old one (same package, same debug key, data
kept). If Android refuses ("package conflicts"), uninstall the old one first: it was built on another machine with a
different debug key.

## Windows and Linux

Only Android builds are possible (iOS needs macOS for Xcode, or an EAS build + paid Apple account). The steps are the
same: Android Studio + JDK 17, USB debugging, `make mobile-android-device`. On Windows run the stack in WSL 2 or Git
Bash; a phone on Wi-Fi reaches WSL 2 only with *mirrored networking* (`networkingMode=mirrored` in `.wslconfig`) —
otherwise use the cable and `make mobile-adb-reverse` (run `adb` on Windows).

## Welcome imagery

The welcome screens show drawn brand art by default (customer: a Sunday table with the AfroTransact bag; driver: a
courier handing over a parcel). To use a photo, or a short muted clip in the driver app, put it in the app's
`assets/hero/`: `hero.jpg` (or `.png` / `.webp`) and, for the driver, `hero.mp4`. `metro.config.js` picks them up;
restart Metro with `--clear`. Or let `make mobile-hero` download free-licensed ones from Pexels (needs
`PEXELS_API_KEY` in `.local/secrets.env`; `ARGS="--dry-run"` to preview, `--remove` to go back); it records the
photographer and licence in `assets/hero/ATTRIBUTION.json`. The folder is gitignored. Reduce motion turns the clip and
the slow pan off.

Launcher icons, splash and favicon come from `design/assets/logo-mark.svg`: `make mobile-brand` regenerates them
(`mobile/scripts/brand-assets.mjs`).

## Troubleshooting

| Symptom | Fix |
|---|---|
| Expo Go: *Could not connect to the server* / spinner forever | Phone and Mac on the same network? Open `http://<ip>:8081` in the phone's browser: if it does not load, it is the network. macOS: *System Settings → Network → Firewall* — allow incoming connections for `node` and `java` (or turn the firewall off while testing). |
| Guest Wi-Fi, office/hotel Wi-Fi, "AP/client isolation" | Devices cannot see each other. Use a home network or the phone's hotspot (Mac joins the phone's hotspot; run `make up MOBILE_HOST=lan` again), or Android + cable + `make mobile-adb-reverse`. |
| VPN on the Mac or phone | VPNs often block or reroute LAN traffic. Disconnect, or allow local network access in the VPN client. |
| *Network request failed* in the app | The app talks to the wrong host: check `make lan-env`; Metro inlines `EXPO_PUBLIC_DEV_HOST` at bundle time, so after switching modes reload the app (shake → Reload). `make up` restarts Metro with `--clear` when the address changed. |
| Sign-in: *invalid redirect_uri* / *redirect_uri does not match* | identity runs without the LAN redirect URIs: `make up MOBILE_HOST=lan` (not just `make mobile`). In Expo Go the redirect is `exp://<ip>:<port>/--/auth/callback`; the IP must be the one Metro advertises. |
| Signed in, then 401s / *invalid issuer* | Services started in another mode. `make up MOBILE_HOST=lan` restarts them with the LAN issuer; `make dev-status` shows what runs. |
| Images missing on the phone | Run in LAN mode (gateway-bff rewrites `localhost:9000` image links) and allow port 9000 through the firewall. |
| Android: `adb devices` shows `unauthorized` | Unlock the phone and accept the USB debugging prompt; `adb kill-server && adb devices`. |
| Port 8081 already in use | Another Metro (or `expo start`) runs: `make dev-status`; stop it, or `CUSTOMER_APP_PORT=8091 make mobile`. |
| Stale bundle / old code in Expo Go | Expo Go → shake → *Reload*; clear its cache: Android *Settings → Apps → Expo Go → Storage → Clear cache*; iOS delete and reinstall Expo Go. Restart Metro with a clean cache: `make down && make up MOBILE_HOST=lan`, or `npx expo start --clear` in the app. |
| iOS: *Untrusted Developer* | *Settings → General → VPN & Device Management →* trust your profile. |
| iOS free account: the app stopped opening after a week | Personal Team builds expire after 7 days: rebuild with `make mobile-ios-device`. |
