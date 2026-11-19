package com.afrotransact.platform.web.diversion;

/** What a finding of the off-platform scan looks like. The names match the golden vectors. */
public enum DiversionKind {
    URL("url", "web address"),
    EMAIL("email", "email address"),
    PHONE("phone", "phone number"),
    SPELLED_DIGITS("spelled_digits", "phone number spelled out in words"),
    HANDLE("handle", "social handle"),
    PHRASE("phrase", "wording that points customers to another way of buying");

    private final String id;
    private final String noun;

    DiversionKind(String id, String noun) {
        this.id = id;
        this.noun = noun;
    }

    /** The id used in contracts/diversion/vectors.json. */
    public String id() {
        return id;
    }

    /** What to call it when telling the seller what to remove. */
    public String noun() {
        return noun;
    }
}
