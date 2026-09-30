@file:Suppress("unused")

// Minimal kotlin.test API over JUnit4, used ONLY by tools/core-verify.sh in environments without Maven access.
// CI uses the real kotlin-test artifacts; tests must only use the functions defined here (or add them here).
package kotlin.test

typealias Test = org.junit.Test
typealias BeforeTest = org.junit.Before
typealias AfterTest = org.junit.After

fun <T> assertEquals(expected: T, actual: T, message: String? = null) =
    org.junit.Assert.assertEquals(message, expected, actual)

fun <T> assertNotEquals(illegal: T, actual: T, message: String? = null) =
    org.junit.Assert.assertNotEquals(message, illegal, actual)

fun assertTrue(actual: Boolean, message: String? = null) = org.junit.Assert.assertTrue(message, actual)
fun assertFalse(actual: Boolean, message: String? = null) = org.junit.Assert.assertFalse(message, actual)
fun assertNull(actual: Any?, message: String? = null) = org.junit.Assert.assertNull(message, actual)

fun <T : Any> assertNotNull(actual: T?, message: String? = null): T {
    org.junit.Assert.assertNotNull(message, actual)
    return actual!!
}

inline fun <reified T> assertIs(value: Any?, message: String? = null): T {
    if (value !is T) throw AssertionError((message?.let { "$it. " } ?: "") + "Expected ${T::class.simpleName}, got $value")
    return value
}

inline fun <reified T : Throwable> assertFailsWith(message: String? = null, block: () -> Unit): T {
    try {
        block()
    } catch (e: Throwable) {
        if (e is T) return e
        throw AssertionError((message ?: "") + " Expected ${T::class.simpleName}, got $e", e)
    }
    throw AssertionError((message ?: "") + " Expected ${T::class.simpleName}, nothing thrown")
}

fun fail(message: String? = null): Nothing = throw AssertionError(message)
