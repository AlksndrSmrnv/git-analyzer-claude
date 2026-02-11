package analyzer

import java.util.concurrent.CompletableFuture

data class CommitInfo(
    val hash: String,
    val authorEmail: String,
    val date: String
)

class GitClient(private val repoPath: String) {

    fun validateRepo(): Boolean {
        return try {
            val output = runGit("rev-parse", "--is-inside-work-tree")
            output.trim() == "true"
        } catch (e: Exception) {
            false
        }
    }

    fun getCommits(sinceDays: Int?): List<CommitInfo> {
        val args = mutableListOf("log", "--format=%H %aE %aI", "--no-merges")
        if (sinceDays != null) {
            args.add("--since=$sinceDays days ago")
        }
        args.add("--")
        args.add("*.kt")

        val output = runGit(*args.toTypedArray())
        if (output.isBlank()) return emptyList()

        return output.lines().mapNotNull { line ->
            val parts = line.split(" ", limit = 3)
            if (parts.size == 3) {
                CommitInfo(hash = parts[0], authorEmail = parts[1], date = parts[2])
            } else null
        }
    }

    fun getDiffForCommit(commitHash: String): String {
        val isRoot = isRootCommit(commitHash)
        return if (isRoot) {
            runGit("diff-tree", "--root", "-p", commitHash, "--", "*.kt")
        } else {
            runGit("diff-tree", "-p", commitHash, "--", "*.kt")
        }
    }

    private fun isRootCommit(commitHash: String): Boolean {
        val output = runGit("rev-list", "--parents", "-1", commitHash)
        return output.trim().split(" ").size == 1
    }

    private fun runGit(vararg args: String): String {
        val command = listOf("git", "-C", repoPath) + args.toList()
        val process = ProcessBuilder(command)
            .redirectErrorStream(false)
            .start()
        try {
            val stderrFuture = CompletableFuture.supplyAsync {
                process.errorStream.bufferedReader().use { it.readText() }
            }
            val stdout = process.inputStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()
            val stderr = stderrFuture.get()
            if (exitCode != 0 && stderr.isNotBlank()) {
                System.err.println("Git error: $stderr")
            }
            return stdout.trim()
        } finally {
            process.destroyForcibly()
        }
    }
}
