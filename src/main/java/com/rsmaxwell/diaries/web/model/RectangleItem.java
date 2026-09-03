package com.rsmaxwell.diaries.web.model;

public record RectangleItem(double x, double y, double width, double height) {
    public RectangleItem {
        Validation.nonNegativeFinite(x, "rectangle x");
        Validation.nonNegativeFinite(y, "rectangle y");
        Validation.positiveFinite(width, "rectangle width");
        Validation.positiveFinite(height, "rectangle height");
    }
}
