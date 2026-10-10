package com.afrotransact.content.domain.legal;

/** One CURRENT version per document; raising a version archives the previous one (still retrievable). */
public enum LegalVersionStatus {
    CURRENT,
    ARCHIVED
}
