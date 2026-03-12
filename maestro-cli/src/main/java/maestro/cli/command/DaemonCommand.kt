package maestro.cli.command

import java.io.File
import java.util.concurrent.Callable
import maestro.cli.daemon.runMaestroDaemonServer
import maestro.cli.util.WorkingDirectory
import picocli.CommandLine

@CommandLine.Command(
    name = "daemon",
    mixinStandardHelpOptions = true,
    description = [
        "Starts the long-lived Maestro daemon for hot-session automation, local API access, and app-bridge connections."
    ],
)
class DaemonCommand : Callable<Int> {

    @CommandLine.Option(
        names = ["--working-dir"],
        description = ["Base working directory for resolving files and project-relative flow paths"],
    )
    private var workingDir: File? = null

    @CommandLine.Option(
        names = ["--port"],
        description = ["Loopback port for the daemon HTTP/WebSocket API (default: 7285)"],
    )
    private var port: Int? = null

    override fun call(): Int {
        if (workingDir != null) {
            WorkingDirectory.baseDir = workingDir!!.absoluteFile
        }
        runMaestroDaemonServer(port ?: 7285)
        return 0
    }
}
