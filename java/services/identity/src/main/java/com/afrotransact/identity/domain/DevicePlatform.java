package com.afrotransact.identity.domain;

/** Operating system / runtime of a registered device. */
public enum DevicePlatform {
    IOS,
    ANDROID,
    WEB;

    /** Default push transport: APNs for iOS, FCM for Android and web push. */
    public PushProvider defaultProvider() {
        return this == IOS ? PushProvider.APNS : PushProvider.FCM;
    }
}
