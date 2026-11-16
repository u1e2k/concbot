package io.github.u1e2k.concbot.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import io.github.u1e2k.concbot.R
import io.github.u1e2k.concbot.action.impl.TetherToggleAction
import io.github.u1e2k.concbot.databinding.ActivityMainBinding
import io.github.u1e2k.concbot.dispatcher.ActionDispatcher
import io.github.u1e2k.concbot.service.ConcAccessibilityService

/**
 * ConcBot のステータス確認・手動テスト・NFCタグ書き込み画面。
 */
class MainActivity : AppCompatActivity(), NfcAdapter.ReaderCallback {

    private lateinit var binding: ActivityMainBinding
    private var nfcAdapter: NfcAdapter? = null
    private var isNfcWritingMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        updateServiceStatus()

        if (isNfcWritingMode) {
            enableNfcReaderMode()
        }
    }

    override fun onPause() {
        super.onPause()
        disableNfcReaderMode()
    }

    private fun setupListeners() {
        // ユーザー補助設定ボタン
        binding.btnOpenAccessibilitySettings.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        }

        // 手動テスト実行ボタン
        binding.btnTestTetherToggle.setOnClickListener {
            val success = ActionDispatcher.dispatch(TetherToggleAction.ACTION_ID, this)
            if (success) {
                Toast.makeText(this, "テザリング切替を開始しました", Toast.LENGTH_SHORT).show()
            }
        }

        // NFC書き込みモード切替ボタン
        binding.btnToggleNfcWriteMode.setOnClickListener {
            toggleNfcWriteMode()
        }
    }

    private fun updateServiceStatus() {
        val isServiceRunning = ConcAccessibilityService.instance != null
        val isServiceEnabled = ConcAccessibilityService.isEnabled(this)

        if (isServiceRunning || isServiceEnabled) {
            binding.tvServiceStatus.text = getString(R.string.status_service_running)
            binding.tvServiceStatus.setTextColor(ContextCompat.getColor(this, R.color.status_green))
        } else {
            binding.tvServiceStatus.text = getString(R.string.status_service_disabled)
            binding.tvServiceStatus.setTextColor(ContextCompat.getColor(this, R.color.status_red))
        }
    }

    private fun toggleNfcWriteMode() {
        if (nfcAdapter == null) {
            Toast.makeText(this, R.string.nfc_not_supported, Toast.LENGTH_SHORT).show()
            return
        }
        if (!nfcAdapter!!.isEnabled) {
            Toast.makeText(this, R.string.nfc_disabled, Toast.LENGTH_SHORT).show()
            startActivity(Intent(Settings.ACTION_NFC_SETTINGS))
            return
        }

        isNfcWritingMode = !isNfcWritingMode

        if (isNfcWritingMode) {
            binding.btnToggleNfcWriteMode.text = getString(R.string.btn_stop_nfc_write)
            binding.tvNfcStatus.text = getString(R.string.nfc_waiting_message)
            binding.tvNfcStatus.visibility = View.VISIBLE
            enableNfcReaderMode()
            Toast.makeText(this, "NFCタグをかざしてください", Toast.LENGTH_SHORT).show()
        } else {
            binding.btnToggleNfcWriteMode.text = getString(R.string.btn_start_nfc_write)
            binding.tvNfcStatus.visibility = View.GONE
            disableNfcReaderMode()
        }
    }

    private fun enableNfcReaderMode() {
        val adapter = nfcAdapter ?: return
        val flags = NfcAdapter.FLAG_READER_NFC_A or
                NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_NFC_F or
                NfcAdapter.FLAG_READER_NFC_V or
                NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK

        adapter.enableReaderMode(this, this, flags, null)
    }

    private fun disableNfcReaderMode() {
        nfcAdapter?.disableReaderMode(this)
    }

    /**
     * NFCタグが検出されたときのコールバック（バックグラウンドスレッドで実行）
     */
    override fun onTagDiscovered(tag: Tag?) {
        if (!isNfcWritingMode || tag == null) return

        val targetUri = "concbot://action/${TetherToggleAction.ACTION_ID}"
        val record = NdefRecord.createUri(Uri.parse(targetUri))
        val message = NdefMessage(arrayOf(record))

        val success = writeNdefMessage(tag, message)

        runOnUiThread {
            if (success) {
                triggerSuccessHaptic()
                Toast.makeText(this, R.string.nfc_write_success, Toast.LENGTH_LONG).show()
                binding.tvNfcStatus.text = "書き込み完了: $targetUri"
                // 1回書き込んだらモードを終了
                toggleNfcWriteMode()
            } else {
                Toast.makeText(this, getString(R.string.nfc_write_failed, "書き込みに失敗しました"), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun writeNdefMessage(tag: Tag, message: NdefMessage): Boolean {
        val ndef = Ndef.get(tag)
        if (ndef != null) {
            try {
                ndef.connect()
                if (!ndef.isWritable) return false
                if (ndef.maxSize < message.byteArrayLength) return false
                ndef.writeNdefMessage(message)
                return true
            } catch (t: Throwable) {
                t.printStackTrace()
                return false
            } finally {
                try { ndef.close() } catch (_: Throwable) {}
            }
        } else {
            val formatable = NdefFormatable.get(tag)
            if (formatable != null) {
                try {
                    formatable.connect()
                    formatable.format(message)
                    return true
                } catch (t: Throwable) {
                    t.printStackTrace()
                    return false
                } finally {
                    try { formatable.close() } catch (_: Throwable) {}
                }
            }
        }
        return false
    }

    private fun triggerSuccessHaptic() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            vibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
        } catch (_: Throwable) {}
    }
}
