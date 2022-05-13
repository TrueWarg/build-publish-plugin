package ru.kode.android.build.publish.plugin.task.entity

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class GetUploadResponse(
    val id: String,
    val upload_status: String,
    val error_details: String?,
    val release_distinct_id: String?,
)
