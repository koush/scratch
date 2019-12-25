package com.koushikdutta.scratch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

private class BatonTestException: Exception()

class BatonTests {
    @Test
    fun testBaton() {
        val baton = Baton<Int>()
        var done = 0
        async {
            assertEquals(baton.pass(4), 1)
            assertEquals(baton.pass(5), 2)
            assertEquals(baton.pass(6), 3)
            done++
        }
        async {
            assertEquals(baton.pass(1), 4)
            assertEquals(baton.pass(2), 5)
            assertEquals(baton.pass(3), 6)
            done++
        }

        assertEquals(done, 2)
    }

    @Test
    fun testBatonFailure() {
        val baton = Baton<Int>()
        async {
            baton.raise(BatonTestException())
        }
        try {
            async {
                baton.pass(4)
            }
        }
        catch (expected: BatonTestException) {
            return
        }
        fail("exception expected")
    }

    @Test
    fun testBatonFailure2() {
        val baton = Baton<Int>()
        var done = 0
        async {
            assertEquals(baton.pass(4), 1)
            baton.raise(BatonTestException())
            done++
        }
        async {
            assertEquals(baton.pass(1), 4)
            try {
                baton.pass(4)
            }
            catch (expected: BatonTestException) {
                done++
                return@async
            }
            fail("exception expected")
        }
        assertEquals(done, 2)
    }

    @Test
    fun testBatonFinish() {
        val baton = Baton<Int>()
        var done = 0
        async {
            assertEquals(baton.pass(4), 1)
            assertEquals(baton.pass(5), 2)
            assertEquals(baton.pass(6), 3)

            baton.finish(7)
            done++
        }
        async {
            assertEquals(baton.pass(1), 4)
            assertEquals(baton.pass(2), 5)
            assertEquals(baton.pass(3), 6)

            assertEquals(baton.pass(0), 7)
            assertEquals(baton.pass(0), 7)
            assertEquals(baton.pass(0), 7)

            done++
        }

        assertEquals(done, 2)
    }

    @Test
    fun testBatonFinishTwice() {
        val baton = Baton<Int>()
        var done = 0
        async {
            baton.finish(7)
            done++
        }
        async {
            try {
                baton.finish(7)
            }
            catch (throwable: Throwable) {
                done++
                return@async
            }
            fail("exception expected")
        }

        assertEquals(done, 2)
    }

    @Test
    fun testBatonFinishTwiceRepeat() {
        val baton = Baton<Int>()
        var done = 0
        async {
            baton.finish(7)
            done++
        }
        async {
            try {
                baton.finish(7)
            }
            catch (throwable: Throwable) {
                done++
                try {
                    baton.finish(7)
                }
                catch (throwable: Throwable) {
                    done++
                    return@async
                }
                fail("exception expected")
            }
            fail("exception expected")
        }

        assertEquals(done, 3)
    }

    @Test
    fun testBatonFinishThrow() {
        val baton = Baton<Int>()
        var done = 0
        async {
            assertEquals(baton.pass(4), 1)
            assertEquals(baton.pass(5), 2)
            assertEquals(baton.pass(6), 3)

            baton.raiseFinish(BatonTestException())
            done++
        }
        async {
            assertEquals(baton.pass(1), 4)
            assertEquals(baton.pass(2), 5)
            assertEquals(baton.pass(3), 6)

            try {
                baton.pass(0)
            }
            catch (throwable: BatonTestException) {
                done++
                try {
                    baton.pass(0)
                }
                catch (throwable: BatonTestException) {
                    done++
                    return@async
                }
                fail("exception expected")
            }
            fail("exception expected")
        }

        assertEquals(done, 3)
    }
}