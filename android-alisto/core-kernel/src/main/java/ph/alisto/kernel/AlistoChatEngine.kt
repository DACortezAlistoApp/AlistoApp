package ph.alisto.kernel

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Decoupled real-time chat engine for active bookings.
 * Uses a Supabase Realtime WebSocket stream on public.booking_messages paired with an
 * in-flight-guarded incremental catch-up fetch (p_since) so requests never overlap or clobber UI state.
 */
object AlistoChatEngine {
    private const val TAG = "AlistoChatEngine"
    const val CHANNEL_CHAT_ID = "alisto_live_chat_messages_v1"
    const val KEY_TEXT_REPLY = "key_alisto_chat_inline_reply"
    const val EXTRA_BOOKING_ID = "alisto_chat_booking_id"
    const val EXTRA_BOOKING_REF = "alisto_chat_booking_ref"
    const val EXTRA_SENDER_ID = "alisto_chat_sender_id"
    const val EXTRA_SENDER_NAME = "alisto_chat_sender_name"
    const val EXTRA_COUNTERPARTY = "alisto_chat_counterparty"
    const val EXTRA_OPEN_CHAT_BOOKING_ID = "open_chat_booking_id"

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())
    private val wsClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val refSeq = AtomicInteger(1)
    private val syncInFlight = ConcurrentHashMap<String, AtomicBoolean>()
    private val lastSeenRawTs = ConcurrentHashMap<String, String>()
    private val lastNotifiedMsgId = ConcurrentHashMap<String, String>()
    private val listeners = ConcurrentHashMap<String, MutableSet<(List<ChatMessage>) -> Unit>>()

    @Volatile var targetActivityClassName: String? = null
    @Volatile var openChatBookingId: String? = null

    private var activeBookingId: String? = null
    private var activeBookingRef: String = ""
    private var activeUserId: String = ""
    private var activeUserName: String = "You"
    private var activeCounterparty: String = "Chat"
    private var appContextRef: Context? = null
    private var onIncomingChime: ((ChatMessage) -> Unit)? = null

    private var webSocket: WebSocket? = null
    private var wsHeartbeatJob: Job? = null
    private var safetyCatchUpJob: Job? = null
    private var reconnectJob: Job? = null

    fun registerHost(
        context: Context,
        activityClassName: String,
        onChime: ((ChatMessage) -> Unit)? = null
    ) {
        appContextRef = context.applicationContext
        targetActivityClassName = activityClassName
        onIncomingChime = onChime
        ensureChatChannel(context.applicationContext)
    }

    fun addTranscriptListener(bookingId: String, listener: (List<ChatMessage>) -> Unit) {
        listeners.getOrPut(bookingId) { ConcurrentHashMap.newKeySet() }.add(listener)
    }

    fun removeTranscriptListener(bookingId: String, listener: (List<ChatMessage>) -> Unit) {
        listeners[bookingId]?.remove(listener)
    }

    fun notifyLocalListeners(bookingId: String) {
        val snapshot = synchronized(AlistoKernelClient.chatTranscripts) {
            AlistoKernelClient.chatTranscripts[bookingId]?.toList().orEmpty()
        }
        listeners[bookingId]?.forEach { cb ->
            mainHandler.post { cb(snapshot) }
        }
    }

    fun watchActiveBooking(
        context: Context,
        bookingId: String,
        bookingRef: String,
        userId: String,
        userName: String,
        counterpartyName: String
    ) {
        appContextRef = context.applicationContext
        ensureChatChannel(context.applicationContext)
        activeBookingRef = bookingRef
        activeUserId = userId
        activeUserName = userName
        activeCounterparty = counterpartyName

        if (activeBookingId == bookingId && webSocket != null) {
            triggerCatchUp(bookingId, forceFull = false)
            return
        }

        stopWatching()
        activeBookingId = bookingId
        connectRealtimeSocket(bookingId)
        triggerCatchUp(bookingId, forceFull = true)
        startSafetyCatchUp(bookingId)
    }

    fun stopWatching() {
        activeBookingId = null
        wsHeartbeatJob?.cancel()
        safetyCatchUpJob?.cancel()
        reconnectJob?.cancel()
        try { webSocket?.close(1000, "Switching active chat") } catch (_: Exception) {}
        webSocket = null
    }

    fun triggerCatchUp(
        bookingId: String,
        forceFull: Boolean = false,
        onComplete: ((Result<List<ChatMessage>>) -> Unit)? = null
    ) {
        val guard = syncInFlight.getOrPut(bookingId) { AtomicBoolean(false) }
        if (!guard.compareAndSet(false, true)) return

        scope.launch {
            try {
                val since = if (forceFull) null else lastSeenRawTs[bookingId]
                val res = AlistoKernelClient.fetchBookingMessagesRpc(bookingId, limit = 60, sinceIso = since)
                res.onSuccess { msgs ->
                    val latest = msgs.lastOrNull()
                    if (latest != null) {
                        if (latest.rawSentAt.isNotBlank()) {
                            lastSeenRawTs[bookingId] = latest.rawSentAt
                        }
                        handlePossibleNewIncoming(bookingId, latest, msgs)
                    }
                    notifyLocalListeners(bookingId)
                }.onFailure { err ->
                    Log.w(TAG, "Chat catch-up transient error for $bookingId: ${err.message}")
                }
                if (onComplete != null) {
                    mainHandler.post { onComplete(res) }
                }
            } finally {
                guard.set(false)
            }
        }
    }

    private fun handlePossibleNewIncoming(bookingId: String, latest: ChatMessage, allMsgs: List<ChatMessage>) {
        if (latest.id.isBlank()) return
        val prev = lastNotifiedMsgId.put(bookingId, latest.id)
        if (prev == null || prev == latest.id) return
        if (latest.senderId.isBlank() || latest.senderId == activeUserId) return

        mainHandler.post { onIncomingChime?.invoke(latest) }
        if (openChatBookingId != bookingId) {
            val ctx = appContextRef ?: return
            showIncomingChatNotification(
                context = ctx,
                bookingId = bookingId,
                bookingRef = activeBookingRef,
                counterpartyName = latest.senderName.ifBlank { activeCounterparty },
                myUserId = activeUserId,
                myUserName = activeUserName,
                recentMessages = allMsgs.takeLast(5)
            )
        }
    }

    private fun startSafetyCatchUp(bookingId: String) {
        safetyCatchUpJob?.cancel()
        safetyCatchUpJob = scope.launch {
            while (isActive && activeBookingId == bookingId) {
                delay(12_000L)
                if (activeBookingId == bookingId) {
                    triggerCatchUp(bookingId, forceFull = false)
                }
            }
        }
    }

    private fun connectRealtimeSocket(bookingId: String) {
        val base = AlistoKernelClient.supabaseUrl.trimEnd('/')
        val key = AlistoKernelClient.supabaseKey.trim()
        if (base.isBlank() || key.isBlank()) return
        val wsUrl = base.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") +
            "/realtime/v1/websocket?apikey=$key&vsn=1.0.0"

        val req = Request.Builder().url(wsUrl).build()
        val topic = "realtime:public:booking_messages:$bookingId"

        webSocket = wsClient.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                val joinPayload = JSONObject().apply {
                    put("topic", topic)
                    put("event", "phx_join")
                    put("ref", refSeq.getAndIncrement().toString())
                    put("payload", JSONObject().apply {
                        put("config", JSONObject().apply {
                            put("postgres_changes", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("event", "INSERT")
                                    put("schema", "public")
                                    put("table", "booking_messages")
                                    put("filter", "booking_id=eq.$bookingId")
                                })
                            })
                        })
                    })
                }
                ws.send(joinPayload.toString())

                wsHeartbeatJob?.cancel()
                wsHeartbeatJob = scope.launch {
                    while (isActive && activeBookingId == bookingId) {
                        delay(25_000L)
                        val hb = JSONObject().apply {
                            put("topic", "phoenix")
                            put("event", "heartbeat")
                            put("ref", refSeq.getAndIncrement().toString())
                            put("payload", JSONObject())
                        }
                        try { ws.send(hb.toString()) } catch (_: Exception) { break }
                    }
                }
                triggerCatchUp(bookingId, forceFull = false)
            }

            override fun onMessage(ws: WebSocket, text: String) {
                try {
                    val msg = JSONObject(text)
                    val ev = msg.optString("event")
                    if (ev == "postgres_changes" || ev == "INSERT") {
                        triggerCatchUp(bookingId, forceFull = false)
                    }
                } catch (_: Exception) {}
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "Realtime WS dropped: ${t.message}")
                scheduleReconnect(bookingId)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                if (activeBookingId == bookingId) {
                    scheduleReconnect(bookingId)
                }
            }
        })
    }

    private fun scheduleReconnect(bookingId: String) {
        if (activeBookingId != bookingId) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(4_000L)
            if (activeBookingId == bookingId) {
                connectRealtimeSocket(bookingId)
            }
        }
    }

    fun ensureChatChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_CHAT_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_CHAT_ID, "Alisto In-App Chat Messages", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Heads-up alerts with Open and quick Reply for active booking chats."
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 140, 80, 140)
                }
            )
        }
    }

    fun clearChatNotification(context: Context, bookingId: String) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(bookingId.hashCode())
        } catch (_: Exception) {}
    }

    fun showIncomingChatNotification(
        context: Context,
        bookingId: String,
        bookingRef: String,
        counterpartyName: String,
        myUserId: String,
        myUserName: String,
        recentMessages: List<ChatMessage>
    ) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ensureChatChannel(context)

        val notifId = bookingId.hashCode()
        val mePerson = Person.Builder().setName(myUserName.ifBlank { "You" }).build()
        val style = NotificationCompat.MessagingStyle(mePerson)
            .setConversationTitle("Chat • $counterpartyName ($bookingRef)")

        recentMessages.forEach { m ->
            val senderPerson = if (m.senderId == myUserId) mePerson else Person.Builder().setName(m.senderName.ifBlank { counterpartyName }).build()
            val tsMs = AlistoKernelClient.parseServerInstant(m.rawSentAt)?.toEpochMilli() ?: System.currentTimeMillis()
            style.addMessage(m.body, tsMs, senderPerson)
        }

        // 1. Open Chat PendingIntent
        val openIntent = Intent().apply {
            val cls = targetActivityClassName
            if (!cls.isNullOrBlank()) {
                setClassName(context.packageName, cls)
            } else {
                context.packageManager.getLaunchIntentForPackage(context.packageName)?.component?.let { component = it }
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(EXTRA_OPEN_CHAT_BOOKING_ID, bookingId)
        }
        val openPi = PendingIntent.getActivity(
            context,
            notifId and 0x7FFFFFFF,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 2. Inline Reply Action (Explicit Intent required for FLAG_MUTABLE on Android 14-16)
        val replyIntent = Intent(context, AlistoChatReplyReceiver::class.java).apply {
            putExtra(EXTRA_BOOKING_ID, bookingId)
            putExtra(EXTRA_BOOKING_REF, bookingRef)
            putExtra(EXTRA_SENDER_ID, myUserId)
            putExtra(EXTRA_SENDER_NAME, myUserName)
            putExtra(EXTRA_COUNTERPARTY, counterpartyName)
        }
        val mutableFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val replyPi = PendingIntent.getBroadcast(
            context,
            (notifId + 1) and 0x7FFFFFFF,
            replyIntent,
            mutableFlags
        )
        val remoteInput = RemoteInput.Builder(KEY_TEXT_REPLY)
            .setLabel("Reply to $counterpartyName...")
            .build()
        val replyAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send,
            "Reply",
            replyPi
        ).addRemoteInput(remoteInput).setAllowGeneratedReplies(true).build()

        val openAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_view,
            "Open",
            openPi
        ).build()

        val iconRes = context.applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.stat_notify_chat
        val notif = NotificationCompat.Builder(context, CHANNEL_CHAT_ID)
            .setSmallIcon(iconRes)
            .setStyle(style)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(openPi)
            .addAction(replyAction)
            .addAction(openAction)
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(notifId, notif)
    }
}
