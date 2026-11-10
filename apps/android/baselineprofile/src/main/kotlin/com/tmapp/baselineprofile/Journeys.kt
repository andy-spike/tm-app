package com.tmapp.baselineprofile

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val PACKAGE = "com.tmapp.android"

/** Startup, then the main journeys: open a stop, search, open the planner. */
private fun MacrobenchmarkScope.journeys() {
    pressHome()
    startActivityAndWait()
    device.wait(Until.hasObject(By.textContains("Para dónde")), 10_000)
    device.findObject(By.textContains("Ruta, estación"))?.click()
    device.wait(Until.hasObject(By.focused(true)), 3_000)
    device.findObject(By.focused(true))?.text = "portal"
    device.waitForIdle()
    device.pressBack()
    device.pressBack()
    device.findObject(By.text("Viaje"))?.click()
    device.waitForIdle()
}

@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule val rule = BaselineProfileRule()

    @Test fun generate() = rule.collect(PACKAGE, includeInStartupProfile = true) { journeys() }
}

@RunWith(AndroidJUnit4::class)
class StartupBenchmark {
    @get:Rule val rule = MacrobenchmarkRule()

    @Test fun coldStartWithProfile() = startup(CompilationMode.Partial())
    @Test fun coldStartWithoutProfile() = startup(CompilationMode.None())

    private fun startup(mode: CompilationMode) = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = mode,
        startupMode = StartupMode.COLD,
        iterations = 5,
        setupBlock = { pressHome() },
    ) {
        startActivityAndWait()
        device.wait(Until.hasObject(By.textContains("Para dónde")), 10_000)
    }
}
