package maestro.cli.daemon

import com.google.common.truth.Truth.assertThat
import maestro.AutomationQueryRequest
import maestro.AutomationSelector
import maestro.AutomationSnapshotMode
import maestro.AutomationSnapshotRequest
import org.junit.jupiter.api.Test

internal class DaemonSemanticAutomationTest {

    private val semanticState = DaemonBridgeRegistry.SemanticState(
        appId = "technology.ryans.thrivify",
        route = "Chats",
        userId = "user-123",
        firebaseUid = "firebase-456",
        metroConnected = true,
        conversationIds = listOf("conversation-1"),
        conversationListReady = true,
        primaryAction = "continue",
        lastCommand = "openRoute",
        lastCommandStatus = "succeeded",
        stateVersion = 7L,
    )

    @Test
    fun `resolveSnapshot uses semantic bridge in auto mode when state is available`() {
        val resolution = DaemonSemanticAutomation.resolveSnapshot(
            state = semanticState,
            request = AutomationSnapshotRequest(
                mode = AutomationSnapshotMode.MINIMAL,
                fields = linkedSetOf("id", "text", "enabled"),
            ),
            sourcePreference = "auto",
        )

        assertThat(resolution.fallbackReason).isNull()
        assertThat(resolution.snapshot).isNotNull()
        assertThat(resolution.snapshot!!.source).isEqualTo(DaemonSemanticAutomation.SOURCE_SEMANTIC)
        assertThat(resolution.snapshot!!.nodeCount).isGreaterThan(0)
    }

    @Test
    fun `resolveQuery uses semantic bridge for automation bridge selectors`() {
        val resolution = DaemonSemanticAutomation.resolveQuery(
            state = semanticState,
            request = AutomationQueryRequest(
                selectors = listOf(
                    AutomationSelector(
                        id = "automation-bridge-route-chats",
                        useFuzzyMatching = false,
                    ),
                ),
            ),
            sourcePreference = "auto",
        )

        assertThat(resolution.fallbackReason).isNull()
        assertThat(resolution.result).isNotNull()
        assertThat(resolution.result!!.source).isEqualTo(DaemonSemanticAutomation.SOURCE_SEMANTIC)
        assertThat(resolution.result!!.matches.single().matchCount).isEqualTo(1)
    }

    @Test
    fun `resolveQuery falls back for unsupported selectors in auto mode`() {
        val resolution = DaemonSemanticAutomation.resolveQuery(
            state = semanticState,
            request = AutomationQueryRequest(
                selectors = listOf(
                    AutomationSelector(
                        id = "home-tab-chats",
                        useFuzzyMatching = false,
                    ),
                ),
            ),
            sourcePreference = "auto",
        )

        assertThat(resolution.result).isNull()
        assertThat(resolution.fallbackReason).isEqualTo("semantic_unsupported_selector")
    }

    @Test
    fun `resolveQuery falls back when bridge state is unavailable`() {
        val resolution = DaemonSemanticAutomation.resolveQuery(
            state = null,
            request = AutomationQueryRequest(
                selectors = listOf(
                    AutomationSelector(
                        id = "automation-bridge-ready",
                        useFuzzyMatching = false,
                    ),
                ),
            ),
            sourcePreference = "auto",
        )

        assertThat(resolution.result).isNull()
        assertThat(resolution.fallbackReason).isEqualTo("bridge_unavailable")
    }
}
