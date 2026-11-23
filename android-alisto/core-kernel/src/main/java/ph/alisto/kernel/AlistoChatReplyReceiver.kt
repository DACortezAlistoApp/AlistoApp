package ph.alisto.kernel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AlistoChatReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val replyText = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(AlistoChatEngine.KEY_TEXT_REPLY)
            ?.toString()
            ?.trim()
            .orEmpty()
        val bookingId = intent.getStringExtra(AlistoChatEngine.EXTRA_BOOKING_ID).orEmpty()
        val bookingRef = intent.getStringExtra(AlistoChatEngine.EXTRA_BOOKING_REF).orEmpty()
        val senderId = intent.getStringExtra(AlistoChatEngine.EXTRA_SENDER_ID).orEmpty()
        val senderName = intent.getStringExtra(AlistoChatEngine.EXTRA_SENDER_NAME).orEmpty().ifBlank { "You" }
        val counterparty = intent.getStringExtra(AlistoChatEngine.EXTRA_COUNTERPARTY).orEmpty().ifBlank { "Chat" }

        if (replyText.isBlank() || bookingId.isBlank() || senderId.isBlank()) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val res = AlistoKernelClient.sendBookingMessageDirectRpc(bookingId, senderId, senderName, replyText)
                res.onSuccess {
                    AlistoChatEngine.notifyLocalListeners(bookingId)
                    val msgs = synchronized(AlistoKernelClient.chatTranscripts) {
                        AlistoKernelClient.chatTranscripts[bookingId]?.takeLast(5).orEmpty()
                    }
                    AlistoChatEngine.showIncomingChatNotification(
                        context = context.applicationContext,
                        bookingId = bookingId,
                        bookingRef = bookingRef,
                        counterpartyName = counterparty,
                        myUserId = senderId,
                        myUserName = senderName,
                        recentMessages = msgs
                    )
                }.onFailure {
                    AlistoChatEngine.clearChatNotification(context.applicationContext, bookingId)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
