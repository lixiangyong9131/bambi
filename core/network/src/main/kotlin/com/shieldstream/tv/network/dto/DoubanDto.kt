package com.shieldstream.tv.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class DoubanSubjectDto(
    val id: String = "",
    val title: String = "",
    val rate: String = "",
    val cover: String = "",
    val url: String = "",
    @SerialName("episodes_info") val episodesInfo: String = "",
    @SerialName("is_tv") val isTv: Boolean = false,
)

@Serializable
data class DoubanSubjectsDto(
    val subjects: List<DoubanSubjectDto> = emptyList(),
)

@Serializable
data class DoubanShortCommentDto(
    val content: String = "",
    val author: String = "",
)

@Serializable
data class DoubanAbstractSubjectDto(
    val id: String = "",
    val title: String = "",
    val rate: String = "",
    @SerialName("release_year") val releaseYear: String = "",
    val subtypes: String = "",
    val isTv: Boolean = false,
    val duration: String = "",
    val region: String = "",
    val directors: List<String> = emptyList(),
    val actors: List<String> = emptyList(),
    val types: List<String> = emptyList(),
    @SerialName("short_comment") val shortComment: DoubanShortCommentDto? = null,
)

@Serializable
data class DoubanAbstractDto(
    val subject: DoubanAbstractSubjectDto? = null,
)

@Serializable
data class DoubanSuggestDto(
    val title: String = "",
    val img: String = "",
    val url: String = "",
    val id: String = "",
    val episode: String = "",
)

@Serializable
data class DoubanRexxarRatingDto(
    val count: Int = 0,
    val star_count: Float = 0f,
    val value: Double = 0.0,
)

@Serializable
data class DoubanRexxarPicDto(
    val large: String = "",
    val normal: String = "",
)

@Serializable
data class DoubanRexxarSubjectDto(
    val title: String = "",
    val year: String = "",
    val intro: String = "",
    val genres: List<String> = emptyList(),
    val pic: DoubanRexxarPicDto? = null,
    val rating: DoubanRexxarRatingDto? = null,
)

@Serializable
data class DoubanRexxarStatsDto(
    val stats: List<Float> = emptyList(),
)

@Serializable
data class DoubanRexxarCollectionItemDto(
    val id: String = "",
    val title: String = "",
    val type: String = "",
    val year: String = "",
    val description: String = "",
    @SerialName("card_subtitle") val cardSubtitle: String = "",
    val pic: DoubanRexxarPicDto? = null,
    val cover: JsonElement? = null,
    @SerialName("cover_url") val coverUrl: String = "",
    val rating: DoubanRexxarRatingDto? = null,
) {
    fun bestCoverUrl(): String {
        pic?.large?.takeIf { it.isNotBlank() }?.let { return it }
        pic?.normal?.takeIf { it.isNotBlank() }?.let { return it }
        coverUrl.takeIf { it.isNotBlank() }?.let { return it }
        return when (val c = cover) {
            is JsonObject -> c["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
            is JsonPrimitive -> c.contentOrNull.orEmpty()
            else -> ""
        }
    }
}

@Serializable
data class DoubanRexxarCollectionResponseDto(
    val start: Int = 0,
    val count: Int = 0,
    val total: Int = 0,
    @SerialName("subject_collection_items") val subjectCollectionItems: List<DoubanRexxarCollectionItemDto> = emptyList(),
)

@Serializable
data class DoubanRexxarRecommendResponseDto(
    val start: Int = 0,
    val count: Int = 0,
    val total: Int = 0,
    val items: List<DoubanRexxarCollectionItemDto> = emptyList(),
)
