package com.shieldstream.tv.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
