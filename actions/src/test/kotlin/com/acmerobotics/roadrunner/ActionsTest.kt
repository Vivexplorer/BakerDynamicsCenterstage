package com.acmerobotics.roadrunner

import com.acmerobotics.dashboard.canvas.Canvas
import com.acmerobotics.dashboard.telemetry.TelemetryPacket
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class Counter(
    val n: Int,
    val f: () -> Unit,
) : Action {
    override fun run(p: TelemetryPacket) =
        if (n <= 0) {
            null
        } else {
            f()
            Counter(n - 1, f)
        }

    override fun preview(c: Canvas) {}
}

fun runBlockingCount(a: Action): Int {
    var a2: Action? = a
    var i = -1
    while (a2 != null) {
        a2 = a2.run(TelemetryPacket())
        i += 1
    }
    return i
}

class ActionsTest {
    @Test
    fun testCounter() {
        val steps = mutableListOf<Int>()
        assertEquals(3, runBlockingCount(Counter(3) { steps.add(0) }))
        assertEquals(listOf(0, 0, 0), steps)
    }

    @Test
    fun testSequential() {
        val steps = mutableListOf<Int>()
        assertEquals(
            4,
            runBlockingCount(
                SequentialAction(
                    Counter(2) { steps.add(0) },
                    Counter(2) { steps.add(1) }
                )
            )
        )
        assertEquals(listOf(0, 0, 1, 1), steps)

        steps.clear()
        assertEquals(
            0,
            runBlockingCount(
                SequentialAction(
                    Counter(0) { steps.add(0) },
                    Counter(0) { steps.add(1) }
                )
            )
        )
        assert(steps.isEmpty())
    }

    @Test
    fun testParallel() {
        val steps = mutableListOf<Int>()
        assertEquals(
            2,
            runBlockingCount(
                ParallelAction(
                    Counter(2) { steps.add(0) },
                    Counter(2) { steps.add(1) }
                )
            )
        )
        assertEquals(listOf(0, 1, 0, 1), steps)

        steps.clear()
        assertEquals(
            3,
            runBlockingCount(
                ParallelAction(
                    Counter(2) { steps.add(0) },
                    Counter(3) { steps.add(1) }
                )
            )
        )
        assertEquals(listOf(0, 1, 0, 1, 1), steps)

        steps.clear()
        assertEquals(
            0,
            runBlockingCount(
                ParallelAction(
                    Counter(0) { steps.add(0) },
                    Counter(0) { steps.add(1) }
                )
            )
        )
        assert(steps.isEmpty())
    }

    @Test
    fun testNesting() {
        val steps = mutableListOf<Int>()
        assertEquals(
            1,
            runBlockingCount(
                ParallelAction(
                    ParallelAction(
                        Counter(0) { steps.add(0) },
                        Counter(1) { steps.add(1) }
                    ),
                    Counter(0) { steps.add(2) }
                )
            )
        )
        assertEquals(listOf(1), steps)
    }
}
