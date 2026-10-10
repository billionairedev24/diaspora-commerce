package com.afrotransact.fulfilment.domain;

/** Where a photo came from. Only {@link #LIVE_CAMERA} is accepted as evidence; gallery uploads never are. */
public enum CaptureSource {
    LIVE_CAMERA,
    GALLERY,
    UNKNOWN
}
