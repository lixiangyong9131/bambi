package com.shieldstream.tv.network.api

import com.shieldstream.tv.network.dto.DoubanAbstractDto
import com.shieldstream.tv.network.dto.DoubanRexxarCollectionResponseDto
import com.shieldstream.tv.network.dto.DoubanRexxarRecommendResponseDto
import com.shieldstream.tv.network.dto.DoubanSubjectsDto
import com.shieldstream.tv.network.dto.DoubanSuggestDto
import retrofit2.http.GET
import retrofit2.http.Query
import retrofit2.http.Url

/** Public, key-less Douban endpoints used as the catalog source. */
interface DoubanApi {

    @GET("j/search_subjects")
    suspend fun subjects(
        @Query("type") type: String,
        @Query("tag") tag: String,
        @Query("sort") sort: String = "recommend",
        @Query("page_limit") pageLimit: Int = 20,
        @Query("page_start") pageStart: Int = 0,
    ): DoubanSubjectsDto

    @GET("j/search_subjects")
    suspend fun search(
        @Query("type") type: String = "movie",
        @Query("tag") tag: String = "",
        @Query("sort") sort: String = "recommend",
        @Query("page_limit") pageLimit: Int = 20,
        @Query("page_start") pageStart: Int = 0,
    ): DoubanSubjectsDto

    @GET("j/subject_suggest")
    suspend fun suggest(@Query("q") query: String): List<DoubanSuggestDto>

    @GET
    suspend fun abstract(@Url url: String): DoubanAbstractDto

    @GET
    suspend fun rexxarSubject(@Url url: String): com.shieldstream.tv.network.dto.DoubanRexxarSubjectDto

    @GET
    suspend fun rexxarRatingStats(@Url url: String): com.shieldstream.tv.network.dto.DoubanRexxarStatsDto

    @GET
    suspend fun rexxarCollection(@Url url: String): DoubanRexxarCollectionResponseDto

    @GET
    suspend fun rexxarRecommend(@Url url: String): DoubanRexxarRecommendResponseDto

    @GET
    suspend fun rexxarSearch(@Url url: String): com.shieldstream.tv.network.dto.DoubanRexxarSearchResponseDto
}
