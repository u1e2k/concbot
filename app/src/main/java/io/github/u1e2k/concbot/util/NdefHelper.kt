package io.github.u1e2k.concbot.util

import android.content.Intent
import android.net.Uri
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.MifareUltralight
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.os.Build
import android.os.Parcelable
import android.util.Log

/**
 * NFC (NDEF) 関連の処理（メッセージ生成・書き込み・抽出・診断）を統括するヘルパー。
 */
object NdefHelper {

    private const val TAG = "NdefHelper"

    sealed class WriteResult {
        object Success : WriteResult()
        data class Error(val message: String) : WriteResult()
    }

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
    fun writeMessage(tag: Tag, message: NdefMessage): WriteResult {
        val techList = tag.techList.map { it.substringAfterLast('.') }
        Log.i(TAG, "Discovered Tag TechList: $techList")

        val ndef = Ndef.get(tag)
        if (ndef != null) {
            try {
                ndef.connect()
                if (!ndef.isWritable) {
                    val msg = "タグが書き込み保護（読み取り専用）されています"
                    Log.w(TAG, msg)
                    return WriteResult.Error(msg)
                }
                if (ndef.maxSize < message.byteArrayLength) {
                    val msg = "タグの容量不足です (最大: ${ndef.maxSize}B, 必要: ${message.byteArrayLength}B)"
                    Log.w(TAG, msg)
                    return WriteResult.Error(msg)
                }
                ndef.writeNdefMessage(message)
                Log.i(TAG, "Successfully wrote NDEF message via Ndef")
                return WriteResult.Success
            } catch (t: Throwable) {
                Log.e(TAG, "Error writing via Ndef", t)
                return WriteResult.Error("書き込みエラー: ${t.localizedMessage ?: t.javaClass.simpleName}")
            } finally {
                try { ndef.close() } catch (_: Throwable) {}
            }
        }

        val formatable = NdefFormatable.get(tag)
        if (formatable != null) {
            try {
                formatable.connect()
                formatable.format(message)
                Log.i(TAG, "Successfully formatted and wrote NDEF message via NdefFormatable")
                return WriteResult.Success
            } catch (t: Throwable) {
                Log.e(TAG, "Error formatting via NdefFormatable", t)
                return WriteResult.Error("フォーマット失敗: ${t.localizedMessage ?: t.javaClass.simpleName}")
            } finally {
                try { formatable.close() } catch (_: Throwable) {}
            }
        }

        // NDEF非対応タグの場合（SuicaなどのFeliCaやMifare Classicなど）
        val detailedTech = techList.joinToString(", ")
        val errorReason = when {
            detailedTech.contains("NfcF", ignoreCase = true) -> "交通系IC/電子マネー等のFeliCaタグには書き込めません (対応: NTAG213/215等)"
            detailedTech.contains("MifareClassic", ignoreCase = true) -> "Mifare Classic タグは非対応です (NTAG系をご使用ください)"
            else -> "NDEF規格非対応のタグです (検出: $detailedTech)"
        }
        Log.w(TAG, errorReason)
        return WriteResult.Error(errorReason)
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
