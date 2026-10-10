package com.afrotransact.fulfilment.domain;

/** The 10-point pre-trip inspection (DVIR). Brakes, tyres and lights are critical: failing one blocks the route. */
public enum InspectionItem {
    TYRES("Tyres & wheels", true),
    BRAKES("Brakes", true),
    LIGHTS("Lights & signals", true),
    MIRRORS("Mirrors & windshield", false),
    HORN_WIPERS("Horn & wipers", false),
    NO_LEAKS("No fluid leaks", false),
    CARGO_AREA("Cargo area clean & secure", false),
    COLD_BOX("Cold box powered", false),
    SAFETY_KIT("Extinguisher & first-aid kit", false),
    PHONE_MOUNT("Phone mount & charger", false);

    private final String label;
    private final boolean critical;

    InspectionItem(String label, boolean critical) {
        this.label = label;
        this.critical = critical;
    }

    public String label() {
        return label;
    }

    public boolean critical() {
        return critical;
    }
}
