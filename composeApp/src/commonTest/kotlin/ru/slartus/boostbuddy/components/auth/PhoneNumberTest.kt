package ru.slartus.boostbuddy.components.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PhoneNumberTest {
    @Test
    fun formatsSerbianAndRussianNumbersWithExplicitCountryCodes() {
        assertEquals("+381641234567", normalizePhoneNumber("+381", "64 123 4567"))
        assertEquals("+79991234567", normalizePhoneNumber("+7", "(999) 123-45-67"))
    }

    @Test
    fun acceptsOtherEditableCountryCodesWithoutGuessingRussia() {
        assertEquals("+442079460123", normalizePhoneNumber("+44", "20 7946 0123"))
        assertEquals("+12025550123", normalizePhoneNumber("1", "202-555-0123"))
        assertNull(normalizePhoneNumber("", "9991234567"))
        assertEquals("+3819991234567", normalizePhoneNumber("+381", "9991234567"))
    }

    @Test
    fun rejectsDuplicateCountryPrefixAndInvalidInput() {
        assertNull(normalizePhoneNumber("+381", "+381641234567"))
        assertNull(normalizePhoneNumber("+0381", "64 123 4567"))
        assertNull(normalizePhoneNumber("+0", "9991234567"))
        assertNull(normalizePhoneNumber("+381", "64abc1234567"))
        assertNull(normalizePhoneNumber("+381", ""))
        assertNull(normalizePhoneNumber("+381", "12"))
        assertNull(normalizePhoneNumber("+381", "1234567890123"))
    }

    @Test
    fun preservesExplicitDigitsInsteadOfRemovingUnknownTrunkPrefixes() {
        assertEquals("+390212345678", normalizePhoneNumber("+39", "02 12345678"))
    }
}
