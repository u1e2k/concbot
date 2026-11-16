package io.github.u1e2k.concbot.ui

import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
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
import io.github.u1e2k.concbot.util.HapticHelper
import io.github.u1e2k.concbot.util.NdefHelper

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
        if (isNfcWritingMode) enableNfcReaderMode()
    }

    override fun onPause() {
        super.onPause()
        disableNfcReaderMode()
    }

    private fun setupListeners() {
        binding.btnOpenAccessibilitySettings.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        binding.btnTestTetherToggle.setOnClickListener {
            if (ActionDispatcher.dispatch(TetherToggleAction.ACTION_ID, this)) {
                Toast.makeText(this, "テザリング切替を開始しました", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnToggleNfcWriteMode.setOnClickListener {
            toggleNfcWriteMode()
        }
    }

    private fun updateServiceStatus() {
        val active = ConcAccessibilityService.instance != null || ConcAccessibilityService.isEnabled(this)
        val textRes = if (active) R.string.status_service_running else R.string.status_service_disabled
        val colorRes = if (active) R.color.status_green else R.color.status_red

        binding.tvServiceStatus.text = getString(textRes)
        binding.tvServiceStatus.setTextColor(ContextCompat.getColor(this, colorRes))
    }

    private fun toggleNfcWriteMode() {
        val adapter = nfcAdapter ?: run {
            Toast.makeText(this, R.string.nfc_not_supported, Toast.LENGTH_SHORT).show()
            return
        }
        if (!adapter.isEnabled) {
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
        nfcAdapter?.enableReaderMode(
            this,
            this,
            NfcAdapter.FLAG_READER_NFC_A or
                    NfcAdapter.FLAG_READER_NFC_B or
                    NfcAdapter.FLAG_READER_NFC_F or
                    NfcAdapter.FLAG_READER_NFC_V or
                    NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
            null
        )
    }

    private fun disableNfcReaderMode() {
        nfcAdapter?.disableReaderMode(this)
    }

    override fun onTagDiscovered(tag: Tag?) {
        if (!isNfcWritingMode || tag == null) return

        val targetUri = "concbot://action/${TetherToggleAction.ACTION_ID}"
        val message = NdefHelper.createUriMessage(targetUri)
        val result = NdefHelper.writeMessage(tag, message)

        runOnUiThread {
            when (result) {
                is NdefHelper.WriteResult.Success -> {
                    HapticHelper.performClickFeedback(this)
                    Toast.makeText(this, R.string.nfc_write_success, Toast.LENGTH_LONG).show()
                    binding.tvNfcStatus.text = "書き込み完了: $targetUri"
                    toggleNfcWriteMode()
                }
                is NdefHelper.WriteResult.Error -> {
                    binding.tvNfcStatus.text = "エラー: ${result.message}"
                    Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
