package analyzer

fun main() {
    val repoPath = AnalyzerConfig.REPO_PATH
    val days = AnalyzerConfig.DAYS

    val periodLabel = if (days != null) "Last $days days" else "All time"

    val gitClient = GitClient(repoPath)

    if (!gitClient.validateRepo()) {
        System.err.println("Error: '$repoPath' is not a valid git repository.")
        return
    }

    val commits = gitClient.getCommits(sinceDays = days)
    if (commits.isEmpty()) {
        println("No commits found for the specified period.")
        return
    }
    println("Found ${commits.size} commits to analyze...")

    val parser = TestParser()
    val testsByAuthor = mutableMapOf<String, MutableList<NewTestInfo>>()

    for ((index, commit) in commits.withIndex()) {
        if ((index + 1) % 50 == 0) {
            println("  Processing commit ${index + 1}/${commits.size}...")
        }

        val diff = gitClient.getDiffForCommit(commit.hash)
        if (diff.isBlank()) continue

        val newTests = parser.findNewTests(diff)
        if (newTests.isNotEmpty()) {
            testsByAuthor.getOrPut(commit.authorEmail) { mutableListOf() }
                .addAll(newTests)
        }
    }

    println()
    val printer = ReportPrinter()
    printer.printReport(testsByAuthor, periodLabel, repoPath)
}
