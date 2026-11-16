package io.github.u1e2k.concbot.trigger.nfc

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.nfc.NfcAdapter
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import io.github.u1e2k.concbot.dispatcher.ActionDispatcher
import io.github.u1e2k.concbot.util.NdefHelper

/**
 * NFCタグの検知（ACTION_NDEF_DISCOVERED）によって起動する透明Activity (Theme.NoDisplay)。
 * URIスキーマ (concbot://action/{action_id}) を解析し、ActionDispatcher に渡して即座に終了します。
 */
class NfcTriggerActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            handleNfcIntent(intent)
        } catch (t: Throwable) {
            Log.e(TAG, "Error handling NFC intent", t)
        } finally {
            finish()
        }
    }

    private fun handleNfcIntent(intent: Intent?) {
        if (intent == null || intent.action != NfcAdapter.ACTION_NDEF_DISCOVERED) return

        // デバウンス制御: 規定時間以内の連続タッチを無視
        val now = SystemClock.elapsedRealtime()
        if (now - lastTriggerTimeMs < DEBOUNCE_INTERVAL_MS) {
            Log.d(TAG, "Debounced NFC trigger")
            return
        }

        val uri: Uri = intent.data ?: NdefHelper.extractUriFromIntent(intent) ?: return
        Log.d(TAG, "Trigger URI: $uri")

        if (uri.scheme == SCHEME_CONCBOT && uri.host == HOST_ACTION) {
            val actionId = uri.pathSegments.firstOrNull()
            if (!actionId.isNullOrEmpty()) {
                lastTriggerTimeMs = now
                ActionDispatcher.dispatch(actionId, applicationContext)
            }
        }
    }

    companion object {
        private const val TAG = "NfcTriggerActivity"
        private const val SCHEME_CONCBOT = "concbot"
        private const val HOST_ACTION = "action"
        private const val DEBOUNCE_INTERVAL_MS = 3000L

        @Volatile
        private var lastTriggerTimeMs = 0L
    }
}
