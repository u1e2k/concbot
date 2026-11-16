package io.github.u1e2k.concbot.util

import android.content.Intent
import android.net.Uri
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.os.Build
import android.os.Parcelable
import android.util.Log

/**
 * NFC (NDEF) 関連の処理（メッセージ生成・書き込み・抽出）を統括するヘルパー。
 */
object NdefHelper {

    private const val TAG = "NdefHelper"

    /**
     * 指定された URI を持つ NdefMessage を生成
     */
    fun createUriMessage(uriString: String): NdefMessage {
        val record = NdefRecord.createUri(Uri.parse(uriString))
        return NdefMessage(arrayOf(record))
    }

    /**
     * NFC タグに NdefMessage を書き込む（Ndef または NdefFormatable に対応）
     */
    fun writeMessage(tag: Tag, message: NdefMessage): Boolean {
        val ndef = Ndef.get(tag)
        if (ndef != null) {
            try {
                ndef.connect()
                if (!ndef.isWritable) {
                    Log.w(TAG, "Tag is not writable")
                    return false
                }
                if (ndef.maxSize < message.byteArrayLength) {
                    Log.w(TAG, "Tag capacity too small (${ndef.maxSize} < ${message.byteArrayLength})")
                    return false
                }
                ndef.writeNdefMessage(message)
                return true
            } catch (t: Throwable) {
                Log.e(TAG, "Error writing NDEF message", t)
                return false
            } finally {
                try { ndef.close() } catch (_: Throwable) {}
            }
        }

        val formatable = NdefFormatable.get(tag)
        if (formatable != null) {
            try {
                formatable.connect()
                formatable.format(message)
                return true
            } catch (t: Throwable) {
                Log.e(TAG, "Error formatting and writing NDEF message", t)
                return false
            } finally {
                try { formatable.close() } catch (_: Throwable) {}
            }
        }

        Log.w(TAG, "Tag does not support NDEF or NdefFormatable")
        return false
    }

    /**
     * NFC Intent から NDEF URI を抽出
     */
    fun extractUriFromIntent(intent: Intent): Uri? {
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
}
