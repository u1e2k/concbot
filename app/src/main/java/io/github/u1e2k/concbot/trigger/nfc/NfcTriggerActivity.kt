package io.github.u1e2k.concbot.trigger.nfc

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.os.SystemClock
import android.util.Log
import io.github.u1e2k.concbot.dispatcher.ActionDispatcher

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
            // 画面を表示しないため即座に終了
            finish()
        }
    }

    private fun handleNfcIntent(intent: Intent?) {
        if (intent == null) return

        val action = intent.action
        if (NfcAdapter.ACTION_NDEF_DISCOVERED != action) {
            Log.w(TAG, "Unexpected action: $action")
            return
        }

        // デバウンス制御: 直近のトリガーから規定時間以内の場合はスキップ
        val now = SystemClock.elapsedRealtime()
        if (now - lastTriggerTimeMs < DEBOUNCE_INTERVAL_MS) {
            Log.d(TAG, "Debounce: Ignored consecutive NFC trigger within ${DEBOUNCE_INTERVAL_MS}ms")
            return
        }

        // IntentのData URIまたはNDEFメッセージレコードからURIを取得
        val uri: Uri? = intent.data ?: extractUriFromNdef(intent)
        if (uri == null) {
            Log.w(TAG, "No URI found in NFC Intent")
            return
        }

        Log.d(TAG, "Received NFC trigger URI: $uri")

        // スキーマとホストの検証: concbot://action/{action_id}
        if (uri.scheme == SCHEME_CONCBOT && uri.host == HOST_ACTION) {
            val pathSegments = uri.pathSegments
            val actionId = if (pathSegments.isNotEmpty()) pathSegments[0] else null

            if (!actionId.isNullOrEmpty()) {
                lastTriggerTimeMs = now
                ActionDispatcher.dispatch(actionId, applicationContext)
            } else {
                Log.w(TAG, "Action ID is empty in URI path: $uri")
            }
        } else {
            Log.w(TAG, "Unrecognized URI pattern: $uri")
        }
    }

    private fun extractUriFromNdef(intent: Intent): Uri? {
        val rawMessages: Array<out Parcelable>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES, Parcelable::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)
        }
        if (rawMessages == null) return null

        for (raw in rawMessages) {
            val msg = raw as? NdefMessage ?: continue
            for (record in msg.records) {
                val recordUri = record.toUri()
                if (recordUri != null) {
                    return recordUri
                }
            }
        }
        return null
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
