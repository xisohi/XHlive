package com.lizongying.mytv0

import okhttp3.Interceptor
import okhttp3.Response

class TvRequestInterceptor(private val ua: String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        // 如果已经有UA头，不覆盖
        if (originalRequest.header("User-Agent") != null) {
            return chain.proceed(originalRequest)
        }

        // 添加自定义UA
        val requestWithUA = originalRequest.newBuilder()
            .header("User-Agent", ua)
            .build()

        return chain.proceed(requestWithUA)
    }
}