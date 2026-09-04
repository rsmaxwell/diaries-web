package com.rsmaxwell.diaries.web.model;

import java.util.Objects;

final class Validation {
    private Validation() {
    }

    static void positiveId(long value, String name) {
        positive(value, name);
    }

    static void positive(long value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    static void nonNegative(long value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }

    static void finite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }

    static void positiveFinite(double value, String name) {
        if (!Double.isFinite(value) || value <= 0) {
            throw new IllegalArgumentException(name + " must be finite and positive");
        }
    }

    static String notBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    static <T> T notNull(T value, String name) {
        return Objects.requireNonNull(value, name + " must not be null");
    }
}
