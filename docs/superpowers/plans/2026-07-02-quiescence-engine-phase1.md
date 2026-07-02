# Quiescence Engine Phase 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace Maestro's fixed-sleep/worst-case-timeout settle core with profile-scoped budgets, cached-frame settle detection, adaptive polling, transition-class settle policies, and Android animation disabling — no app injection required.

**Architecture:** `SpeedSettings` (mechanism, maestro-client) carries the timing knobs; `SpeedProfile` (policy, maestro-orchestra-models) maps `default`/`fast`/`ferrari` to settings, resolved from flow config ext or `MAESTRO_SPEED_PROFILE` env. Orchestra resolves the profile at flow start, injects budgets into every settle/lookup, and toggles animations. ScreenshotUtils gains a per-driver `FrameCache` so settle checks diff one fresh capture against the previous frame. `MaestroTimer` polling loops get 16→50→100ms adaptive backoff.

**Tech Stack:** Kotlin/JVM (Gradle, Java 21 toolchain), JUnit 5 + Google Truth, okio, romankh3 ImageComparison.

**Spec:** `docs/superpowers/specs/2026-07-02-quiescence-engine-design.md`

**Recorded spec deviations (Phase 1 only):**
- Per-command YAML `transition:` key is deferred; the existing per-command `waitToSettleTimeoutMs` override (0 = skip settle) covers explicit control. Transition classes are applied via a per-command-type default mapping.
- `ferrari` profile equals `fast` until the copilot lands in Phase 2 (event-driven quiescence replaces the 500ms black-box budget).
- The `default` profile must behave byte-for-byte like current behavior: budgets/skips only engage for non-default profiles.
- Two off-hot-path sleeps are retained with justification (screenrecord flush, install/launch startup loops) — see Task 9. The spec's "quiescence is the only wait primitive" applies to the per-step hot path in Phase 1.
- Diagnostics: Phase 1 records total per-command `durationMs`; the lookup/action/settle breakdown and blocked-signal naming arrive with the copilot (Phases 2–4), which is what makes signals nameable.

**Conventions for every task:**
- Run tests with `./gradlew :<module>:test --tests '<ClassName>'` from the repo root.
- Google Truth assertions: `assertThat(x).isEqualTo(y)`.
- Commit after each green test cycle. Commit messages end with:
  `Claude-Session: https://claude.ai/code/session_01ELhhynn5NKRwKyp8GdgPxR`

---

### Task 1: SpeedSettings data class (mechanism layer)

**Files:**
- Create: `maestro-client/src/main/java/maestro/settle/SpeedSettings.kt`
- Test: `maestro-client/src/test/java/maestro/settle/SpeedSettingsTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package maestro.settle

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class SpeedSettingsTest {

    @Test
    fun `defaults match current upstream timing behavior`() {
        val s = SpeedSettings.DEFAULT
        assertThat(s.minorSettleBudgetMs).isNull()
        assertThat(s.screenSettleBudgetMs).isNull()
        assertThat(s.lookupTimeoutMs).isEqualTo(17000L)
        assertThat(s.optionalLookupTimeoutMs).isEqualTo(7000L)
        assertThat(s.skipPreTapSettle).isFalse()
        assertThat(s.disableAnimations).isFalse()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :maestro-client:test --tests 'maestro.settle.SpeedSettingsTest'`
Expected: FAIL (compilation error: unresolved reference `SpeedSettings`)

- [ ] **Step 3: Write the implementation**

```kotlin
package maestro.settle

/**
 * Timing knobs for the execution engine. Null settle budgets mean "driver default"
 * (preserves upstream behavior). Policy (which profile maps to which values) lives
 * in maestro-orchestra-models SpeedProfile; this class is pure mechanism.
 */
data class SpeedSettings(
    /** Settle budget after minor-transition actions (taps, swipes). Null = driver default. */
    val minorSettleBudgetMs: Int? = null,
    /** Settle budget after screen-transition actions (launch, back, openLink). Null = driver default. */
    val screenSettleBudgetMs: Int? = null,
    val lookupTimeoutMs: Long = 17000L,
    val optionalLookupTimeoutMs: Long = 7000L,
    /** Skip the pre-tap settle when no action has occurred since the last settled hierarchy. */
    val skipPreTapSettle: Boolean = false,
    /** Disable OS/system animations for the session (Android system scales; iOS via copilot in Phase 2). */
    val disableAnimations: Boolean = false,
) {
    companion object {
        val DEFAULT = SpeedSettings()
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :maestro-client:test --tests 'maestro.settle.SpeedSettingsTest'`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add maestro-client/src/main/java/maestro/settle/SpeedSettings.kt maestro-client/src/test/java/maestro/settle/SpeedSettingsTest.kt
git commit -m "feat(settle): add SpeedSettings mechanism knobs"
```

---

### Task 2: SpeedProfile enum + resolution (policy layer)

**Files:**
- Create: `maestro-orchestra-models/src/main/java/maestro/orchestra/SpeedProfile.kt`
- Test: `maestro-orchestra/src/test/java/maestro/orchestra/SpeedProfileTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package maestro.orchestra

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class SpeedProfileTest {

    @Test
    fun `resolves from flow config ext key`() {
        val profile = SpeedProfile.resolve(mapOf("speedProfile" to "fast")) { null }
        assertThat(profile).isEqualTo(SpeedProfile.FAST)
    }

    @Test
    fun `flow config wins over env var`() {
        val profile = SpeedProfile.resolve(mapOf("speedProfile" to "ferrari")) { "fast" }
        assertThat(profile).isEqualTo(SpeedProfile.FERRARI)
    }

    @Test
    fun `falls back to env var when config has no key`() {
        val profile = SpeedProfile.resolve(emptyMap()) { name ->
            if (name == "MAESTRO_SPEED_PROFILE") "FAST" else null
        }
        assertThat(profile).isEqualTo(SpeedProfile.FAST)
    }

    @Test
    fun `resolution is case-insensitive`() {
        assertThat(SpeedProfile.resolve(mapOf("speedProfile" to "FeRrArI")) { null })
            .isEqualTo(SpeedProfile.FERRARI)
    }

    @Test
    fun `unknown value and null config resolve to DEFAULT`() {
        assertThat(SpeedProfile.resolve(mapOf("speedProfile" to "warp9")) { null })
            .isEqualTo(SpeedProfile.DEFAULT)
        assertThat(SpeedProfile.resolve(null) { null }).isEqualTo(SpeedProfile.DEFAULT)
    }

    @Test
    fun `fast profile carries speed settings`() {
        val s = SpeedProfile.FAST.settings
        assertThat(s.minorSettleBudgetMs).isEqualTo(500)
        assertThat(s.screenSettleBudgetMs).isEqualTo(1500)
        assertThat(s.lookupTimeoutMs).isEqualTo(3000L)
        assertThat(s.optionalLookupTimeoutMs).isEqualTo(2000L)
        assertThat(s.skipPreTapSettle).isTrue()
        assertThat(s.disableAnimations).isTrue()
    }

    @Test
    fun `default profile carries untouched settings`() {
        assertThat(SpeedProfile.DEFAULT.settings).isEqualTo(maestro.settle.SpeedSettings.DEFAULT)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :maestro-orchestra:test --tests 'maestro.orchestra.SpeedProfileTest'`
Expected: FAIL (unresolved reference `SpeedProfile`)

- [ ] **Step 3: Write the implementation**

```kotlin
package maestro.orchestra

import maestro.settle.SpeedSettings

/**
 * Speed profiles bundle SpeedSettings. Resolution precedence:
 * flow config `speedProfile:` key (lands in MaestroConfig.ext via @JsonAnySetter,
 * same pattern as the `jsEngine` ext key) > MAESTRO_SPEED_PROFILE env > DEFAULT.
 *
 * FERRARI equals FAST until the Phase 2 copilot provides event-driven quiescence.
 */
enum class SpeedProfile(val settings: SpeedSettings) {
    DEFAULT(SpeedSettings.DEFAULT),
    FAST(
        SpeedSettings(
            minorSettleBudgetMs = 500,
            screenSettleBudgetMs = 1500,
            lookupTimeoutMs = 3000L,
            optionalLookupTimeoutMs = 2000L,
            skipPreTapSettle = true,
            disableAnimations = true,
        )
    ),
    FERRARI(FAST.settings.copy());

    companion object {
        const val ENV_VAR = "MAESTRO_SPEED_PROFILE"
        const val CONFIG_KEY = "speedProfile"

        fun resolve(
            configExt: Map<String, Any?>?,
            env: (String) -> String? = System::getenv,
        ): SpeedProfile {
            val raw = (configExt?.get(CONFIG_KEY) as? String) ?: env(ENV_VAR)
            return entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: DEFAULT
        }
    }
}
```

Note: Kotlin forbids referencing `FAST` from `FERRARI`'s constructor argument in some enum forms; if `FERRARI(FAST.settings.copy())` fails to compile, extract a private `val FAST_SETTINGS = SpeedSettings(...)` file-level constant and pass it to both entries.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :maestro-orchestra:test --tests 'maestro.orchestra.SpeedProfileTest'`
Expected: PASS (7 tests)

- [ ] **Step 5: Commit**

```bash
git add maestro-orchestra-models/src/main/java/maestro/orchestra/SpeedProfile.kt maestro-orchestra/src/test/java/maestro/orchestra/SpeedProfileTest.kt
git commit -m "feat(settle): add SpeedProfile policy with config/env resolution"
```

---

### Task 3: Adaptive backoff in MaestroTimer

**Files:**
- Modify: `maestro-utils/src/main/kotlin/MaestroTimer.kt`
- Test: `maestro-utils/src/test/kotlin/MaestroTimerTest.kt` (create if absent; check for an existing test first and extend it instead)

- [ ] **Step 1: Write the failing test**

```kotlin
import com.google.common.truth.Truth.assertThat
import maestro.utils.MaestroTimer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class MaestroTimerTest {

    private val recordedSleeps = mutableListOf<Long>()
    private lateinit var originalSleep: (MaestroTimer.Reason, Long) -> Unit

    @BeforeEach
    fun setUp() {
        originalSleep = MaestroTimer.sleep
        MaestroTimer.setTimerFunc { _, ms -> recordedSleeps += ms }
    }

    @AfterEach
    fun tearDown() {
        MaestroTimer.setTimerFunc(originalSleep)
    }

    @Test
    fun `withTimeout sleeps with adaptive backoff between failed attempts`() {
        var calls = 0
        val result = MaestroTimer.withTimeout(200L) {
            calls++
            if (calls == 4) "found" else null
        }
        assertThat(result).isEqualTo("found")
        // 3 failed attempts -> 3 sleeps: 16, 50, 100
        assertThat(recordedSleeps).containsExactly(16L, 50L, 100L).inOrder()
    }

    @Test
    fun `withTimeout does not sleep after a successful attempt`() {
        val result = MaestroTimer.withTimeout(200L) { "immediate" }
        assertThat(result).isEqualTo("immediate")
        assertThat(recordedSleeps).isEmpty()
    }

    @Test
    fun `withTimeout backoff plateaus at the last delay`() {
        var calls = 0
        MaestroTimer.withTimeout(100L) {
            calls++
            if (calls >= 6) "done" else null
        }
        assertThat(recordedSleeps).containsExactly(16L, 50L, 100L, 100L, 100L).inOrder()
    }

    @Test
    fun `retryUntilTrue does not sleep before the first attempt`() {
        val ok = MaestroTimer.retryUntilTrue(100L) { true }
        assertThat(ok).isTrue()
        assertThat(recordedSleeps).isEmpty()
    }

    @Test
    fun `retryUntilTrue with explicit delayMs keeps fixed delay after failures`() {
        var calls = 0
        MaestroTimer.retryUntilTrue(100L, delayMs = 30L) {
            calls++
            calls >= 3
        }
        assertThat(recordedSleeps).containsExactly(30L, 30L).inOrder()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :maestro-utils:test --tests 'MaestroTimerTest'`
Expected: FAIL — current `withTimeout` never sleeps (busy loop, `recordedSleeps` empty), and current `retryUntilTrue` sleeps *before* every attempt including the first.

- [ ] **Step 3: Rewrite the two functions**

Replace the bodies of `withTimeout` and `retryUntilTrue` in `maestro-utils/src/main/kotlin/MaestroTimer.kt` (keep `sleep`, `setTimerFunc`, and `Reason` unchanged):

```kotlin
    val DEFAULT_BACKOFF_MS: List<Long> = listOf(16L, 50L, 100L)

    fun <T> withTimeout(
        timeoutMs: Long,
        backoffMs: List<Long> = DEFAULT_BACKOFF_MS,
        block: () -> T?,
    ): T? {
        val endTime = System.currentTimeMillis() + timeoutMs
        var attempt = 0

        do {
            val result = block()
            if (result != null) {
                return result
            }
            val delay = backoffMs.getOrElse(attempt) { backoffMs.last() }
            attempt++
            if (System.currentTimeMillis() + delay < endTime) {
                sleep(Reason.BUFFER, delay)
            }
        } while (System.currentTimeMillis() < endTime)

        return null
    }

    fun retryUntilTrue(
        timeoutMs: Long,
        delayMs: Long? = null,
        onException: (Exception) -> Unit = {},
        block: () -> Boolean,
    ): Boolean {
        val endTime = System.currentTimeMillis() + timeoutMs
        var attempt = 0

        do {
            try {
                if (block()) {
                    return true
                }
            } catch (ignored: Exception) {
                onException(ignored)
            }
            val delay = delayMs ?: DEFAULT_BACKOFF_MS.getOrElse(attempt) { DEFAULT_BACKOFF_MS.last() }
            attempt++
            if (System.currentTimeMillis() + delay < endTime) {
                sleep(Reason.BUFFER, delay)
            }
        } while (System.currentTimeMillis() < endTime)

        return false
    }
```

Behavioral change to verify at call sites: `retryUntilTrue` previously slept *before* each attempt (including the first) when `delayMs` was set. Run `grep -rn "retryUntilTrue" --include='*.kt' maestro-client/src/main maestro-ios-driver/src/main maestro-cli/src/main` and confirm no caller depends on a pre-first-attempt sleep (as of the spec recon, callers pass no `delayMs` or use it as a poll interval — the new semantics are strictly better).

- [ ] **Step 4: Run tests, including existing suites for regressions**

Run: `./gradlew :maestro-utils:test`
Expected: PASS (all)

- [ ] **Step 5: Commit**

```bash
git add maestro-utils/src/main/kotlin/MaestroTimer.kt maestro-utils/src/test/kotlin/MaestroTimerTest.kt
git commit -m "feat(settle): adaptive backoff in MaestroTimer polling loops"
```

---

### Task 4: FrameCache + single-capture settle detection

**Files:**
- Create: `maestro-client/src/main/java/maestro/utils/FrameCache.kt`
- Modify: `maestro-client/src/main/java/maestro/utils/ScreenshotUtils.kt` (the `waitUntilScreenIsStatic` companion function)
- Modify call sites: `maestro-client/src/main/java/maestro/drivers/AndroidDriver.kt:793`, `maestro-client/src/main/java/maestro/drivers/WebDriver.kt:563`, `maestro-client/src/main/java/maestro/drivers/CdpWebDriver.kt:603`, `maestro-client/src/main/java/maestro/Maestro.kt:626`
- Test: `maestro-client/src/test/java/maestro/utils/ScreenSettleTest.kt`

- [ ] **Step 1: Write FrameCache**

```kotlin
package maestro.utils

import java.awt.image.BufferedImage

/**
 * Holds the most recent screenshot frame for a driver session so settle checks
 * can diff one fresh capture against the previous frame instead of always
 * capturing two fresh screenshots.
 */
class FrameCache {
    var lastFrame: BufferedImage? = null
        private set

    fun update(frame: BufferedImage) {
        lastFrame = frame
    }

    fun clear() {
        lastFrame = null
    }
}
```

- [ ] **Step 2: Write the failing test**

The fake driver implements the `maestro.Driver` interface; only `takeScreenshot` matters — stub every other member with `TODO()` (the compiler enforces the exact member list; none of them are invoked by the code under test).

```kotlin
package maestro.utils

import com.google.common.truth.Truth.assertThat
import maestro.Driver
import okio.Buffer
import okio.Sink
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

class ScreenSettleTest {

    private lateinit var originalSleep: (MaestroTimer.Reason, Long) -> Unit

    @BeforeEach
    fun setUp() {
        originalSleep = MaestroTimer.sleep
        MaestroTimer.setTimerFunc { _, _ -> } // no real sleeping in tests
    }

    @AfterEach
    fun tearDown() {
        MaestroTimer.setTimerFunc(originalSleep)
    }

    private fun solidPng(color: Color): ByteArray {
        val img = BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = color
        g.fillRect(0, 0, 20, 20)
        g.dispose()
        val out = ByteArrayOutputStream()
        ImageIO.write(img, "PNG", out)
        return out.toByteArray()
    }

    /** Serves the queued frames in order; repeats the last frame when the queue empties. */
    private class FakeScreenshotDriver(frames: List<ByteArray>) : Driver {
        private val queue = frames.toMutableList()
        var captures = 0
            private set

        override fun takeScreenshot(out: Sink, compressed: Boolean) {
            captures++
            val bytes = if (queue.size > 1) queue.removeAt(0) else queue.first()
            val buffer = Buffer().write(bytes)
            out.write(buffer, buffer.size)
        }

        // Every other Driver member: override with TODO() — not exercised here.
    }

    @Test
    fun `static screen with empty cache needs two captures`() {
        val red = solidPng(Color.RED)
        val driver = FakeScreenshotDriver(listOf(red, red))

        val isStatic = ScreenshotUtils.waitUntilScreenIsStatic(1000L, 0.005, driver, FrameCache())

        assertThat(isStatic).isTrue()
        assertThat(driver.captures).isEqualTo(2)
    }

    @Test
    fun `static screen with primed cache needs one capture`() {
        val red = solidPng(Color.RED)
        val cache = FrameCache()
        cache.update(ImageIO.read(red.inputStream()))
        val driver = FakeScreenshotDriver(listOf(red))

        val isStatic = ScreenshotUtils.waitUntilScreenIsStatic(1000L, 0.005, driver, cache)

        assertThat(isStatic).isTrue()
        assertThat(driver.captures).isEqualTo(1)
    }

    @Test
    fun `animating screen settles once frames repeat`() {
        val red = solidPng(Color.RED)
        val blue = solidPng(Color.BLUE)
        val green = solidPng(Color.GREEN)
        // red -> blue -> green -> green : settles on the 4th capture
        val driver = FakeScreenshotDriver(listOf(red, blue, green, green))

        val isStatic = ScreenshotUtils.waitUntilScreenIsStatic(5000L, 0.005, driver, FrameCache())

        assertThat(isStatic).isTrue()
        assertThat(driver.captures).isEqualTo(4)
    }

    @Test
    fun `screen that never settles returns false at timeout`() {
        // Alternates forever between two frames
        val driver = object : Driver {
            private var flip = false
            override fun takeScreenshot(out: Sink, compressed: Boolean) {
                flip = !flip
                val bytes = ScreenSettleTest().solidPng(if (flip) Color.RED else Color.BLUE)
                val buffer = Buffer().write(bytes)
                out.write(buffer, buffer.size)
            }
            // Every other Driver member: override with TODO().
        }

        val isStatic = ScreenshotUtils.waitUntilScreenIsStatic(100L, 0.005, driver, FrameCache())

        assertThat(isStatic).isFalse()
    }
}
```

(Adjust the never-settles test's frame factory if the helper visibility fights you — a top-level private function works too. The assertion that matters is `isStatic == false` within a real 100ms deadline.)

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew :maestro-client:test --tests 'maestro.utils.ScreenSettleTest'`
Expected: FAIL — current `waitUntilScreenIsStatic(timeoutMs, threshold, driver)` has no `FrameCache` parameter (compilation error).

- [ ] **Step 4: Rewrite waitUntilScreenIsStatic in ScreenshotUtils**

Replace the existing companion function (currently: two fresh screenshots per `retryUntilTrue` iteration) with:

```kotlin
        fun waitUntilScreenIsStatic(
            timeoutMs: Long,
            threshold: Double,
            driver: Driver,
            frameCache: FrameCache? = null,
        ): Boolean {
            val endTime = System.currentTimeMillis() + timeoutMs
            var previous: BufferedImage? = frameCache?.lastFrame
            var attempt = 0

            do {
                val current = tryTakingScreenshot(driver)
                    ?: return false

                val prev = previous
                if (prev != null && prev.width == current.width && prev.height == current.height) {
                    val diff = ImageComparison(prev, current).compareImages().differencePercent
                    if (diff <= threshold) {
                        frameCache?.update(current)
                        return true
                    }
                }
                previous = current
                frameCache?.update(current)

                val delay = MaestroTimer.DEFAULT_BACKOFF_MS.getOrElse(attempt) { MaestroTimer.DEFAULT_BACKOFF_MS.last() }
                attempt++
                if (System.currentTimeMillis() + delay < endTime) {
                    MaestroTimer.sleep(MaestroTimer.Reason.BUFFER, delay)
                }
            } while (System.currentTimeMillis() < endTime)

            return false
        }
```

Check the type of `differencePercent` in the vendored ImageComparison version (`float`); if the comparison against `threshold: Double` fails to compile, convert with `.toDouble()`.

- [ ] **Step 5: Wire per-driver FrameCache at the call sites**

In each of `AndroidDriver`, `WebDriver`, `CdpWebDriver` add a field and pass it through:

```kotlin
    private val frameCache = FrameCache()
```

```kotlin
    // AndroidDriver.kt:793, WebDriver.kt:563, CdpWebDriver.kt:603
    ScreenshotUtils.waitUntilScreenIsStatic(timeoutMs, SCREENSHOT_DIFF_THRESHOLD, this, frameCache)
```

In `Maestro.kt:626` (the `waitForAnimationToEnd` path) add a `private val frameCache = FrameCache()` field to `Maestro` and pass it likewise. Import `maestro.utils.FrameCache` where needed. `IOSDriver` keeps its native `isScreenStatic()` path — untouched.

- [ ] **Step 6: Run the module test suite**

Run: `./gradlew :maestro-client:test`
Expected: PASS (new ScreenSettleTest green, no regressions)

- [ ] **Step 7: Commit**

```bash
git add maestro-client/src/main/java/maestro/utils/FrameCache.kt maestro-client/src/main/java/maestro/utils/ScreenshotUtils.kt maestro-client/src/main/java/maestro/drivers/AndroidDriver.kt maestro-client/src/main/java/maestro/drivers/WebDriver.kt maestro-client/src/main/java/maestro/drivers/CdpWebDriver.kt maestro-client/src/main/java/maestro/Maestro.kt maestro-client/src/test/java/maestro/utils/ScreenSettleTest.kt
git commit -m "feat(settle): cached-frame settle detection with adaptive polling"
```

---

### Task 5: Settle skip (timeout 0), quiescence epoch, and pre-tap cache in Maestro.kt

**Files:**
- Modify: `maestro-client/src/main/java/maestro/Maestro.kt`
- Test: `maestro-client/src/test/java/maestro/QuiescenceEpochTest.kt`

Before editing, read `Maestro.kt:180-230` (tapOnElement), `:300-330` (performTap), and `:459-465` (waitForAppToSettle) to confirm current signatures and nullability — adapt the snippets below to match exactly.

- [ ] **Step 1: Add state and the markAction helper to Maestro**

```kotlin
    var speedSettings: SpeedSettings = SpeedSettings.DEFAULT

    private var actionEpoch: Long = 0
    private var settledEpoch: Long = -1
    private var settledHierarchy: ViewHierarchy? = null

    /** Any driver mutation invalidates the settled-hierarchy cache. */
    private fun markAction() {
        actionEpoch++
    }
```

Import `maestro.settle.SpeedSettings`. Then run `grep -n "driver\." maestro-client/src/main/java/maestro/Maestro.kt` and insert `markAction()` as the first statement of every public method that invokes a *mutating* driver call. The known list (verify against the grep output and add any mutating method it reveals): `launchApp`, `stopApp`, `killApp`, `clearAppState`, `clearKeychain`, `backPress`, `hideKeyboard`, `inputText`, `eraseText`, `pressKey`, `openLink`, `setLocation`, `setOrientation`, `swipe` (all overloads), `scrollVertical`, `performTap` (covers tap/longPress/tapRepeat). Read-only calls (`viewHierarchy`, `takeScreenshot`, `deviceInfo`) must NOT mark.

- [ ] **Step 2: Teach waitForAppToSettle about skip + cache**

```kotlin
    fun waitForAppToSettle(
        initialHierarchy: ViewHierarchy? = null,
        appId: String? = null,
        waitToSettleTimeoutMs: Int? = null,
    ): ViewHierarchy? {
        // Transition class NONE resolves to a 0 budget: skip the driver round-trip entirely.
        if (waitToSettleTimeoutMs == 0) {
            return initialHierarchy ?: settledHierarchy
        }
        val result = driver.waitForAppToSettle(initialHierarchy, appId, waitToSettleTimeoutMs)
        settledEpoch = actionEpoch
        if (result != null) {
            settledHierarchy = result
        }
        return result
    }
```

(Preserve the exact existing return-type nullability; iOS returns null on its fast path, which is why the cache only updates on non-null.)

- [ ] **Step 3: Skip the pre-tap settle when the cache is fresh**

In `tapOnElement` (Maestro.kt:188 area), replace:

```kotlin
        val hierarchyBeforeTap = waitForAppToSettle(initialHierarchy, appId, waitToSettleTimeoutMs) ?: initialHierarchy
```

with:

```kotlin
        val hierarchyBeforeTap = if (
            speedSettings.skipPreTapSettle &&
            settledEpoch == actionEpoch &&
            (initialHierarchy ?: settledHierarchy) != null
        ) {
            LOGGER.info("Skipping pre-tap settle: no action since last settled hierarchy")
            initialHierarchy ?: settledHierarchy
        } else {
            waitForAppToSettle(initialHierarchy, appId, waitToSettleTimeoutMs) ?: initialHierarchy
        }
```

(Keep the surrounding null handling exactly as it is today — the expression preserves the same type as the original.)

- [ ] **Step 4: Write the failing test**

Driver-level fakery for full `Maestro` construction is heavy; test the epoch logic through a fake `Driver` where `waitForAppToSettle` counts invocations, `tap` is a no-op, and `contentDescriptor` returns a fixed tree. Stub all other members with `TODO()`.

```kotlin
package maestro

import com.google.common.truth.Truth.assertThat
import maestro.settle.SpeedSettings
import org.junit.jupiter.api.Test

class QuiescenceEpochTest {

    // Construct Maestro with a fake Driver (see maestro-client/src/test fakes for
    // existing patterns; Maestro(driver) is the entry point — check the actual
    // constructor/factory in Maestro.kt companion and use it here).

    @Test
    fun `waitForAppToSettle with zero timeout does not hit the driver`() {
        val driver = FakeSettleDriver()
        val maestro = Maestro.ios(driver) // use the real factory; any platform works with a fake driver

        maestro.waitForAppToSettle(initialHierarchy = null, appId = null, waitToSettleTimeoutMs = 0)

        assertThat(driver.settleCalls).isEqualTo(0)
    }

    @Test
    fun `settle then settle again without action skips driver when skipPreTapSettle is on`() {
        val driver = FakeSettleDriver()
        val maestro = Maestro.ios(driver)
        maestro.speedSettings = SpeedSettings.DEFAULT.copy(skipPreTapSettle = true)

        maestro.waitForAppToSettle() // primes settledEpoch/settledHierarchy
        val callsAfterPrime = driver.settleCalls

        // tapOnElement's pre-settle branch: fresh epoch -> no additional settle call.
        // Exercise via the public API: find + tap on the fake tree's element.
        // (See FakeSettleDriver.contentDescriptor for the fixed "OK" button node.)
        val result = maestro.findElementWithTimeout(100L, Filters.textMatches("OK".toRegex()))
        requireNotNull(result)
        maestro.tap(result.element, result.hierarchy, retryIfNoChange = false, waitUntilVisible = false)

        // Pre-tap settle skipped (fresh) — only the post-tap settle may add calls.
        assertThat(driver.settleCalls).isAtMost(callsAfterPrime + 1)
    }
}
```

Write `FakeSettleDriver` in the same file: `settleCalls` counter incremented in `waitForAppToSettle` (returning a minimal `ViewHierarchy` built from a `TreeNode` with an "OK" text node and non-zero bounds), `tap`/`longPress` no-ops, `contentDescriptor` returning the same tree, everything else `TODO()`. Match `Filters.textMatches` and `Maestro.ios(...)`/`tap(...)` signatures to the real code — read them first; the *assertions* above are the contract.

- [ ] **Step 5: Run tests**

Run: `./gradlew :maestro-client:test --tests 'maestro.QuiescenceEpochTest'`
Expected: PASS

Run: `./gradlew :maestro-client:test`
Expected: PASS (no regressions)

- [ ] **Step 6: Commit**

```bash
git add maestro-client/src/main/java/maestro/Maestro.kt maestro-client/src/test/java/maestro/QuiescenceEpochTest.kt
git commit -m "feat(settle): quiescence epoch, settle skip, and pre-tap cache"
```

---

### Task 6: Transition classes + settle-budget resolution in Orchestra

**Files:**
- Create: `maestro-orchestra/src/main/java/maestro/orchestra/TransitionDefaults.kt`
- Modify: `maestro-orchestra/src/main/java/maestro/orchestra/Orchestra.kt` (every call site that forwards `command.waitToSettleTimeoutMs`)
- Test: `maestro-orchestra/src/test/java/maestro/orchestra/TransitionDefaultsTest.kt`

- [ ] **Step 1: Write the failing test**

First run `grep -n "^data class\|^class" maestro-orchestra-models/src/main/java/maestro/orchestra/Commands.kt` to get exact command class names, then:

```kotlin
package maestro.orchestra

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class TransitionDefaultsTest {

    @Test
    fun `text entry commands are NONE`() {
        assertThat(TransitionDefaults.forCommand(InputTextCommand(text = "hi")))
            .isEqualTo(TransitionClass.NONE)
    }

    @Test
    fun `app lifecycle and navigation commands are SCREEN`() {
        assertThat(TransitionDefaults.forCommand(LaunchAppCommand(appId = "com.x")))
            .isEqualTo(TransitionClass.SCREEN)
        assertThat(TransitionDefaults.forCommand(BackPressCommand()))
            .isEqualTo(TransitionClass.SCREEN)
    }

    @Test
    fun `taps and swipes are MINOR`() {
        assertThat(TransitionDefaults.forCommand(TapOnElementCommand(selector = ElementSelector(textRegex = "OK"))))
            .isEqualTo(TransitionClass.MINOR)
        assertThat(TransitionDefaults.forCommand(SwipeCommand(direction = SwipeDirection.UP)))
            .isEqualTo(TransitionClass.MINOR)
    }
}
```

(Adapt constructor arguments to the real data classes — e.g. `InputTextCommand`'s parameter name, `LaunchAppCommand`'s required fields. The mapping being tested is the contract.)

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :maestro-orchestra:test --tests 'maestro.orchestra.TransitionDefaultsTest'`
Expected: FAIL (unresolved `TransitionDefaults`)

- [ ] **Step 3: Write the implementation**

```kotlin
package maestro.orchestra

enum class TransitionClass { SCREEN, MINOR, NONE }

/**
 * Default transition class per command type. NONE commands skip post-action settling
 * entirely (budget 0); MINOR use the minor settle budget; SCREEN use the screen budget.
 * Explicit per-command waitToSettleTimeoutMs always wins over these defaults.
 */
object TransitionDefaults {

    fun forCommand(command: Command): TransitionClass = when (command) {
        is InputTextCommand,
        is InputRandomCommand,
        is EraseTextCommand,
        -> TransitionClass.NONE

        is LaunchAppCommand,
        is StopAppCommand,
        is KillAppCommand,
        is OpenLinkCommand,
        is BackPressCommand,
        -> TransitionClass.SCREEN

        else -> TransitionClass.MINOR
    }
}
```

(Adjust the class list to the exact names in `Commands.kt` — e.g. if random input is `InputRandomCommand` vs `InputRandomTextCommand`.)

- [ ] **Step 4: Add the resolver to Orchestra and route every settle timeout through it**

Add near the other private fields of `Orchestra` (speedProfile/speedSettings fields arrive in Task 7 — for this task declare them):

```kotlin
    private var speedProfile: SpeedProfile = SpeedProfile.DEFAULT
    private var speedSettings: SpeedSettings = SpeedSettings.DEFAULT

    /**
     * Explicit per-command timeout wins; the DEFAULT profile preserves driver-default
     * behavior (null); other profiles inject transition-class budgets.
     */
    private fun resolveSettleTimeout(command: Command, explicit: Int?): Int? {
        if (explicit != null) return explicit
        if (speedProfile == SpeedProfile.DEFAULT) return null
        return when (TransitionDefaults.forCommand(command)) {
            TransitionClass.NONE -> 0
            TransitionClass.MINOR -> speedSettings.minorSettleBudgetMs
            TransitionClass.SCREEN -> speedSettings.screenSettleBudgetMs
        }
    }
```

Then `grep -n "waitToSettleTimeoutMs = command.waitToSettleTimeoutMs" maestro-orchestra/src/main/java/maestro/orchestra/Orchestra.kt` and change **every** occurrence to:

```kotlin
                waitToSettleTimeoutMs = resolveSettleTimeout(command, command.waitToSettleTimeoutMs),
```

(Import `maestro.settle.SpeedSettings`.)

- [ ] **Step 5: Run tests**

Run: `./gradlew :maestro-orchestra:test`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add maestro-orchestra/src/main/java/maestro/orchestra/TransitionDefaults.kt maestro-orchestra/src/main/java/maestro/orchestra/Orchestra.kt maestro-orchestra/src/test/java/maestro/orchestra/TransitionDefaultsTest.kt
git commit -m "feat(settle): transition-class settle budgets in Orchestra"
```

---

### Task 7: Profile resolution at flow start + profile-scoped lookup timeouts

**Files:**
- Modify: `maestro-orchestra/src/main/java/maestro/orchestra/Orchestra.kt`
- Test: extend `maestro-orchestra/src/test/java/maestro/orchestra/SpeedProfileTest.kt` — resolution unit tests already cover precedence; this task's verification is compile + full suite + one integration assertion below.

- [ ] **Step 1: Locate the config application point**

Run: `grep -n "ApplyConfigurationCommand" maestro-orchestra/src/main/java/maestro/orchestra/Orchestra.kt` — find where the flow's `ApplyConfigurationCommand` is executed (config lands there at flow start).

- [ ] **Step 2: Apply the profile when config is applied**

Add and invoke from the ApplyConfigurationCommand handler:

```kotlin
    private fun applySpeedProfile(config: MaestroConfig?) {
        speedProfile = SpeedProfile.resolve(config?.ext)
        speedSettings = speedProfile.settings
        maestro.speedSettings = speedSettings
        if (speedProfile != SpeedProfile.DEFAULT) {
            logger.info("Speed profile active: $speedProfile ($speedSettings)")
        }
    }
```

(Match the actual logger field name in Orchestra; if none exists at class level, use the existing logging pattern found in the file.)

- [ ] **Step 3: Make lookup timeouts profile-scoped**

Add:

```kotlin
    private val effectiveLookupTimeoutMs: Long
        get() = if (speedProfile == SpeedProfile.DEFAULT) lookupTimeoutMs else speedSettings.lookupTimeoutMs

    private val effectiveOptionalLookupTimeoutMs: Long
        get() = if (speedProfile == SpeedProfile.DEFAULT) optionalLookupTimeoutMs else speedSettings.optionalLookupTimeoutMs
```

Then `grep -n "lookupTimeoutMs\b\|optionalLookupTimeoutMs\b" maestro-orchestra/src/main/java/maestro/orchestra/Orchestra.kt` and replace every *usage* (not the constructor parameters or the two new properties) with the `effective*` variants — the known usage sites are lines 429, 1272, 1693-1694 plus any the grep reveals.

Precedence note (document in a comment on the properties): explicit constructor arguments still act as the DEFAULT-profile baseline (MCP/CLI callers may pass their own); a non-default profile from flow config or env overrides them.

- [ ] **Step 4: Run the full orchestra suite**

Run: `./gradlew :maestro-orchestra:test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add maestro-orchestra/src/main/java/maestro/orchestra/Orchestra.kt
git commit -m "feat(settle): resolve speed profile at flow start, scope lookup timeouts"
```

---

### Task 8: Animation control (Driver interface + AndroidDriver + Orchestra lifecycle)

**Files:**
- Modify: `maestro-client/src/main/java/maestro/Driver.kt`
- Modify: `maestro-client/src/main/java/maestro/drivers/AndroidDriver.kt`
- Modify: `maestro-client/src/main/java/maestro/Maestro.kt`
- Modify: `maestro-orchestra/src/main/java/maestro/orchestra/Orchestra.kt`
- Test: manual verification on device (adb settings are not unit-testable without a device; the AndroidDriver `shell` helper is already exercised by existing integration paths)

- [ ] **Step 1: Add a default no-op to the Driver interface**

```kotlin
    /** Disable or restore OS-level animations for the session. No-op where unsupported. */
    fun setAnimations(enabled: Boolean) {}
```

- [ ] **Step 2: Implement in AndroidDriver**

```kotlin
    private var savedAnimationScales: Triple<String, String, String>? = null

    override fun setAnimations(enabled: Boolean) {
        metrics.measured("operation", mapOf("command" to "setAnimations", "enabled" to enabled.toString())) {
            if (!enabled) {
                if (savedAnimationScales == null) {
                    savedAnimationScales = Triple(
                        normalizeScale(shell("settings get global window_animation_scale")),
                        normalizeScale(shell("settings get global transition_animation_scale")),
                        normalizeScale(shell("settings get global animator_duration_scale")),
                    )
                }
                shell("settings put global window_animation_scale 0")
                shell("settings put global transition_animation_scale 0")
                shell("settings put global animator_duration_scale 0")
            } else {
                savedAnimationScales?.let { (window, transition, animator) ->
                    shell("settings put global window_animation_scale $window")
                    shell("settings put global transition_animation_scale $transition")
                    shell("settings put global animator_duration_scale $animator")
                }
                savedAnimationScales = null
            }
        }
    }

    private fun normalizeScale(raw: String): String {
        val value = raw.trim()
        return if (value.isBlank() || value == "null") "1" else value
    }
```

(Use the existing `shell(...)` helper in AndroidDriver — see the `http_proxy` implementations around line 738 for the pattern.)

- [ ] **Step 3: Safety-net restore in AndroidDriver.close()**

Find `override fun close()` in AndroidDriver and add as the first statement:

```kotlin
        if (savedAnimationScales != null) {
            runCatching { setAnimations(true) }
        }
```

- [ ] **Step 4: Delegate from Maestro**

```kotlin
    fun setAnimations(enabled: Boolean) {
        LOGGER.info("Setting animations enabled=$enabled")
        driver.setAnimations(enabled)
    }
```

- [ ] **Step 5: Drive from Orchestra's flow lifecycle**

In `applySpeedProfile` (Task 7) add after `maestro.speedSettings = speedSettings`:

```kotlin
        if (speedSettings.disableAnimations && !animationsDisabled) {
            maestro.setAnimations(false)
            animationsDisabled = true
        }
```

with the field `private var animationsDisabled = false`, and in the flow-completion path (find the `finally`/completion block that runs after `executeCommands` — grep `onFlowComplete` handling in Orchestra.kt) add:

```kotlin
        if (animationsDisabled) {
            runCatching { maestro.setAnimations(true) }
            animationsDisabled = false
        }
```

- [ ] **Step 6: Compile + manual device verification**

Run: `./gradlew :maestro-client:compileKotlin :maestro-orchestra:compileKotlin`
Expected: BUILD SUCCESSFUL

With the Android device connected, run any flow with `speedProfile: fast` in its config and verify during the run: `adb shell settings get global animator_duration_scale` prints `0`, and after the run it prints the prior value.

- [ ] **Step 7: Commit**

```bash
git add maestro-client/src/main/java/maestro/Driver.kt maestro-client/src/main/java/maestro/drivers/AndroidDriver.kt maestro-client/src/main/java/maestro/Maestro.kt maestro-orchestra/src/main/java/maestro/orchestra/Orchestra.kt
git commit -m "feat(settle): session-scoped Android animation disabling"
```

---

### Task 9: Delete hardcoded Android sleeps

**Files:**
- Modify: `maestro-client/src/main/java/maestro/drivers/AndroidDriver.kt:333` (keyPress), `:526` (backPress), `:533` (hideKeyboard)

- [ ] **Step 1: Remove the three 300ms sleeps**

Delete `Thread.sleep(300)` from `keyPress`, `backPress`, and `hideKeyboard`. `hideKeyboard` keeps its `waitForAppToSettle(null, null)` call — settling is now the only wait primitive on these paths.

Retained deliberately (document with a one-line comment at each if none exists): the screen-recording flush sleep (`AndroidDriver.kt:571`, 3000ms — off the hot path, protects file pull) and the install/launch retry loops (`:137`, `:168` — startup, not per-step).

- [ ] **Step 2: Verify no hot-path sleeps remain**

Run: `grep -n "Thread.sleep" maestro-client/src/main/java/maestro/drivers/AndroidDriver.kt`
Expected output: only the retained lines from Step 1 (screenrecord + startup loops).

- [ ] **Step 3: Compile and run module tests**

Run: `./gradlew :maestro-client:test`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add maestro-client/src/main/java/maestro/drivers/AndroidDriver.kt
git commit -m "perf(android): remove hardcoded 300ms sleeps from key/back/hideKeyboard"
```

---

### Task 10: Per-command duration in CommandMetadata

**Files:**
- Modify: `maestro-orchestra/src/main/java/maestro/orchestra/Orchestra.kt` (CommandMetadata at ~line 2166 and the executeCommand body)

- [ ] **Step 1: Add the field**

In the `CommandMetadata` data class add:

```kotlin
        val durationMs: Long? = null,
```

- [ ] **Step 2: Measure in executeCommand**

Find the method that executes a single command and already calls `onCommandMetadataUpdate` (grep `onCommandMetadataUpdate` for the update pattern used). Wrap the execution:

```kotlin
        val commandStartMs = System.currentTimeMillis()
        // ... existing execution ...
        // in the completion path (success AND failure), before notifying:
        updateMetadata(rawCommand, getMetadata(rawCommand).copy(durationMs = System.currentTimeMillis() - commandStartMs))
```

(Match the real metadata get/update helper names revealed by the grep — the pattern `getMetadata(...)`/`updateMetadata(...)` appears throughout Orchestra.kt.)

- [ ] **Step 3: Compile + suite**

Run: `./gradlew :maestro-orchestra:test`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add maestro-orchestra/src/main/java/maestro/orchestra/Orchestra.kt
git commit -m "feat(orchestra): record per-command durationMs in CommandMetadata"
```

---

### Task 11: Documentation

**Files:**
- Create: `docs/speed-profiles.md`
- Modify: `README.md` (one line in the fork-features section pointing at the doc)

- [ ] **Step 1: Write docs/speed-profiles.md**

Prescriptive content (no changelog framing) covering: what `speedProfile` does; the three profiles and their settings table (mirror the spec's Configuration table, Phase 1 column values); how to set it (flow config `speedProfile: fast`, env `MAESTRO_SPEED_PROFILE=fast`); per-command `waitToSettleTimeoutMs` override semantics including `0` = skip settle; Android animation-scale behavior (set to 0 during the session, restored after, safety-net on driver close); note that `ferrari` currently equals `fast` and gains event-driven quiescence when the copilot ships.

- [ ] **Step 2: Commit**

```bash
git add docs/speed-profiles.md README.md
git commit -m "docs: document speed profiles"
```

---

### Task 12: Full verification pass

- [ ] **Step 1: Run all touched module suites**

Run: `./gradlew :maestro-utils:test :maestro-client:test :maestro-orchestra:test`
Expected: PASS across all three modules.

- [ ] **Step 2: End-to-end smoke on real targets**

With the iOS simulator booted and the Android device connected, run an existing e2e flow twice — once unchanged (default profile: behavior must be identical to pre-change) and once with `speedProfile: fast` added to the flow config. Record wall-clock for both. Expected: default run passes unchanged; fast run passes and is measurably faster (settle-bound flows typically 2x+).

- [ ] **Step 3: Commit any fixups and report timings**

Report the two wall-clock numbers in the task summary — they are the Phase 1 acceptance evidence.
