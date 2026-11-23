package ph.alisto.contractor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class ContractorOfferActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val bid = intent.getStringExtra("booking_id").orEmpty()
        val decision = intent.getStringExtra("decision").orEmpty()
        if (bid.isBlank() || decision.isBlank()) return

        val svcIntent = Intent(context, ContractorDispatchMonitorService::class.java).apply {
            action = ContractorDispatchMonitorService.ACTION_OFFER_DECISION
            putExtra("booking_id", bid)
            putExtra("decision", decision)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(svcIntent)
            } else {
                context.startService(svcIntent)
            }
        } catch (_: Exception) {
            try { context.startService(svcIntent) } catch (_: Exception) {}
        }
    }
}
