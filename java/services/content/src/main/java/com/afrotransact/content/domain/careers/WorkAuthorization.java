package com.afrotransact.content.domain.careers;

/** "Authorised to work in the US?" answers. */
public enum WorkAuthorization {
    YES("Yes"),
    YES_WITH_SPONSORSHIP("Yes, with sponsorship"),
    NO("No");

    private final String label;

    WorkAuthorization(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** Accepts the label shown in the form or the enum name; null when unrecognised. */
    public static WorkAuthorization parse(String raw) {
        if (raw == null) {
            return null;
        }
        for (WorkAuthorization w : values()) {
            if (w.label.equalsIgnoreCase(raw.trim()) || w.name().equalsIgnoreCase(raw.trim())) {
                return w;
            }
        }
        return null;
    }
}
