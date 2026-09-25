package com.localstream.client.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.localstream.client.BuildConfig
import com.localstream.client.R
import com.localstream.client.data.Prefs
import com.localstream.client.databinding.ActivityMainBinding
import com.localstream.client.databinding.DialogManualIpBinding
import com.localstream.client.net.ControlClient
import com.localstream.client.net.DiscoveredServer
import com.localstream.client.net.DiscoveryClient
import com.localstream.client.proto.ServerMessage
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

/**
 * Discovery + connect screen. Broadcasts DSPROBE1 while visible, shows discovered servers,
 * and always offers manual IP entry as a fallback. Drives HELLO / pairing via
 * the process-wide [ControlClient] singleton and hands off to [StreamActivity] once the
 * session reaches HELLO_OK.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var discoveryClient: DiscoveryClient
    private lateinit var serverAdapter: ServerAdapter
    private val prefs: Prefs by lazy { Prefs(applicationContext) }

    private var pairDialog: PairingDialog? = null
    private var roleDialog: AlertDialog? = null
    private var manualDialog: AlertDialog? = null
    private var connectingServerName: String = ""

    /** True from the moment the user initiates a connect until we've navigated away (or the
     * attempt failed). Gates both duplicate connect taps and the READY-state navigation: the
     * control channel deliberately stays connected after StreamActivity finishes (state stays
     * READY), and without this gate the replayed READY state would instantly
     * relaunch StreamActivity in an endless bounce. */
    private var pendingConnect = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ControlClient.init(applicationContext)
        binding.tvVersion.text = "v${BuildConfig.VERSION_NAME}"
        discoveryClient = DiscoveryClient(applicationContext)

        serverAdapter = ServerAdapter(
            isPaired = { ip -> prefs.tokenForServer(ip).isNotEmpty() },
            onClick = { server -> onServerTapped(server) }
        )
        binding.rvServers.layoutManager = LinearLayoutManager(this)
        binding.rvServers.adapter = serverAdapter
        updateEmptyState()

        binding.btnRefresh.setOnClickListener {
            // Drop stale rows (a PC that went to sleep) and probe again from scratch.
            serverAdapter.clear()
            updateEmptyState()
            startDiscovery()
        }
        binding.cardManualConnect.setOnClickListener { showManualIpDialog() }

        observeControlClient()
    }

    override fun onStart() {
        super.onStart()
        if (!pendingConnect) showSearchingStatus()
        startDiscovery()
    }

    override fun onStop() {
        super.onStop()
        discoveryClient.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        discoveryClient.stop()
        roleDialog?.dismiss()
        roleDialog = null
        manualDialog?.dismiss()
        manualDialog = null
        dismissPairDialog()
    }

    private fun startDiscovery() {
        discoveryClient.start { server ->
            serverAdapter.upsert(server)
            updateEmptyState()
        }
    }

    private fun updateEmptyState() {
        binding.tvEmptyList.visibility = if (serverAdapter.isEmpty()) View.VISIBLE else View.GONE
    }

    /** Fills the status card. [busy] shows the spinner; [isError] tints the icon red. */
    private fun setStatus(@StringRes title: Int, body: CharSequence, busy: Boolean, isError: Boolean = false) {
        binding.tvStatusTitle.setText(title)
        binding.tvStatusBody.text = body
        binding.progressStatus.visibility = if (busy) View.VISIBLE else View.GONE
        binding.ivStatusIcon.setImageResource(if (isError) R.drawable.ic_error_outline else R.drawable.ic_search)
        ImageViewCompat.setImageTintList(
            binding.ivStatusIcon,
            ColorStateList.valueOf(ContextCompat.getColor(this, if (isError) R.color.ls_error else R.color.ls_blue))
        )
    }

    private fun showSearchingStatus() =
        setStatus(R.string.status_searching_title, getString(R.string.status_searching_body), busy = true)

    private fun showFailedStatus() =
        setStatus(R.string.status_failed_title, getString(R.string.status_failed_body), busy = false, isError = true)

    private fun setConnectControlsEnabled(enabled: Boolean) {
        binding.cardManualConnect.isEnabled = enabled
        binding.cardManualConnect.alpha = if (enabled) 1f else 0.5f
    }

    private fun onServerTapped(server: DiscoveredServer) {
        // Guard against a second connect attempt racing the first: Socket.connect() is a
        // blocking call not integrated with coroutine cancellation, so two in-flight attempts
        // could otherwise resolve out of order and leave ControlClient pointed at the wrong
        // socket.
        if (pendingConnect) return
        pendingConnect = true
        connectingServerName = server.name
        setStatus(
            R.string.status_connecting_title,
            getString(R.string.status_connecting_body, server.name, server.ip),
            busy = true
        )
        setConnectControlsEnabled(false)
        ControlClient.connect(server.ip, server.controlPort)
    }

    private fun observeControlClient() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { ControlClient.state.collect { state -> handleState(state) } }
                launch { ControlClient.events.collect { msg -> handleEvent(msg) } }
            }
        }
    }

    private fun handleState(state: ControlClient.State) {
        when (state) {
            ControlClient.State.PAIRING -> {
                // Whether this pairing started from a tap here or from a token invalidation
                // during a reconnect (StreamActivity finishes back to us in that case), the
                // user is now mid-connect-flow: completing the PIN should land them in the
                // stream, so make sure the READY handler below navigates.
                pendingConnect = true
                setStatus(R.string.status_pairing_title, getString(R.string.status_pairing_body), busy = false)
                showPairDialog()
            }
            ControlClient.State.DISCONNECTED -> {
                pendingConnect = false
                setConnectControlsEnabled(true)
                showSearchingStatus()
                dismissPairDialog()
            }
            ControlClient.State.CONNECTING, ControlClient.State.RECONNECTING -> {
                // Only show progress for a user-initiated connect; background auto-reconnects
                // (the process-wide client keeps its backoff loop running) shouldn't lock
                // this screen's UI.
                if (pendingConnect) {
                    val body = if (state == ControlClient.State.RECONNECTING) {
                        getString(R.string.status_reconnecting_body)
                    } else {
                        getString(
                            R.string.status_connecting_body,
                            connectingServerName.ifEmpty { ControlClient.serverIp },
                            ControlClient.serverIp
                        )
                    }
                    setStatus(R.string.status_connecting_title, body, busy = true)
                }
            }
            ControlClient.State.READY, ControlClient.State.STREAMING -> {
                // Drive navigation off *state* (a StateFlow, always replayed to a fresh
                // collector) rather than the one-shot HELLO_OK event (a SharedFlow with no
                // replay): if the app was briefly backgrounded right as HELLO_OK arrived, the
                // event would be lost, but the state transition to READY is never lost, so we
                // can't strand the user on this screen with no way forward.
                setConnectControlsEnabled(true)
                showSearchingStatus()
                dismissPairDialog()
                if (pendingConnect) {
                    pendingConnect = false
                    // A controller-role connection lands on the touchpad&keyboard screen;
                    // a viewer connection on the stream.
                    val target = if (ControlClient.isController) {
                        ControllerActivity::class.java
                    } else {
                        StreamActivity::class.java
                    }
                    startActivity(Intent(this, target))
                }
            }
        }
    }

    private fun handleEvent(msg: ServerMessage) {
        when (msg) {
            ServerMessage.PairRequired -> {
                // A session opened by "get a new PIN" reached pairing again: the PC now
                // shows a fresh code.
                pairDialog?.onPinIssued()
            }
            is ServerMessage.PairOk -> {
                // ControlClient re-sends HELLO with the new token itself; the UI only
                // needs to take down the PIN screen.
                dismissPairDialog()
            }
            is ServerMessage.PairFail -> {
                if (msg.attemptsLeft > 0) {
                    pairDialog?.showWrongPin(msg.attemptsLeft)
                } else {
                    Snackbar.make(binding.rootLayout, R.string.error_pair_failed, Snackbar.LENGTH_LONG).show()
                    dismissPairDialog()
                    ControlClient.disconnect()
                }
            }
            is ServerMessage.Error -> {
                if (msg.code == "BUSY" && !ControlClient.isController) {
                    // Screen output already taken: offer the controller role instead
                    // of a dead end. Minimal UI reset first so the dialog doesn't fight the
                    // spinner; the server closes the socket right after BUSY, and the
                    // trailing CONNECTION_LOST is swallowed while the dialog is up.
                    pendingConnect = false
                    setConnectControlsEnabled(true)
                    dismissPairDialog()
                    setStatus(R.string.status_busy_title, getString(R.string.status_busy_body), busy = false)
                    showRoleDialog()
                } else if (roleDialog?.isShowing == true) {
                    // Trailing socket-close noise for the rejection the dialog handles.
                } else {
                    pendingConnect = false
                    setConnectControlsEnabled(true)
                    showFailedStatus()
                    dismissPairDialog()
                    Snackbar.make(binding.rootLayout, describeError(msg), Snackbar.LENGTH_LONG).show()
                }
            }
            else -> {}
        }
    }

    private fun describeError(msg: ServerMessage.Error): String = when (msg.code) {
        "CONNECT_FAILED" -> getString(
            R.string.error_connect_failed,
            msg.message.ifEmpty { getString(R.string.error_connect_failed_default) }
        )
        "CONNECTION_LOST" -> getString(R.string.error_connection_lost)
        "BAD_VERSION" -> getString(R.string.error_bad_version, msg.message)
        "BUSY" -> msg.message.ifEmpty { getString(R.string.error_busy_default) }
        "PAIR_TIMEOUT" -> getString(R.string.error_pair_timeout)
        "PAIR_FAILED" -> getString(R.string.error_pair_failed)
        else -> msg.message.ifEmpty { getString(R.string.error_generic, msg.code) }
    }

    /** The second device met the busy screen-output slot: offer the controller
     * role. "Screen output" follows the agreed behavior — an explicit error, never taking
     * the output over from the device that is already displaying. */
    private fun showRoleDialog() {
        if (roleDialog?.isShowing == true) return
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.role_dialog_title)
            .setMessage(R.string.role_dialog_body)
            .setPositiveButton(R.string.role_dialog_controller) { _, _ ->
                pendingConnect = true
                setStatus(R.string.status_connecting_title, getString(R.string.status_controller_body), busy = true)
                ControlClient.connect(
                    ControlClient.serverIp,
                    ControlClient.serverPort,
                    role = "controller"
                )
            }
            .setNegativeButton(R.string.role_dialog_output) { _, _ ->
                Snackbar.make(binding.rootLayout, R.string.role_output_busy, Snackbar.LENGTH_LONG).show()
                showFailedStatus()
            }
            .setNeutralButton(R.string.action_cancel) { _, _ -> showSearchingStatus() }
            .show()
        roleDialog = dialog
    }

    private fun showManualIpDialog() {
        if (pendingConnect || manualDialog?.isShowing == true) return
        val dialogBinding = DialogManualIpBinding.inflate(layoutInflater)
        // Most manual connects go back to the last PC that worked.
        prefs.getPairedServer()?.ip?.let { lastIp ->
            dialogBinding.etManualIp.setText(lastIp)
            dialogBinding.etManualIp.setSelection(lastIp.length)
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.manual_connect_action)
            .setMessage(R.string.manual_dialog_body)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.action_connect, null)
            .setNegativeButton(R.string.action_cancel, null)
            .create()
        val submit = {
            val ip = dialogBinding.etManualIp.text?.toString()?.trim().orEmpty()
            if (ip.isEmpty()) {
                dialogBinding.tilManualIp.error = getString(R.string.manual_dialog_error_empty)
            } else {
                dialog.dismiss()
                val knownName = prefs.getPairedServer()?.takeIf { it.ip == ip }?.name
                onServerTapped(
                    DiscoveredServer(knownName?.ifEmpty { null } ?: ip, ip, DiscoveryClient.DEFAULT_CONTROL_PORT)
                )
            }
        }
        dialog.setOnShowListener {
            // Replace the auto-dismissing positive button so an empty field keeps the
            // dialog open with an inline error.
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { submit() }
        }
        dialogBinding.etManualIp.doAfterTextChanged { dialogBinding.tilManualIp.error = null }
        dialogBinding.etManualIp.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                submit()
                true
            } else {
                false
            }
        }
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialogBinding.etManualIp.requestFocus()
        manualDialog = dialog
        dialog.show()
    }

    private fun showPairDialog() {
        pairDialog?.let { existing ->
            if (existing.isShowing) {
                existing.onPinIssued()
                return
            }
        }
        val dialog = PairingDialog(
            this,
            serverName = connectingServerName.ifEmpty { ControlClient.serverIp },
            serverIp = ControlClient.serverIp,
            onSubmit = { pin -> ControlClient.sendPairCode(pin) },
            // DISCONNECTED resets the rest of this screen.
            onCancelPairing = { ControlClient.disconnect() },
            onRequestNewPin = {
                // The server issues one PIN per session (only on the first PAIR_REQUEST),
                // so a fresh PIN means a fresh session to the same PC. connect() goes
                // straight to CONNECTING without a DISCONNECTED in between, which keeps
                // this screen up until the new session reaches PAIR_REQUIRED.
                ControlClient.connect(ControlClient.serverIp, ControlClient.serverPort, ControlClient.role)
            },
            onManualIp = {
                dismissPairDialog()
                ControlClient.disconnect()
                // The DISCONNECTED collector clears this too, but only after this callback
                // returns, and the manual dialog refuses to open mid-connect.
                pendingConnect = false
                showManualIpDialog()
            },
        )
        pairDialog = dialog
        dialog.show()
    }

    private fun dismissPairDialog() {
        pairDialog?.dismiss()
        pairDialog = null
    }
}
