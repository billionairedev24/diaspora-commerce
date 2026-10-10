package com.afrotransact.identity.application;

import com.afrotransact.identity.domain.DevicePlatform;
import com.afrotransact.identity.domain.PushApp;
import com.afrotransact.identity.domain.PushProvider;

/**
 * @param provider push transport; null picks the platform default (iOS → APNs, Android and web → FCM). Apps that
 *                 send every platform through FCM (e.g. Expo/Firebase on iOS) pass {@code FCM}.
 */
public record RegisterDeviceCommand(PushApp app, DevicePlatform platform, PushProvider provider, String token,
                                    String deviceName) {
}
