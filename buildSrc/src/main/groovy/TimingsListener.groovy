import org.gradle.BuildListener
import org.gradle.BuildResult
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.execution.TaskExecutionListener
import org.gradle.api.initialization.Settings
import org.gradle.api.invocation.Gradle
import org.gradle.api.tasks.TaskState

import java.util.concurrent.TimeUnit

// A listener that will allow us to monitor task timings and, ultimately, APK size
class TimingsListener implements TaskExecutionListener, BuildListener {
    private long startTime
    private timings = [:]
    private Project refProject

    TimingsListener(Project _refProject) {
        refProject = _refProject
    }

    @Override
    void beforeExecute(Task task) {
        startTime = System.nanoTime()
    }

    @Override
    void afterExecute(Task task, TaskState taskState) {
        def ms = TimeUnit.MILLISECONDS.convert(System.nanoTime() - startTime, TimeUnit.NANOSECONDS)
        timings.put(task.name, ms)
    }

    @Override
    void buildFinished(BuildResult result) {

        // Grab the Sumologic endpoint from Bitrise
        def sumologicEndpoint = System.getenv("SUMOLOGIC_BUILD_ENDPOINT")

        // Let's abort early if (1) the build failed, or (2) we're not on bitrise
        if(result.failure != null) {
            println("Build report logic aborting due to failed build")
            return
        }

        if(sumologicEndpoint == null || sumologicEndpoint.isEmpty()) {
            println("Build report logic aborting because we're not on bitrise")
            return
        }

        // Grab the gradle tasks passed on the command line for the job
        def startTaskNames = result.gradle.startParameter.taskNames.join(",")

        // Sort the timings in descending time order, compute our top 10
        timings = timings.sort { -it.value }
        def top10 = timings.take(10).entrySet()

        // Compute the cumulative time for all build tasks
        def cumulativeTime = 0
        for(entry in timings) {
            cumulativeTime += entry.value
        }

        // Figure out our build type
        def buildType = "debug"
        if(startTaskNames.contains("Release")) {
            buildType = "release"
        }

        // Figure out our build flavor
        def buildFlavor = "qa"
        if(startTaskNames.contains("Dev")) {
            buildFlavor = "dev"
        }
        else if(startTaskNames.contains("Prod")) {
            buildFlavor = "prod"
        }

        // Locate the apk
        println("projectName = ${refProject.name}")
        def buildDir = refProject.buildDir
        def file = new File("$buildDir/outputs/apk/$buildFlavor/$buildType/${refProject.name}-$buildFlavor-${buildType}.apk")
        println("file name=${file.path} length=${file.length()}")

        // Construct the JSON payload for our "buildComplete" event
        def bitriseWorkflow = System.getenv("BITRISE_TRIGGERED_WORKFLOW_ID")
        def bitriseApp = System.getenv("BITRISE_APP_TITLE")
        def bitriseBranch = System.getenv("BITRISE_GIT_BRANCH")
        def bitriseBuildNumber = System.getenv("BITRISE_BUILD_NUMBER")
        def fileSizeInMB = file.length() == 0 ? 0 : (file.length() / (1024.0 * 1024.0)).round(3)

        // Instruct the builder as to how to build our payload
        def payloadBuilder = new groovy.json.JsonBuilder()
        payloadBuilder event: "buildComplete",
            buildTime: cumulativeTime,
            gradleTasks: startTaskNames,
            apkFilePath: file.path,
            apkSize: fileSizeInMB,
            bitriseWorkflow: bitriseWorkflow,
            bitriseApp: bitriseApp,
            bitriseBranch: bitriseBranch,
            bitriseBuildNumber: bitriseBuildNumber,
            topTasks: top10

        // Create the event payload.  Change key/value in top 10 tasks to task/ms.
        def payload = payloadBuilder.toString().replaceAll("\"key\"", "\"task\"").replaceAll("\"value\"", "\"ms\"")

        println("event payload: $payload")

        // Let's issue our curl command to emit our data
        refProject.exec {
            executable "curl"
            args "-X", "POST", "-H", "Content-Type: application/json", "-d", payload, sumologicEndpoint
        }

    }

    @Override
    void buildStarted(Gradle gradle) {}

    @Override
    void projectsEvaluated(Gradle gradle) {}

    @Override
    void projectsLoaded(Gradle gradle) {}

    @Override
    void settingsEvaluated(Settings settings) {}
}
