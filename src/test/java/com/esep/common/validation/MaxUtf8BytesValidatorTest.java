package com.esep.common.validation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MaxUtf8BytesValidatorTest {

    private final MaxUtf8BytesValidator validator = validator(4);

    @Test
    void countsBytesNotCharacters() {
        assertThat(validator.isValid("abcd", null)).isTrue();    // 4 bytes
        assertThat(validator.isValid("abcde", null)).isFalse();  // 5 bytes
        assertThat(validator.isValid("жж", null)).isTrue();      // 2 chars, 4 bytes
        assertThat(validator.isValid("жжж", null)).isFalse();    // 3 chars, 6 bytes
        assertThat(validator.isValid("😀", null)).isTrue();      // 1 code point, 2 chars, 4 bytes
    }

    @Test
    void nullIsLeftToNotNull() {
        assertThat(validator.isValid(null, null)).isTrue();
    }

    private static MaxUtf8BytesValidator validator(int max) {
        MaxUtf8BytesValidator validator = new MaxUtf8BytesValidator();
        validator.initialize(new MaxUtf8Bytes() {
            @Override
            public int value() {
                return max;
            }

            @Override
            public String message() {
                return "";
            }

            @Override
            public Class<?>[] groups() {
                return new Class<?>[0];
            }

            @SuppressWarnings("unchecked")
            @Override
            public Class<? extends jakarta.validation.Payload>[] payload() {
                return new Class[0];
            }

            @Override
            public Class<? extends java.lang.annotation.Annotation> annotationType() {
                return MaxUtf8Bytes.class;
            }
        });
        return validator;
    }
}
