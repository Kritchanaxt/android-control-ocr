package com.example.android_screen_relay

import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import android.util.Log
import kotlinx.coroutines.launch

class RelayServer(port: Int) : WebSocketServer(InetSocketAddress(port)) {

    private var currentPasskey: String? = null
    // Use thread-safe collection for authenticated sessions to avoid ConcurrentModificationException
    private val authenticatedSessions = java.util.concurrent.CopyOnWriteArraySet<WebSocket>()
    private val pendingConnections = java.util.concurrent.ConcurrentHashMap<String, WebSocket>()
    
    // Callback for notifications
    var onShowNotification: ((String, String) -> Unit)? = null
    var onConnectionRequest: ((String, String) -> Unit)? = null

    fun approveConnection(requestId: String) {
        val conn = pendingConnections.remove(requestId)
        if (conn != null && conn.isOpen) {
            authenticatedSessions.add(conn)
            val response = org.json.JSONObject()
            response.put("type", "auth_response")
            response.put("status", "ok")
            conn.send(response.toString())
            
            val remoteAddr = conn.remoteSocketAddress?.address?.hostAddress ?: "Unknown"
            android.util.Log.d("RelayServer", "Connection Approved: $remoteAddr")
        }
    }

    fun denyConnection(requestId: String) {
        val conn = pendingConnections.remove(requestId)
        if (conn != null) {
            val response = org.json.JSONObject()
            response.put("type", "auth_response")
            response.put("status", "denied")
            conn.send(response.toString())
            conn.close(1008, "Connection Denied by User")
        }
    }

    fun updatePasskey(passkey: String?) {
        this.currentPasskey = passkey
        LogRepository.addLog(
            component = "RelayServer",
            event = "security",
            data = mapOf("action" to "passkey_update", "passkey" to (passkey ?: "null")),
            level = "INFO",
            type = LogRepository.LogType.INFO
        )
        if (passkey == null) {
            // If passkey is cleared/reset, disconnect everyone or just invalidate?
            // Disconnecting is safer
            authenticatedSessions.clear()
            connections.forEach { it.close(1000, "Server passkey reset") }
        }
    }

    init {
        isReuseAddr = true
        connectionLostTimeout = 30 // Check for broken connections every 30 seconds
    }

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        val remoteAddr = conn.remoteSocketAddress?.address?.hostAddress ?: "Unknown"
        val descriptor = handshake.resourceDescriptor
        
        Log.d("RelayServer", "NEW CONNECTION: IP=$remoteAddr | Resource=$descriptor")
        LogRepository.addLog(
            component = "RelayServer",
            event = "client_connected",
            data = mapOf("ip" to remoteAddr, "resource" to descriptor),
            level = "INFO",
            type = LogRepository.LogType.INFO
        )
        
        // Timeout check removed for testing stability
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
        val remoteAddr = conn.remoteSocketAddress?.address?.hostAddress ?: "Unknown"
        val who = if (remote) "Remote" else "Local"
        
        Log.d("RelayServer", "DISCONNECTED: $remoteAddr | Code=$code | Reason=$reason | By=$who")
        LogRepository.addLog(
            component = "RelayServer",
            event = "client_disconnected",
            data = mapOf("ip" to remoteAddr, "code" to code, "reason" to reason, "initiator" to who),
            level = "INFO",
            type = LogRepository.LogType.INFO
        )
        authenticatedSessions.remove(conn)
        
        // Remove from pending connections if present (This is O(N) but necessary to prevent leaks)
        val iterator = pendingConnections.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value == conn) {
                iterator.remove()
            }
        }
    }

    override fun onMessage(conn: WebSocket, message: String) {
        try {
            val json = org.json.JSONObject(message)
            val type = json.optString("type")
            
            // SECURITY CHECK: Authentication
            if (type == "auth") {
                val attemptKey = json.optString("key")
                val remoteAddr = conn.remoteSocketAddress?.address?.hostAddress ?: "?"
                
                LogRepository.addLog(
                    component = "RelayServer",
                    event = "auth_request",
                    data = mapOf("ip" to remoteAddr),
                    type = LogRepository.LogType.INCOMING
                )
                
                if (currentPasskey != null && attemptKey == currentPasskey) {
                    // NEW LOGIC: Move to Pending State instead of direct approval
                    val requestId = java.util.UUID.randomUUID().toString()
                    pendingConnections[requestId] = conn

                    val response = org.json.JSONObject()
                    response.put("type", "auth_response")
                    response.put("status", "pending")
                    conn.send(response.toString())

                    // Notify Service to show UI
                    onConnectionRequest?.invoke(requestId, remoteAddr)
                    
                    Log.d("RelayServer", "AUTH PENDING: Client $remoteAddr waiting for approval.")
                    LogRepository.addLog(
                        component = "RelayServer",
                        event = "auth_pending",
                        data = mapOf("ip" to remoteAddr, "requestId" to requestId),
                        level = "INFO",
                        type = LogRepository.LogType.INFO
                    )
                } else {
                    val response = org.json.JSONObject()
                    response.put("type", "auth_response")
                    response.put("status", "failed")
                    conn.send(response.toString())
                    conn.close(1008, "Invalid Passkey") // Specific close code
                    
                    Log.w("RelayServer", "AUTH FAILED: Invalid passkey from $remoteAddr")
                    LogRepository.addLog(
                        component = "RelayServer",
                        event = "auth_failed",
                        data = mapOf("ip" to remoteAddr),
                        level = "WARN",
                        type = LogRepository.LogType.ERROR
                    )
                }
                return
            }

            // Reject all other messages if not authenticated
            if (!authenticatedSessions.contains(conn)) {
                return 
            }
            
            // Log command details strictly as String map for reliability
            val commandData = mutableMapOf<String, Any>("action" to type)
            // Extract some details for the log
            val iter = json.keys()
            while(iter.hasNext()) {
                val key = iter.next()
                if (key != "type" && key != "key") {
                     // Store as string to avoid JSON recursion issues during logging
                     commandData[key] = json.opt(key).toString() 
                }
            }
            
            LogRepository.addLog(
                component = "RelayServer",
                event = "command_received",
                data = commandData,
                type = LogRepository.LogType.INCOMING
            )
            
            // ... (Rest of logic remains same, just logging above)
            
            when (type) {
                "click" -> {
                    // Check for normalized coordinates first
                    if (json.has("x_percent") && json.has("y_percent")) {
                        val displayMetrics = android.content.res.Resources.getSystem().displayMetrics
                        val screenWidth = displayMetrics.widthPixels
                        val screenHeight = displayMetrics.heightPixels
                        
                        val xPct = json.optDouble("x_percent").toFloat()
                        val yPct = json.optDouble("y_percent").toFloat()
                        
                        val realX = xPct * screenWidth
                        val realY = yPct * screenHeight
                         RelayAccessibilityService.instance?.click(realX, realY)
                         
                    } else {
                        // Old absolute way
                        val x = json.optDouble("x").toFloat()
                        val y = json.optDouble("y").toFloat()
                        RelayAccessibilityService.instance?.click(x, y)
                    }
                }
                "swipe" -> {
                    val x1 = json.optDouble("x1").toFloat()
                    val y1 = json.optDouble("y1").toFloat()
                    val x2 = json.optDouble("x2").toFloat()
                    val y2 = json.optDouble("y2").toFloat()
                    val duration = json.optLong("duration", 300)
                    RelayAccessibilityService.instance?.swipe(x1, y1, x2, y2, duration)
                }
                "notification" -> {
                    val title = json.optString("title", "New Notification")
                    val message = json.optString("message", "You received a new message.")
                    onShowNotification?.invoke(title, message)
                    
                    LogRepository.addLog(
                        component = "RelayServer",
                        event = "show_notification",
                        data = mapOf("title" to title, "message" to message),
                        type = LogRepository.LogType.INFO
                    )
                }
                "back" -> RelayAccessibilityService.instance?.performGlobal(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                "home" -> RelayAccessibilityService.instance?.performGlobal(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
                "recent" -> RelayAccessibilityService.instance?.performGlobal(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS)
                "stop_server" -> {
                    // Logic to stop server from client? might be dangerous, maybe just stop stream
                }
            }
        } catch (e: Exception) {
            // Not a JSON or error parsing
            // e.printStackTrace()
        }
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        Log.e("RelayServer", "Error: ${ex.message}")
        LogRepository.addLog(
            component = "RelayServer",
            event = "error",
            data = mapOf("message" to (ex.message ?: "Unknown error")),
            level = "ERROR",
            type = LogRepository.LogType.ERROR
        )
        ex.printStackTrace()
    }

    override fun onStart() {
        val msg = "Server started on port $port"
        Log.d("RelayServer", msg)
        LogRepository.addLog(
            component = "RelayServer",
            event = "server_start",
            data = mapOf("port" to port),
            level = "INFO",
            type = LogRepository.LogType.INFO
        )
    }
    
    fun broadcastImage(imageBytes: ByteArray) {
        // Broadcast raw bytes to authenticated clients
        broadcastToAuthenticated(imageBytes)
        // LogRepository.addLog("TX: Image Frame (${imageBytes.size} bytes)", LogRepository.LogType.OUTGOING) // Too spammy? Maybe log every 30 frames?
    }
    
    // Kept for legacy textual interface if needed (but we are moving to binary)
    fun broadcastToAuthenticated(message: String) {
        synchronized(authenticatedSessions) {
            for (client in authenticatedSessions) {
                if (client.isOpen) {
                    client.send(message)
                }
            }
        }
        
        // Log based on message type for better readability in logs
        when {
            message.contains("\"type\":\"heartbeat\"") -> {
                // Heartbeat is too spammy, log only that it was sent
                // LogRepository.addLog(component = "RelayServer", event = "heartbeat_sent", level = "DEBUG") 
            }
            message.contains("\"type\":\"state_update\"") -> {
                // Parse and log app state changes clearly
                try {
                    val json = org.json.JSONObject(message)
                    LogRepository.addLog(
                        component = "RelayServer",
                        event = "state_change_pushed",
                        data = mapOf("state" to json.optString("state")),
                        type = LogRepository.LogType.OUTGOING
                    )
                } catch (e: Exception) {
                    LogRepository.addLog(component = "RelayServer", event = "broadcast_message", data = mapOf("message" to message), type = LogRepository.LogType.OUTGOING)
                }
            }
            message.contains("\"engine_info\":") && message.contains("\"result\":") -> {
                // This is an OCR result, log a summary instead of the giant JSON
                try {
                    val json = org.json.JSONObject(message)
                    val summary = json.optJSONObject("summary")
                    LogRepository.addLog(
                        component = "RelayServer",
                        event = "ocr_result_pushed",
                        data = mapOf(
                            "text_count" to (summary?.optInt("text_object_count") ?: 0),
                            "latency_ms" to (summary?.optInt("total_latency_ms") ?: 0),
                            "full_text_preview" to (json.optJSONObject("result")?.optString("full_text")?.take(50) ?: "")
                        ),
                        type = LogRepository.LogType.OUTGOING
                    )
                } catch (e: Exception) {
                    LogRepository.addLog(component = "RelayServer", event = "broadcast_message", data = mapOf("message" to message), type = LogRepository.LogType.OUTGOING)
                }
            }
            else -> {
                // General broadcast message
                LogRepository.addLog(
                    component = "RelayServer",
                    event = "broadcast_message",
                    data = mapOf("message" to message),
                    type = LogRepository.LogType.OUTGOING
                )
            }
        }
    }

    fun broadcastToAuthenticated(data: ByteArray) {
        synchronized(authenticatedSessions) {
            for (client in authenticatedSessions) {
                if (client.isOpen) {
                    client.send(data)
                }
            }
        }
    }
}
