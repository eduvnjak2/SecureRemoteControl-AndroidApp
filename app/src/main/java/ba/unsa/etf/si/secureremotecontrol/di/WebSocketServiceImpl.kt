package ba.unsa.etf.si.secureremotecontrol.data.websocket

import android.util.Log
import ba.unsa.etf.si.secureremotecontrol.data.api.WebSocketService
import ba.unsa.etf.si.secureremotecontrol.data.models.Device
import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton
import ba.unsa.etf.si.secureremotecontrol.data.api.RtcMessage
import ba.unsa.etf.si.secureremotecontrol.data.fileShare.*
import ba.unsa.etf.si.secureremotecontrol.data.util.RegistrationPreferences // Import
import com.google.gson.JsonObject

@Singleton
class WebSocketServiceImpl @Inject constructor(
    private val client: OkHttpClient,
    private val gson: Gson,
    private val registrationPreferences: RegistrationPreferences // Inject RegistrationPreferences
) : WebSocketService {

    private val TAG = "WebSocketService"
    private var heartbeatJob: Job? = null
    private val clientScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val HEARTBEAT_SEND_INTERVAL_MS = 25000L
    private val MAX_RETRY_ATTEMPTS = 5
    private val RETRY_DELAY_MS = 5000L // 5s
    private var retryCount = 0

    // Single WebSocket instance for all operations
    private var webSocket: WebSocket? = null
    private var isConnected = false

    // Message channel for broadcasting to all collectors
    private val messageChannel = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 10,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    override fun connectWebSocket(): WebSocket {
        if (isConnected && webSocket != null) {
            Log.d(TAG, "WebSocket is already connected.")
            return webSocket!!
        }

        val currentWebSocketUrl = registrationPreferences.webSocketUrl
        if (currentWebSocketUrl.isNullOrEmpty()) {
            Log.e(TAG, "WebSocket URL is not configured in preferences. Cannot connect.")
            // Return the existing webSocket instance (which might be null or closed)
            // The connection will likely fail or not establish.
            // This situation should ideally be prevented by UI flow ensuring URL is set.
            // To satisfy the non-nullable return type if webSocket is null:
            return webSocket ?: client.newWebSocket(
                Request.Builder().url("wss://invalid-url-will-fail.com").build(), // Dummy request
                object : WebSocketListener() {
                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        Log.e(TAG, "Dummy WebSocket failed due to missing URL configuration: ${t.message}")
                    }
                }
            )
        }

        Log.d(TAG, "Creating new WebSocket connection to: $currentWebSocketUrl")
        val request = Request.Builder().url(currentWebSocketUrl).build() // Use dynamic URL

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket connection opened successfully to $currentWebSocketUrl")
                isConnected = true
                retryCount = 0
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "Message received: $text")
                clientScope.launch {
                    messageChannel.emit(text)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket connection failed: ${t.message}")
                isConnected = false
                retryConnection()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $reason")
                isConnected = false
            }
        })

        return webSocket!!
    }
    override fun sendDeregistrationRequest(deviceId: String, deregistrationKey: String) {
        if (registrationPreferences.webSocketUrl.isNullOrEmpty()) {
            Log.e(TAG, "Cannot send deregistration request: WebSocket URL not configured.")
            return
        }
        if (!isConnected) {
            Log.e(TAG, "Cannot send deregistration request: WebSocket is not connected.")
            connectWebSocket()
        }

        val message = gson.toJson(mapOf(
            "type" to "deregister",
            "deviceId" to deviceId,
            "deregistrationKey" to deregistrationKey
        ))
        val success = webSocket?.send(message) ?: false
        Log.d(TAG, "Deregistration request sent for deviceId: $deviceId, success: $success")
    }

    override fun observeMessages(): Flow<String> {
        if (registrationPreferences.webSocketUrl.isNullOrEmpty()){
            Log.w(TAG, "WebSocket URL not set. Message observation might not work until URL is configured and connection is established.")
            // Potentially return an empty flow or a flow that emits an error.
            // For now, it will attempt to connect if not connected, which will fail if URL is missing.
        }
        if (!isConnected) {
            Log.d(TAG, "WebSocket not connected, connecting now")
            connectWebSocket() // This will now use the dynamic URL
        }
        Log.d(TAG, "Setting up message observation flow")
        return messageChannel.asSharedFlow()
    }

    override fun sendRawMessage(message: String) {
        if (registrationPreferences.webSocketUrl.isNullOrEmpty()){
            Log.e(TAG, "Cannot send raw message: WebSocket URL not configured.")
            return
        }
        if (!isConnected) {
            Log.d(TAG, "WebSocket not connected, connecting now before sending message")
            connectWebSocket()
        }

        val success = webSocket?.send(message) ?: false
        Log.d(TAG, "Sent raw message: $message, success: $success")

    }

    override fun sendRegistration(device: Device) {
        val message = gson.toJson(mapOf(
            "type" to "register",
            "deviceId" to device.deviceId,
            "registrationKey" to device.registrationKey,
            "model" to device.model,
            "osVersion" to device.osVersion
        ))
        sendRawMessage(message)
        startHeartbeat(device.deviceId)
    }

    override fun sendFinalConformation(from: String, token: String, decision: Boolean) {
        val message = gson.toJson(mapOf(
            "type" to "session_final_confirmation",
            "from" to from,
            "token" to token,
            "decision" to if (decision) "accepted" else "rejected"
        ))
        Log.d(TAG, "Final confirmation sent: $message")
        sendRawMessage(message)
    }

    override fun disconnect() {
        stopHeartbeat()
        webSocket?.close(1000, "Closing WebSocket")
        webSocket = null
        isConnected = false
    }

    fun sendDeregistration(device: Device) {
        val message = gson.toJson(mapOf(
            "type" to "deregister",
            "deviceId" to device.deviceId
        ))
        sendRawMessage(message)
        stopHeartbeat()
    }

    override fun startHeartbeat(deviceId: String) {
        heartbeatJob?.cancel()
        heartbeatJob = clientScope.launch {
            delay(500)
            while (isActive) {
                sendStatusHeartbeatMessage(deviceId)
                delay(HEARTBEAT_SEND_INTERVAL_MS)
            }
        }
    }
    override fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    override fun sendSessionRequest(from: String, token: String) {
        if (registrationPreferences.webSocketUrl.isNullOrEmpty()){
            Log.e(TAG, "Cannot send session request: WebSocket URL not configured.")
            _coroutineScope.launch { messageChannel.emit("{\"type\":\"error\", \"message\":\"WebSocket URL not configured.\"}")}
            return
        }
        if (!isConnected) {
            Log.e(TAG, "Cannot send session request: WebSocket is not connected.")
            connectWebSocket()
        }
        val message = gson.toJson(mapOf(
            "type" to "session_request",
            "from" to from,
            "token" to token
        ))
        val success = webSocket?.send(message) ?: false
        Log.d(TAG, "Session request sent from $from, success: $success")
    }

    private val _coroutineScope = CoroutineScope(Dispatchers.Main)


    override fun observeRtcMessages(): Flow<RtcMessage> = observeMessages()
        .mapNotNull { message ->
            try {
                val jsonObject = JSONObject(message)
                val type = jsonObject.optString("type", "")

                if (type == "offer" || type == "answer" || type == "ice-candidate") {
                    val fromId = jsonObject.optString("fromId", "")
                    val toId = jsonObject.optString("toId", "")

                    if (jsonObject.has("payload")) {
                        val payload = jsonObject.getJSONObject("payload")
                        RtcMessage(type, fromId, toId, payload)
                    } else {
                        Log.w(TAG, "RTC message is missing payload: $message")
                        null
                    }
                } else {
                    null
                }
            } catch (e: Exception) {
                null
            }
        }

    private fun sendStatusHeartbeatMessage(deviceId: String) {
        val statusMsg = JSONObject().apply {
            put("type", "status")
            put("deviceId", deviceId)
            put("status", "active")
        }
        val sent = webSocket?.send(statusMsg.toString()) ?: false
        if (sent) {
            Log.d(TAG, "Sent status/heartbeat for device: $deviceId")
        } else {
            Log.w(TAG, "Failed to send status/heartbeat for device: $deviceId (WebSocket not ready?)")
        }
    }

    private fun retryConnection() {
        if (registrationPreferences.webSocketUrl.isNullOrEmpty()) {
            Log.w(TAG, "Skipping retryConnection as WebSocket URL is not set.")
            return
        }
        if (retryCount < MAX_RETRY_ATTEMPTS) {
            retryCount++
            Log.d(TAG, "Retrying connection #$retryCount after ${RETRY_DELAY_MS}ms")
            clientScope.launch {
                delay(RETRY_DELAY_MS)
                connectWebSocket()
            }
        } else {
            Log.e(TAG, "Max retry attempts reached ($MAX_RETRY_ATTEMPTS). Connection failed.")
            retryCount = 0
        }
    }

    override fun observeClickEvents(): Flow<Pair<Float, Float>> = observeMessages()
        .mapNotNull { message ->
            try {
                val jsonObject = JSONObject(message)
                if (jsonObject.getString("type") == "click") {
                    val payload = jsonObject.getJSONObject("payload")
                    val x = payload.getDouble("x").toFloat()
                    val y = payload.getDouble("y").toFloat()
                    Pair(x, y)
                } else null
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing click event", e)
                null
            }
        }

    override fun sendRequestSessionFileshare(deviceId: String, sessionId: String) {
        val message = RequestSessionFileshareMessage(
            deviceId = deviceId,
            sessionId = sessionId
        )
        sendRawMessage(gson.toJson(message))
        Log.d(TAG, "Sent request_session_fileshare: ${gson.toJson(message)}")
    }

    override fun sendBrowseResponse(deviceId: String, sessionId: String, path: String, entries: List<FileEntry>) {
        val message = BrowseResponseMessage(
            deviceId = deviceId,
            sessionId = sessionId,
            path = path,
            entries = entries
        )
        sendRawMessage(gson.toJson(message))
        Log.d(TAG, "Sent browse_response: ${gson.toJson(message)}")
    }

    override fun sendUploadStatus(deviceId: String, sessionId: String, status: String, message: String?, path: String?, fileName: String) {
        val msg = UploadStatusMessage(
            deviceId = deviceId,
            sessionId = sessionId,
            status = status,
            message = message,
            path = path,
            fileName = fileName
        )
        sendRawMessage(gson.toJson(msg))
        Log.d(TAG, "Sent upload_status: ${gson.toJson(msg)}")
    }

    override fun sendDownloadResponse(deviceId: String, sessionId: String, downloadUrl: String) {
        val message = DownloadResponseMessage(
            deviceId = deviceId,
            sessionId = sessionId,
            downloadUrl = downloadUrl
        )
        sendRawMessage(gson.toJson(message))
        Log.d(TAG, "Sent download_response: ${gson.toJson(message)}")
    }

    private inline fun <reified T> observeSpecificMessage(messageType: String): Flow<T> = observeMessages()
        .mapNotNull { jsonString ->
            try {
                if (!jsonString.contains("\"type\":\"$messageType\"")) {
                    return@mapNotNull null
                }
                val jsonObject = JSONObject(jsonString)
                if (jsonObject.optString("type", "") == messageType) {
                    gson.fromJson(jsonString, T::class.java)
                } else {
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing $messageType message: $jsonString", e)
                null
            }
        }

    override fun observeDecisionFileShare(): Flow<DecisionFileshareMessage> =
        observeSpecificMessage("decision_fileshare")

    override fun observeBrowseRequest(): Flow<BrowseRequestMessage> =
        observeSpecificMessage("browse_request")

    override fun observeUploadFiles(): Flow<UploadFilesMessage> =
        observeSpecificMessage("upload_files")

    override fun observeDownloadRequest(): Flow<DownloadRequestMessage> =
        observeSpecificMessage("download_request")
}