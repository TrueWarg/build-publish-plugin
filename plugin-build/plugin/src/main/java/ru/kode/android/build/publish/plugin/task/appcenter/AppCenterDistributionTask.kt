package ru.kode.android.build.publish.plugin.task.appcenter

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.plugins.BasePlugin
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.options.Option
import org.gradle.workers.WorkQueue
import org.gradle.workers.WorkerExecutor
import ru.kode.android.build.publish.plugin.enity.mapper.fromJson
import ru.kode.android.build.publish.plugin.extension.config.MAX_REQUEST_COUNT
import ru.kode.android.build.publish.plugin.extension.config.MAX_REQUEST_DELAY_MS
import ru.kode.android.build.publish.plugin.task.appcenter.work.AppCenterUploadWork
import ru.kode.android.build.publish.plugin.util.capitalized
import javax.inject.Inject

abstract class AppCenterDistributionTask @Inject constructor(
    private val workerExecutor: WorkerExecutor,
) : DefaultTask() {

    init {
        description = "Task to send apk to AppCenter"
        group = BasePlugin.BUILD_GROUP
    }

    @get:InputFile
    @get:Option(
        option = "changelogFile",
        description = "File with saved changelog"
    )
    abstract val changelogFile: RegularFileProperty

    @get:InputFile
    @get:Option(
        option = "buildVariantOutputFile",
        description = "Artifact output file (absolute path is expected)"
    )
    abstract val buildVariantOutputFile: RegularFileProperty

    @get:InputFile
    @get:Option(option = "tagBuildFile", description = "Json contains info about tag build")
    abstract val tagBuildFile: RegularFileProperty

    @get:InputFile
    @get:Option(
        option = "apiTokenFile",
        description = "API token for target project in AppCenter"
    )
    abstract val apiTokenFile: RegularFileProperty

    @get:Input
    @get:Option(
        option = "ownerName",
        description = "Owner name of target project in AppCenter"
    )
    abstract val ownerName: Property<String>

    @get:Input
    @get:Optional
    @get:Option(
        option = "appName",
        description = "Application name in AppCenter"
    )
    abstract val appName: Property<String>

    @get:Input
    @get:Option(option = "testerGroups", description = "Distribution group names")
    abstract val testerGroups: SetProperty<String>

    @get:Input
    @get:Optional
    @get:Option(
        option = "maxUploadStatusRequestCount",
        description = "Max request count to check upload status. Default = $MAX_REQUEST_COUNT",
    )
    abstract val maxUploadStatusRequestCount: Property<Int>

    @get:Input
    @get:Optional
    @get:Option(
        option = "uploadStatusRequestDelayMs",
        description = "Request delay in ms for each request. Default = $MAX_REQUEST_DELAY_MS ms",
    )
    abstract val uploadStatusRequestDelayMs: Property<Long>

    @get:Input
    @get:Optional
    @get:Option(
        option = "uploadStatusRequestDelayCoefficient",
        description = "Coefficient K for dynamic upload status request delay calculation:" +
            "delaySecs = apkSizeMb / K" +
            "If this isn't specified or 0, uploadStatusRequestDelayMs will be used." +
            "Default value is null."
    )
    abstract val uploadStatusRequestDelayCoefficient: Property<Long>

    @get:Input
    @get:Option(option = "baseFileName", description = "Application bundle name prefix")
    abstract val baseFileName: Property<String>

    @TaskAction
    fun upload() {
        val outputFile = buildVariantOutputFile.asFile.get()
        if (outputFile.extension != "apk") throw GradleException("file ${outputFile.path} is not apk")
        val currentBuildTag = fromJson(tagBuildFile.asFile.get())
        val changelogFile = changelogFile.asFile.get()
        val apiTokenFile = apiTokenFile.asFile.get()
        val workQueue: WorkQueue = workerExecutor.noIsolation()
        workQueue.submit(AppCenterUploadWork::class.java) { parameters ->
            parameters.ownerName.set(ownerName)
            parameters.appName.set(
                if (appName.isPresent) {
                    appName
                } else {
                    baseFileName.map { baseFileNameValue ->
                        "${baseFileNameValue.capitalized()}-${currentBuildTag.buildVariant}"
                    }
                }
            )
            parameters.buildName.set(currentBuildTag.name)
            parameters.buildNumber.set(currentBuildTag.buildNumber.toString())
            parameters.apiToken.set(apiTokenFile.readText())
            parameters.outputFile.set(outputFile)
            parameters.testerGroups.set(testerGroups)
            parameters.changelogFile.set(changelogFile)
            parameters.maxUploadStatusRequestCount.set(maxUploadStatusRequestCount)
            parameters.uploadStatusRequestDelayMs.set(uploadStatusRequestDelayMs)
            parameters.uploadStatusRequestDelayCoefficient.set(uploadStatusRequestDelayCoefficient)
        }
    }
}
