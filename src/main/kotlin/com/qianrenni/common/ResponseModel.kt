package com.qianrenni.common

import kotlinx.serialization.Serializable


/**
 * 定义响应契约接口
 */
interface ResponseContract<out T> {
    val code: Int
    val data: T?
    val message: String
}

/**
 * 密封类仅作为类型标记,不持有状态
 */
@Serializable
sealed class ResponseModel<out T> : ResponseContract<T> {

    @Serializable
    data class Success<T>(
        override val data: T,
        override val code: Int = 0,
        override val message: String = ""
    ) : ResponseModel<T>()

    @Serializable
    data class Error(
        override val message: String,
        override val code: Int = 1,
        override val data: Nothing? = null // Nothing? 等价于只能为 null
    ) : ResponseModel<Nothing>()

    @Serializable
    data class Empty(
        override val message: String = "",
        override val code: Int = 0,
        override val data: Nothing? = null
    ) : ResponseModel<Nothing>()
}

@Serializable
data class PageResult<T>(
    val items: List<T>,
    val total: Int,
    val page: Int,
    val size: Int
)