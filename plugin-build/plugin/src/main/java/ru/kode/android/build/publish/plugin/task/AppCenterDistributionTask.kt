package ru.kode.android.build.publish.plugin.task

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.plugins.BasePlugin
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.options.Option
import ru.kode.android.build.publish.plugin.enity.BuildVariant
import ru.kode.android.build.publish.plugin.error.ValueNotFoundException
import ru.kode.android.build.publish.plugin.task.entity.ChunkRequestBody
import ru.kode.android.build.publish.plugin.util.capitalizedName
import java.io.File
import java.io.IOException

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
        option = "currentBuildVariant",
        description = "Project current build variant"
    )
    abstract val currentBuildVariant: Property<BuildVariant>

    @get:InputFile
    @get:Option(
        option = "outputFile",
        description = "Artifact output file (absolute path is expected)"
    )
    abstract val outputFile: RegularFileProperty

    @get:Input
    @get:Option(option = "distributionGroups", description = "distribution group names")
    abstract val distributionGroups: SetProperty<String>

    @get:InputFile
    @get:Option(
        option = "releaseNotes",
        description = "Release notes"
    )
    abstract val changelogFile: RegularFileProperty

    @TaskAction
    fun upload() {
        project.logger.debug("Step 1/7: Prepare upload")
        val config = config.get()
        val buildVariant = currentBuildVariant.get()
        val ownerName = config["owner_name"] ?: throw ValueNotFoundException("owner_name")
        val appName = (config["app_name"] ?: throw ValueNotFoundException("app_name")) +
            "-${buildVariant.capitalizedName()}"

        val apiTokenFilePath = config["api_token_file_path"]
            ?: throw ValueNotFoundException("api_token_file_path")

        val token = File(apiTokenFilePath).readText()
        val uploader = AppCenterUploader(ownerName, appName, project.logger, token)
        val prepareResponse = uploader.prepareRelease()
        uploader.iniUploadApi(prepareResponse.upload_domain ?: "https://file.appcenter.ms")

        project.logger.debug("Step 2/7: Send metadata")
        val apkFile = outputFile.get().asFile
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
        val releaseId = publishResponse.release_distinct_id
        val releaseNotes = try {
            changelogFile.get().asFile.readText()
        } catch (e: IOException) {
            project.logger.debug("failed to read changelog $e"); null
        }
        if (releaseId != null) {
            uploader.distribute(releaseId, distributionGroups.get(), releaseNotes.orEmpty())
        } else {
            project.logger.error("Apk was uploaded, " +
                "but distributors will not be notified: " +
                "field 'release_distinct_id' is null, cannot execute 'distribute' request")
        }
        project.logger.debug("Done")
    }
}

