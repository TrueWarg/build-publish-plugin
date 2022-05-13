package ru.kode.android.build.publish.plugin.task

import com.squareup.moshi.Moshi
import okhttp3.*
import ru.kode.android.build.publish.plugin.task.entity.ChunkRequestBody
import ru.kode.android.build.publish.plugin.task.entity.GetUploadResponse
import ru.kode.android.build.publish.plugin.task.entity.PrepareResponse
import ru.kode.android.build.publish.plugin.task.entity.SentMetaDataResponse
import java.io.File
import java.util.concurrent.TimeUnit

internal class AppCenterUploader(
    private val ownerName: String,
    private val appName: String,
    token: String,
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(HTTP_CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
        .readTimeout(HTTP_CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
        .writeTimeout(HTTP_CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
        .addInterceptor(AttachTokenInterceptor(token))
        .build()

    private val moshi = Moshi.Builder().build()

    fun prepareRelease(): PrepareResponse {
        val url = "https://api.appcenter.ms/v0.1/apps/${ownerName}/${appName}/uploads/releases"
        val adapter = moshi.adapter(PrepareResponse::class.java)
        val body = post(url) ?: error("body is null")
        return adapter.fromJson(body.string()).required(body)
    }

    fun sendMetaData(
        apkFile: File,
        packageAssetId: String,
        encodedToken: String,
    ): SentMetaDataResponse {
        val appType = "application/vnd.android.package-archive"
        val url = "https://file.appcenter.ms/upload/set_metadata/$packageAssetId" +
            "?file_name=${apkFile.name}" +
            "&file_size=${apkFile.length()}" +
            "&token=$encodedToken" +
            "&content_type=$appType"
        val adapter = moshi.adapter(SentMetaDataResponse::class.java)
        val body = post(url) ?: error("body is null")
        val response = adapter.fromJson(body.string()).required(body)
        if (response.status_code != "Success") {
            error("send meta data terminated with ${response.status_code}")
        }
        return response
    }

    fun uploadChunk(
        packageAssetId: String,
        encodedToken: String,
        chunkNumber: Int,
        request: ChunkRequestBody,
    ) {
        val url = "https://file.appcenter.ms/upload/upload_chunk/$packageAssetId" +
            "?token=$encodedToken" +
            "&block_number=$chunkNumber"
        post(url, request)
    }

    fun sendUploadIsFinished(
        packageAssetId: String,
        encodedToken: String,
    ) {
        val url = "https://file.appcenter.ms/upload/finished/$packageAssetId?token=$encodedToken"
        post(url)
    }

    fun commit(preparedUploadId: String) {
        val url = "https://api.appcenter.ms/v0.1/apps/" +
            "$ownerName/$appName/uploads/releases/$preparedUploadId"

        val body = FormBody.Builder()
            .addEncoded("upload_status", "uploadFinished")
            .addEncoded("id", preparedUploadId)
            .build()

        patch(url, body)
    }

    fun waitingReadyToBePublished(preparedUploadId: String): GetUploadResponse {
        var requestCount = 0
        var response: GetUploadResponse
        do {
            response = getUpload(preparedUploadId)
            Thread.sleep(1000L)
            requestCount++
            require(requestCount <= 20) { "Cannot fetch upload status" }
        } while (response.upload_status == "readyToBePublished")
        return response
    }

    fun distribute(
        releaseId: String,
        distributionGroups: Set<String>,
        releaseNotes: String,
    ) {
        val url = "https://api.appcenter.ms/v0.1/apps/$ownerName/$appName/releases/$releaseId"
        val destinations = distributionGroups.map { "{ \"name\" : $it }" }.toString()

        val body = FormBody.Builder()
            .addEncoded("upload_status", "uploadFinished")
            .addEncoded("destinations", destinations)
            .addEncoded("release_notes", releaseNotes)
            .addEncoded("notify_testers", "true")
            .build()

        patch(url, body)
    }

    private fun getUpload(preparedUploadId: String): GetUploadResponse {
        val url = "https://api.appcenter.ms/v0.1/apps/" +
            "$ownerName/$appName/uploads/releases/$preparedUploadId"
        val body = get(url) ?: error("body is null")
        val adapter = moshi.adapter(GetUploadResponse::class.java)
        return adapter.fromJson(body.string()).required(body)
    }

    private fun get(url: String): ResponseBody? {
        val request = Request
            .Builder()
            .get()
            .url(url)
            .build()

        return client.newCall(request).execute().body
    }

    private fun post(url: String, body: RequestBody = FormBody.Builder().build()): ResponseBody? {
        val request = Request
            .Builder()
            .post(body)
            .url(url)
            .build()

        return client.newCall(request).execute().body
    }

    private fun patch(url: String, body: RequestBody = FormBody.Builder().build()): ResponseBody? {
        val request = Request
            .Builder()
            .patch(body)
            .url(url)
            .build()

        return client.newCall(request).execute().body
    }
}

private fun <T> T?.required(body: ResponseBody): T {
    return this ?: error("cannot parse json body ${body.string()}")
}

private class AttachTokenInterceptor(
    private val token: String,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val newRequest = originalRequest.newBuilder()
            .addHeader(name = "Content-Type", "application/json; charset=UTF-8")
            .addHeader("Accept", "application/json; charset=UTF-8")
            .addHeader(name = "X-API-Token", token)
            .build()
        return chain.proceed(newRequest)
    }
}

private const val HTTP_CONNECT_TIMEOUT_SEC = 10L
