package com.example.network

import com.example.model.PairRequest
import com.example.model.PairResponse
import com.example.model.ReconnectRequest
import com.example.model.ReconnectResponse
import com.example.model.ServerInfoResponse
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Transport HTTP Client communicating with Taloola Caller Bridge (port 5090).
 */
class TaloolaHttpClient(
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()
) {
    private val moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val serverInfoAdapter = moshi.adapter(ServerInfoResponse::class.java)
    private val pairRequestAdapter = moshi.adapter(PairRequest::class.java)
    private val pairResponseAdapter = moshi.adapter(PairResponse::class.java)
    private val reconnectRequestAdapter = moshi.adapter(ReconnectRequest::class.java)
    private val reconnectResponseAdapter = moshi.adapter(ReconnectResponse::class.java)

    /**
     * 8. SERVER INFO: GET /api/server/info
     */
    suspend fun getServerInfo(host: String, port: Int, tls: Boolean = false): Result<ServerInfoResponse> =
        withContext(Dispatchers.IO) {
            val scheme = if (tls) "https" else "http"
            val url = "$scheme://$host:$port/api/server/info"
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", "ALAMER-Caller-Assistant/1.0")
                .get()
                .build()

            try {
                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext Result.failure(
                            IOException("فشل استرجاع معلومات الخادم (HTTP ${response.code})")
                        )
                    }
                    val bodyString = response.body?.string()
                        ?: return@withContext Result.failure(IOException("استجابة الخادم فارغة"))
                    val info = serverInfoAdapter.fromJson(bodyString)
                        ?: return@withContext Result.failure(IOException("فشل قراءة بيانات الخادم"))
                    Result.success(info)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /**
     * 9. PAIRING: POST /api/caller-assistant/pair
     */
    suspend fun pair(host: String, port: Int, tls: Boolean = false, pairRequest: PairRequest): Result<PairResponse> =
        withContext(Dispatchers.IO) {
            val scheme = if (tls) "https" else "http"
            val url = "$scheme://$host:$port/api/caller-assistant/pair"
            val jsonBody = pairRequestAdapter.toJson(pairRequest)
            val request = Request.Builder()
                .url(url)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("User-Agent", "ALAMER-Caller-Assistant/1.0")
                .post(jsonBody.toRequestBody(jsonMediaType))
                .build()

            try {
                okHttpClient.newCall(request).execute().use { response ->
                    val bodyString = response.body?.string() ?: ""
                    if (!response.isSuccessful) {
                        return@withContext Result.failure(
                            IOException("فشل طلب الاقتران (HTTP ${response.code}): $bodyString")
                        )
                    }
                    val pairResponse = pairResponseAdapter.fromJson(bodyString)
                        ?: return@withContext Result.failure(IOException("فشل قراءة استجابة الاقتران"))
                    if (!pairResponse.success) {
                        return@withContext Result.failure(
                            IOException(pairResponse.errorMessage ?: "تم رفض الاقتران من جانب الخادم")
                        )
                    }
                    Result.success(pairResponse)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /**
     * 13. RECONNECT: POST /api/caller-assistant/reconnect
     */
    suspend fun reconnect(host: String, port: Int, tls: Boolean = false, reconnectRequest: ReconnectRequest): Result<ReconnectResponse> =
        withContext(Dispatchers.IO) {
            val scheme = if (tls) "https" else "http"
            val url = "$scheme://$host:$port/api/caller-assistant/reconnect"
            val jsonBody = reconnectRequestAdapter.toJson(reconnectRequest)
            val request = Request.Builder()
                .url(url)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("User-Agent", "ALAMER-Caller-Assistant/1.0")
                .post(jsonBody.toRequestBody(jsonMediaType))
                .build()

            try {
                okHttpClient.newCall(request).execute().use { response ->
                    val bodyString = response.body?.string() ?: ""
                    if (response.code == 401 || response.code == 403) {
                        return@withContext Result.failure(
                            SecurityException("CREDENTIAL_INVALID")
                        )
                    }
                    if (!response.isSuccessful) {
                        return@withContext Result.failure(
                            IOException("فشل إعادة الاتصال (HTTP ${response.code})")
                        )
                    }
                    val reconnectResponse = reconnectResponseAdapter.fromJson(bodyString)
                        ?: return@withContext Result.failure(IOException("فشل قراءة استجابة إعادة الاتصال"))
                    if (!reconnectResponse.success) {
                        if (reconnectResponse.deviceStatus.equals("REVOKED", ignoreCase = true)) {
                            return@withContext Result.failure(SecurityException("DEVICE_REVOKED"))
                        }
                        return@withContext Result.failure(
                            IOException(reconnectResponse.errorMessage ?: "فشلت إعادة الاتصال")
                        )
                    }
                    Result.success(reconnectResponse)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /**
     * Diagnostic Health Check: GET /health
     */
    suspend fun checkHealth(host: String, port: Int, tls: Boolean = false): Result<Int> =
        withContext(Dispatchers.IO) {
            val scheme = if (tls) "https" else "http"
            val url = "$scheme://$host:$port/health"
            val request = Request.Builder()
                .url(url)
                .get()
                .build()
            try {
                okHttpClient.newCall(request).execute().use { response ->
                    Result.success(response.code)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
}
