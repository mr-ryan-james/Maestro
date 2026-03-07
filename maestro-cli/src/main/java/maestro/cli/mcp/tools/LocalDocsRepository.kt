package maestro.cli.mcp.tools

import kotlin.math.max

internal object LocalDocsRepository {

    private data class DocSection(
        val title: String,
        val body: String,
    ) {
        val combined = "$title\n$body"
    }

    private val cheatSheetText = """
        # Maestro Cheat Sheet

        ## Flow Structure
        - Optional headers come before `---`
        - Common headers: `appId`, `name`, `env`, `tags`, `onFlowStart`, `onFlowComplete`
        - Commands are YAML list items after `---`
        - Reuse flows with `runFlow`

        ## Core Selectors
        - Prefer `id:` / React Native `testID`
        - Use `text:` for visible text
        - Add `index:` when multiple matches exist
        - Selector modifiers: `enabled`, `checked`, `focused`, `selected`

        ## Common Commands
        - `launchApp`
        - `tapOn`
        - `inputText`
        - `eraseText`
        - `hideKeyboard`
        - `scroll`
        - `scrollUntilVisible`
        - `swipe`
        - `back`
        - `pressKey`
        - `takeScreenshot`
        - `copyTextFrom`
        - `openLink`
        - `clearState`
        - `setLocation`
        - `assertVisible`
        - `assertNotVisible`
        - `waitForAnimationToEnd`
        - `runFlow`
        - `runScript`
        - `repeat`

        ## Examples
        ```yaml
        appId: com.example.app
        ---
        - launchApp:
            clearState: true
        - tapOn:
            id: login-button
        - inputText: "hello@example.com"
        - hideKeyboard
        - tapOn: Continue
        - assertVisible:
            id: home-screen
        ```

        ```yaml
        - scrollUntilVisible:
            element:
              id: save-button
            direction: DOWN
        - tapOn:
            id: save-button
        ```

        ## Best Practices
        - Prefer stable `id` selectors over visible text.
        - Inspect the view hierarchy before guessing selectors.
        - Use `scrollUntilVisible` instead of repeated coordinate swipes.
        - Use `waitForAnimationToEnd` around transitions that are visibly animated.
        - Keep flows small and compose with `runFlow`.
        - Use `env` for test data rather than hard-coded literals.
    """.trimIndent()

    private val docs = listOf(
        DocSection(
            title = "Flow syntax and structure",
            body = """
                Maestro flows are YAML documents. Optional headers appear before `---` and commands come after it.
                Headers commonly include `appId`, `name`, and `env`.
                Commands can be nested with `runFlow`, guarded with `when`, and parameterized with environment variables.
                Use `check_flow_syntax` to validate YAML before execution when you are unsure.
            """.trimIndent(),
        ),
        DocSection(
            title = "Selectors and hierarchy inspection",
            body = """
                Prefer `id` selectors first, especially React Native `testID`.
                If an element is not obvious, use `inspect_view_hierarchy` and search the CSV for `resource-id`, `text`, `accessibility`, and bounds.
                `tap_on`, `assert_visible`, `assert_not_visible`, and `copy_text_from` all support selector modifiers like `enabled`, `focused`, `selected`, and `checked`.
            """.trimIndent(),
        ),
        DocSection(
            title = "Text input and keyboard handling",
            body = """
                Typical text flow is `tapOn` -> `eraseText` -> `inputText` -> `hideKeyboard`.
                If the keyboard hides important controls, use `hide_keyboard` or `scroll_until_visible` instead of coordinate taps.
                `press_key` is useful for Enter, Tab, Backspace, Home, and remote navigation keys.
            """.trimIndent(),
        ),
        DocSection(
            title = "Scrolling and navigation",
            body = """
                Use `scroll` for generic vertical movement, `swipe` for directional gestures, and `scroll_until_visible` for target-driven discovery.
                `back` presses the device back action. `open_link` can trigger deep links or browser navigation.
                `wait_for_animation_to_end` is useful after route transitions or bottom sheets.
            """.trimIndent(),
        ),
        DocSection(
            title = "State, screenshots, and diagnostics",
            body = """
                `clear_state` resets app data for a specific app id.
                `take_screenshot` captures the current screen.
                `run_flow` is best for quick ad hoc command sequences.
                `run_flow_files` runs one or more full YAML files resolved relative to the provided working directory.
            """.trimIndent(),
        ),
        DocSection(
            title = "Recipes for robust flows",
            body = """
                Break large flows into subflows and compose them with `runFlow`.
                Use environment variables for reusable values like phones, codes, and ids.
                Assert the next screen with a stable screen anchor rather than a random child widget.
                Prefer selected-state markers after tapping radio-like controls so automation can verify that the interaction actually stuck.
            """.trimIndent(),
        ),
    )

    fun cheatSheet(): String = cheatSheetText

    fun search(question: String): String {
        val normalizedQuestion = question.trim()
        if (normalizedQuestion.isBlank()) {
            return cheatSheetText
        }

        val tokens = tokenize(normalizedQuestion)
        val ranked = docs
            .map { section -> section to score(section, tokens) }
            .sortedByDescending { it.second }
            .take(3)
            .filter { it.second > 0 }

        if (ranked.isEmpty()) {
            return buildString {
                appendLine("No exact local docs match was found. Returning the local Maestro cheat sheet instead.\n")
                append(cheatSheetText)
            }
        }

        return buildString {
            appendLine("Local Maestro docs results for: $normalizedQuestion")
            appendLine()
            ranked.forEach { (section, _) ->
                appendLine("## ${section.title}")
                appendLine(section.body.trim())
                appendLine()
            }
            appendLine("---")
            appendLine("Cheat sheet summary:")
            append(cheatSheetText)
        }.trim()
    }

    private fun score(section: DocSection, tokens: Set<String>): Int {
        val haystack = section.combined.lowercase()
        return tokens.sumOf { token ->
            when {
                haystack.contains(token) -> max(1, token.length / 3)
                else -> 0
            }
        }
    }

    private fun tokenize(input: String): Set<String> {
        return input
            .lowercase()
            .split(Regex("[^a-z0-9_:+.-]+"))
            .map { it.trim() }
            .filter { it.length >= 2 }
            .toSet()
    }
}
