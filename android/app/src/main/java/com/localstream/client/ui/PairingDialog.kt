package com.localstream.client.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDialog
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.localstream.client.R
import com.localstream.client.databinding.ScreenPairBinding

/**
 * Full-screen first-time pairing step: the target PC, six PIN boxes backed by one hidden
 * numeric field, and the two recovery paths (fetch a fresh PIN, switch to a manual IP).
 * Protocol decisions stay in [MainActivity]; this class only renders and reports what the
 * user asked for through the callbacks. Back / the toolbar arrow cancel the pairing.
 */
class PairingDialog(
    context: Context,
    serverName: String,
    serverIp: String,
    private val onSubmit: (String) -> Unit,
    private val onCancelPairing: () -> Unit,
    private val onRequestNewPin: () -> Unit,
    private val onManualIp: () -> Unit,
) : AppCompatDialog(context, R.style.Theme_LocalStream_Light_PairScreen) {

    private val binding = ScreenPairBinding.inflate(LayoutInflater.from(this.context))
    private val boxes: List<TextView> = with(binding) { listOf(pin1, pin2, pin3, pin4, pin5, pin6) }

    /** A PIN is on its way to the server; locks the button until PAIR_OK / PAIR_FAIL. */
    private var verifying = false
    /** The old session was dropped to make the server issue a new PIN; cleared when the
     * new session reaches PAIR_REQUIRED again. */
    private var awaitingNewPin = false
    private var errorShown = false

    init {
        setContentView(binding.root)
        setCancelable(true)
        setCanceledOnTouchOutside(false)
        setOnCancelListener { onCancelPairing() }

        binding.tvPairServerName.text = serverName
        binding.tvPairServerIp.text = serverIp
        binding.btnBack.setOnClickListener { cancel() }
        binding.btnHelp.setOnClickListener { showHelp() }
        binding.btnPairConnect.setOnClickListener { submit() }
        binding.btnRequestPin.setOnClickListener { requestNewPin() }
        binding.btnPairManual.setOnClickListener { onManualIp() }

        binding.etPin.doAfterTextChanged { text ->
            // Typing again after a wrong PIN starts a fresh attempt.
            if (errorShown && !text.isNullOrEmpty()) {
                errorShown = false
                setMessage(R.string.pair_note)
            }
            render()
        }
        binding.etPin.setOnFocusChangeListener { _, _ -> render() }
        binding.etPin.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submit()
                true
            } else {
                false
            }
        }
        render()
    }

    override fun onStart() {
        super.onStart()
        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
        )
        focusPinField()
    }

    /** The server rejected the PIN but allows another try. */
    fun showWrongPin(attemptsLeft: Int) {
        verifying = false
        binding.etPin.text?.clear()
        errorShown = true
        setMessage(context.getString(R.string.pair_error_wrong, attemptsLeft), isError = true)
        render()
        focusPinField()
    }

    /** The (re)connected session is waiting for a PIN again, so a fresh one is on the PC. */
    fun onPinIssued() {
        if (!awaitingNewPin) return
        awaitingNewPin = false
        verifying = false
        errorShown = false
        binding.etPin.text?.clear()
        setMessage(R.string.pair_new_pin)
        render()
        focusPinField()
    }

    private fun submit() {
        if (verifying || awaitingNewPin) return
        val pin = binding.etPin.text?.toString().orEmpty()
        if (pin.length != PIN_LENGTH || !pin.all(Char::isDigit)) {
            errorShown = true
            setMessage(context.getString(R.string.pair_error_length), isError = true)
            render()
            return
        }
        verifying = true
        setMessage(R.string.pair_verifying)
        render()
        onSubmit(pin)
    }

    private fun requestNewPin() {
        if (awaitingNewPin) return
        awaitingNewPin = true
        verifying = false
        errorShown = false
        binding.etPin.text?.clear()
        setMessage(R.string.pair_requesting)
        render()
        onRequestNewPin()
    }

    private fun render() {
        val pin = binding.etPin.text?.toString().orEmpty()
        val cursor = pin.length.coerceAtMost(PIN_LENGTH - 1)
        val focused = binding.etPin.hasFocus()
        boxes.forEachIndexed { i, box ->
            box.text = pin.getOrNull(i)?.toString().orEmpty()
            box.setBackgroundResource(
                when {
                    errorShown -> R.drawable.bg_pin_box_error
                    focused && i == cursor -> R.drawable.bg_pin_box_active
                    else -> R.drawable.bg_pin_box
                }
            )
        }
        binding.btnPairConnect.isEnabled = pin.length == PIN_LENGTH && !verifying && !awaitingNewPin
        binding.btnRequestPin.isEnabled = !awaitingNewPin
        binding.etPin.isEnabled = !awaitingNewPin
    }

    private fun setMessage(@StringRes res: Int) = setMessage(context.getString(res), isError = false)

    private fun setMessage(text: String, isError: Boolean) {
        binding.tvPinMessage.text = text
        binding.tvPinMessage.setTextColor(
            ContextCompat.getColor(context, if (isError) R.color.ls_error else R.color.ls_text_secondary)
        )
    }

    private fun focusPinField() {
        val field = binding.etPin
        if (!field.isEnabled) return
        field.requestFocus()
        field.post {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun showHelp() {
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.pair_help_title)
            .setMessage(R.string.pair_help_body)
            .setPositiveButton(R.string.action_ok, null)
            .show()
    }

    private companion object {
        const val PIN_LENGTH = 6
    }
}
