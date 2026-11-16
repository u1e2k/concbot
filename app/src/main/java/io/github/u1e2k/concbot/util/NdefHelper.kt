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
     * NFC タグに NdefMessage を書き込む
     * 1. Ndef インスタンスでの通常書き込み
     * 2. NdefFormatable でのフォーマット書き込み
     * 3. 未フォーマット NTAG (MifareUltralight) への直接低レベル書き込みフォールバック
     */
    fun writeMessage(tag: Tag, message: NdefMessage): WriteResult {
        val techList = tag.techList.map { it.substringAfterLast('.') }
        Log.i(TAG, "Discovered Tag TechList: $techList")

        // 1. Ndef 経由の書き込み
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

        // 2. NdefFormatable 経由のフォーマット書き込み
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

        // 3. MifareUltralight (NTAG213/215/216) 直接フォーマット・書き込みフォールバック
        val ultralight = MifareUltralight.get(tag)
        if (ultralight != null) {
            Log.i(TAG, "Attempting low-level write via MifareUltralight for NTAG...")
            val directResult = writeNtagDirect(ultralight, message)
            if (directResult is WriteResult.Success) {
                Log.i(TAG, "Successfully wrote NTAG directly via MifareUltralight")
                return directResult
            }
        }

        // 失敗時の診断情報
        val detailedTech = techList.joinToString(", ")
        val errorReason = when {
            detailedTech.contains("NfcF", ignoreCase = true) -> "交通系IC/電子マネー等のFeliCaタグには書き込めません"
            detailedTech.contains("MifareClassic", ignoreCase = true) -> "Mifare Classic タグは非対応です"
            else -> "NDEF規格非対応のタグです (検出: $detailedTech)"
        }
        Log.w(TAG, errorReason)
        return WriteResult.Error(errorReason)
    }

    /**
     * 未初期化の NTAG213/215/216 に対して Capability Container (CC) と NDEF TLV を直接書き込む
     */
    private fun writeNtagDirect(ultralight: MifareUltralight, message: NdefMessage): WriteResult {
        try {
            ultralight.connect()

            val ndefBytes = message.toByteArray()
            // TLVヘッダ: 0x03 (NDEF Message TLV), Length, [Payload], 0xFE (Terminator TLV)
            val tlvList = mutableListOf<Byte>()
            tlvList.add(0x03.toByte())
            if (ndefBytes.size < 0xFF) {
                tlvList.add(ndefBytes.size.toByte())
            } else {
                tlvList.add(0xFF.toByte())
                tlvList.add(((ndefBytes.size shr 8) and 0xFF).toByte())
                tlvList.add((ndefBytes.size and 0xFF).toByte())
            }
            for (b in ndefBytes) {
                tlvList.add(b)
            }
            tlvList.add(0xFE.toByte()) // Terminator

            // 4バイト（1ページ）単位にパディング
            while (tlvList.size % 4 != 0) {
                tlvList.add(0x00.toByte())
            }

            // Page 3: Capability Container (CC) を書き込む
            // E1 (Magic), 10 (Version 1.0), 12 (NTAG213: 144B = 18*8), 00 (Read/Write)
            // 既存のCCが有効なら上書きせずそのまま使う
            val existingCc = ultralight.readPages(3)
            if (existingCc[0] != 0xE1.toByte()) {
                val defaultCc = byteArrayOf(0xE1.toByte(), 0x10.toByte(), 0x12.toByte(), 0x00.toByte())
                ultralight.writePage(3, defaultCc)
            }

            // Page 4 以降に TLV データを 4 バイトずつ書き込む
            var page = 4
            for (i in tlvList.indices step 4) {
                val pageData = byteArrayOf(
                    tlvList[i],
                    tlvList[i + 1],
                    tlvList[i + 2],
                    tlvList[i + 3]
                )
                ultralight.writePage(page, pageData)
                page++
            }

            return WriteResult.Success
        } catch (t: Throwable) {
            Log.e(TAG, "Direct NTAG write failed", t)
            return WriteResult.Error("NTAG直接書き込みエラー: ${t.localizedMessage ?: t.javaClass.simpleName}")
        } finally {
            try { ultralight.close() } catch (_: Throwable) {}
        }
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
