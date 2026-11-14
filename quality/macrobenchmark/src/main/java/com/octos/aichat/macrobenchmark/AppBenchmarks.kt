package com.octos.aichat.macrobenchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val PACKAGE = "com.octos.aichat"
private const val ITERATIONS = 10

/**
 * App launch time (time to initial display / full display) of MainActivity.
 *
 * Cold = process killed between iterations (the first-open case); warm = process
 * alive, activity recreated (switching back to the app). CompilationMode.Partial()
 * mirrors a typical installed state (baseline/cloud profile applied).
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {

    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test fun coldStartup() = startup(StartupMode.COLD)

    @Test fun warmStartup() = startup(StartupMode.WARM)

    private fun startup(mode: StartupMode) = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = CompilationMode.Partial(),
        iterations = ITERATIONS,
        startupMode = mode,
    ) {
        pressHome()
        startActivityAndWait()
    }
}

/** Frame timing (jank) while flinging the home settings screen. */
@RunWith(AndroidJUnit4::class)
class HomeScrollBenchmark {

    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test fun scrollHome() = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(),
        iterations = ITERATIONS,
        startupMode = StartupMode.WARM,
        setupBlock = {
            pressHome()
            startActivityAndWait()
        },
    ) {
        flingHome()
    }

    private fun MacrobenchmarkScope.flingHome() {
        val list = device.wait(Until.findObject(By.scrollable(true)), 5_000)
            ?: error("home screen has no scrollable container")
        // Keep the fling away from the gesture-nav edge.
        list.setGestureMargin(device.displayWidth / 5)
        repeat(2) {
            list.fling(Direction.DOWN)
            device.waitForIdle()
        }
        list.fling(Direction.UP)
        device.waitForIdle()
    }
}
