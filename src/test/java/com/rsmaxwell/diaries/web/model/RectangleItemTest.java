package com.rsmaxwell.diaries.web.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RectangleItemTest {
    @Test
    void permitsFiniteOriginsOutsideThePage() {
        RectangleItem rectangle = new RectangleItem(-12.5, -8.25, 100, 50);

        assertThat(rectangle.x()).isEqualTo(-12.5);
        assertThat(rectangle.y()).isEqualTo(-8.25);
    }

    @Test
    void stillRejectsNonFiniteOriginsAndNonPositiveDimensions() {
        assertThatThrownBy(() -> new RectangleItem(Double.NaN, 0, 100, 50))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("rectangle x must be finite");
        assertThatThrownBy(() -> new RectangleItem(0, Double.POSITIVE_INFINITY, 100, 50))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("rectangle y must be finite");
        assertThatThrownBy(() -> new RectangleItem(0, 0, 0, 50))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("rectangle width must be finite and positive");
        assertThatThrownBy(() -> new RectangleItem(0, 0, 100, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("rectangle height must be finite and positive");
    }
}
