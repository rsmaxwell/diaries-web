package com.rsmaxwell.diaries.web.model;

public enum FragmentType {
    MARQUEE,
    IMAGE,
    /**
     * Sentinel for a future explicit non-null string type. FragmentItem.rawType()
     * preserves the exact supplied value for diagnostics.
     */
    UNKNOWN
}
