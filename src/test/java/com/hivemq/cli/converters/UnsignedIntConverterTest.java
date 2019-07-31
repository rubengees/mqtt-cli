package com.hivemq.cli.converters;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UnsignedIntConverterTest {

    private UnsignedIntConverter unsignedIntConverter;

    @BeforeEach
    void setUp() {
        unsignedIntConverter = new UnsignedIntConverter();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "test", "132.4", "0.5", "abc123", "123abc", "a.3", "3.b"})
    void testInvalidString(String s) {
        final Exception e = assertThrows(Exception.class, () -> unsignedIntConverter.convert(s));
        assertEquals(UnsignedIntConverter.WRONG_INPUT_MESSAGE, e.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "4294967296", "-9223372036854775808", "9223372036854775807", "-1522155", "-5125125125"})
    void testNegativeNumber(String s) {
        final Exception e = assertThrows(java.lang.Exception.class, () -> unsignedIntConverter.convert(s));
        assertEquals(UnsignedIntConverter.WRONG_INPUT_MESSAGE, e.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "1", "4294967295", "4644664", "545522"})
    void testSuccess(String s) throws Exception {
        long got = unsignedIntConverter.convert(s);
        long expected = Long.parseLong(s);
        assertEquals(expected, got);
    }


}