package com.koushikdutta.scratch

import kotlin.test.*

private class BatonTestException: Exception()

class BatonTests {
    @Test
    fun testBatonSimple() {
        val baton = Baton<Int>()
        var done = 0
        async {
            assertEquals(baton.pass(4), 1)
            done++
        }
        async {
            assertEquals(baton.pass(1), 4)
            done++
        }

        assertEquals(done, 2)
    }

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
            .rethrow()
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
            val check1 = baton.finish(5)
            assertTrue(check1!!.finished)
            assertEquals(check1.value, 7)
            done++
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
            val check1 = baton.finish(5)
            assertTrue(check1!!.finished)
            assertEquals(check1.value, 7)
            done++

            val check2 = baton.finish(4)
            assertTrue(check2!!.finished)
            assertEquals(check2.value, 7)
            done++
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

    @Test
    fun testBatonTossSimple() {
        val baton = Baton<Int>()
        var done = 0
        async {
            assertEquals(baton.pass(4), 1)
            done++
        }
        async {
            assertEquals(baton.toss(1), 4)
            done++
        }

        assertEquals(done, 2)
    }

    @Test
    fun testBatonToss() {
        val baton = Baton<Int>()
        var done = 0
        async {
            assertEquals(baton.pass(4), 1)
            assertEquals(baton.pass(5), 2)
            assertEquals(baton.pass(6), 3)
            done++
        }
        async {
            assertEquals(baton.toss(1), 4)
            assertEquals(baton.toss(2), 5)
            assertEquals(baton.toss(3), 6)

            done++
        }

        assertEquals(done, 2)
    }

    @Test
    fun testBatonEmpty() {
        val baton = Baton<Int>()
        assertNull(baton.toss(4))
    }

    @Test
    fun testBatonEmpty2() {
        val baton = Baton<Int>()
        assertNull(baton.toss(4))
        assertEquals(baton.toss(0), 4)
    }

    @Test
    fun testBatonEmpty3() {
        val baton = Baton<Int>()
        assertNull(baton.toss(4))
        assertEquals(baton.toss(0), 4)
        assertNull(baton.toss(5))
        assertEquals(baton.toss(1), 5)
    }

    @Test
    fun testBatonTake() {
        val baton = Baton<Int>()
        assertNull(baton.take(2), null)
        assertNull(baton.toss(3))
        assertEquals(baton.take(4), 3)
        assertNull(baton.take(5), null)
    }
}