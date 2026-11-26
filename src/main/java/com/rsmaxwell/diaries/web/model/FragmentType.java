package com.rsmaxwell.diaries.web.model;

public enum FragmentType {
    MARQUEE, // Historic retained contract; removed after coordinated migration
    TEXT, // Page- or year-level text; Marquee is optional
    IMAGE,
    /**
     * Sentinel for a future explicit non-null string type. FragmentItem.rawType()
     * preserves the exact supplied value for diagnostics.
     */
    UNKNOWN
}
