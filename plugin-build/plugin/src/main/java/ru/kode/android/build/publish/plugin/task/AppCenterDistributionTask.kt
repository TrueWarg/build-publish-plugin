package ru.kode.android.build.publish.plugin.task

import org.gradle.api.DefaultTask
import org.gradle.api.plugins.BasePlugin
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.options.Option
import ru.kode.android.build.publish.plugin.error.ValueNotFoundException
import ru.kode.android.build.publish.plugin.task.entity.ChunkRequestBody
import ru.kode.android.build.publish.plugin.util.capitalize
import java.io.File

abstract class AppCenterDistributionTask : DefaultTask() {
    init {
        description = "Task to send apk to AppCenter"
        group = BasePlugin.BUILD_GROUP
    }

    @get:Input
    @get:Option(
        option = "config",
        description = "AppCenter config: owner_name, app_name and api_token_file_path"
    )
    abstract val config: MapProperty<String, String>

    @get:Input
    @get:Option(
        option = "baseOutputFileName",
        description = "Application bundle name for changelog"
    )
    abstract val baseOutputFileName: Property<String>

    @get:Input
    @get:Option(
        option = "currentBuildVariant",
        description = "Project current build variant"
    )
    abstract val currentBuildVariant: Property<String>

    @get:Input
    @get:Option(option = "buildVariants", description = "List of all available build variants")
    abstract val buildVariants: SetProperty<String>

    @get:Input
    @get:Option(option = "distributionGroups", description = "distribution group names")
    abstract val distributionGroups: SetProperty<String>

    @get:Input
    @get:Option(
        option = "releaseNotes",
        description = "Release notes"
    )
    abstract val releaseNotes: Property<String>

    @TaskAction
    fun upload() {
        project.logger.debug("Step 1/7: Prepare upload")
        val config = config.get()
        val buildVariant = currentBuildVariant.get()
        val ownerName = config["owner_name"] ?: throw ValueNotFoundException("owner_name")
        val appName = (config["app_name"] ?: throw ValueNotFoundException("app_name")) +
            "-${buildVariant.capitalize()}"
        val apiTokenFilePath = config["api_token_file_path"]
            ?: throw ValueNotFoundException("api_token_file_path")

        val token = File(apiTokenFilePath).readText()
        val uploader = AppCenterUploader(ownerName, appName, token)
        val prepareResponse = uploader.prepareRelease()

        project.logger.debug("Step 2/7: Send metadata")
        val apkDir = "${project.rootDir.path}/app/build/outputs/apk/${buildVariant}"
        val apkFile = getLatestApk(apkDir) ?: error("no apk in $apkDir")
        val packageAssetId = prepareResponse.package_asset_id
        val encodedToken = prepareResponse.url_encoded_token
        val metaResponse = uploader.sendMetaData(apkFile, packageAssetId, encodedToken)

        // See NOTE_CHUNKS_UPLOAD_LOOP
        project.logger.debug("Step 3/7: Upload apk file chunks")
        metaResponse.chunk_list.forEachIndexed { i, chunkNumber ->
            val range = (i * metaResponse.chunk_size)..((i + 1) * metaResponse.chunk_size)
            project.logger.debug("Step 3/7 : Upload chunk ${i + 1}/${metaResponse.chunk_list.size}")
            uploader.uploadChunk(
                packageAssetId = packageAssetId,
                encodedToken = encodedToken,
                chunkNumber = chunkNumber,
                request = ChunkRequestBody(apkFile, range, "application/octet-stream")
            )
        }

        project.logger.debug("Step 4/7: Finish upload")
        uploader.sendUploadIsFinished(packageAssetId, encodedToken)

        project.logger.debug("Step 5/7: Commit uploaded release")
        uploader.commit(prepareResponse.id)

        project.logger.debug("Step 6/7: Fetching for release to be ready to publish")
        val publishResponse = uploader.waitingReadyToBePublished(prepareResponse.id)

        project.logger.debug("Step 7/7: Distribute to the app testers: $distributionGroups")
        uploader.distribute(publishResponse.id, distributionGroups.get(), releaseNotes.get())
        project.logger.debug("Done")
    }
}

private fun getLatestApk(dirPath: String): File? {
    val dir = File(dirPath)
    val files = dir.listFiles()
    if (files == null || files.isEmpty()) return null

    var lastModifiedApk: File? = null
    for (i in 1 until files.size) {
        val foundFirstApk = lastModifiedApk == null && files[i].extension == "apk"
        val foundNewerApk = lastModifiedApk != null
            && (lastModifiedApk.lastModified() < files[i].lastModified())
            && files[i].extension == "apk"

        if (foundFirstApk || foundNewerApk) {
            lastModifiedApk = files[i]
        }
    }

    return lastModifiedApk
}
