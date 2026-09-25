package com.localstream.client.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.localstream.client.BuildConfig
import com.localstream.client.R
import com.localstream.client.data.Prefs
import com.localstream.client.databinding.ActivityMainBinding
import com.localstream.client.databinding.DialogPairBinding
import com.localstream.client.net.ControlClient
import com.localstream.client.net.DiscoveredServer
import com.localstream.client.net.DiscoveryClient
import com.localstream.client.proto.ServerMessage
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
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

    private var pairDialog: AlertDialog? = null
    private var pairDialogPinField: TextInputEditText? = null
    private var roleDialog: AlertDialog? = null
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

        binding.btnConnect.setOnClickListener {
            val ip = binding.etManualIp.text?.toString()?.trim().orEmpty()
            if (ip.isEmpty()) {
                Snackbar.make(binding.rootLayout, "Enter a server IP address", Snackbar.LENGTH_SHORT).show()
            } else {
                onServerTapped(DiscoveredServer(ip, ip, DiscoveryClient.DEFAULT_CONTROL_PORT))
            }
        }

        observeControlClient()
    }

    override fun onStart() {
        super.onStart()
        if (!pendingConnect) binding.tvSubtitle.text = "Searching this Wi-Fi network…"
        discoveryClient.start { server ->
            serverAdapter.upsert(server)
            updateEmptyState()
            if (!pendingConnect) {
                val count = serverAdapter.itemCount
                binding.tvSubtitle.text = "$count LocalStream server${if (count == 1) "" else "s"} found"
            }
        }
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
        dismissPairDialog()
    }

    private fun updateEmptyState() {
        val empty = serverAdapter.isEmpty()
        binding.emptyStateContainer.visibility = if (empty) View.VISIBLE else View.GONE
    }

    private fun onServerTapped(server: DiscoveredServer) {
        // Guard against a second connect attempt racing the first: Socket.connect() is a
        // blocking call not integrated with coroutine cancellation, so two in-flight attempts
        // could otherwise resolve out of order and leave ControlClient pointed at the wrong
        // socket.
        if (pendingConnect) return
        pendingConnect = true
        connectingServerName = server.name
        binding.tvSubtitle.text = "Connecting to ${server.name} (${server.ip})…"
        binding.btnConnect.isEnabled = false
        binding.progressDiscovering.visibility = View.VISIBLE
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
                binding.tvSubtitle.text = "Enter the PIN shown on the PC"
                showPairDialog()
            }
            ControlClient.State.DISCONNECTED -> {
                pendingConnect = false
                binding.btnConnect.isEnabled = true
                binding.tvSubtitle.text = "Searching this Wi-Fi network…"
                binding.progressDiscovering.visibility = View.GONE
                dismissPairDialog()
            }
            ControlClient.State.CONNECTING, ControlClient.State.RECONNECTING -> {
                // Only show progress for a user-initiated connect; background auto-reconnects
                // (the process-wide client keeps its backoff loop running) shouldn't lock
                // this screen's UI.
                if (pendingConnect) {
                    binding.progressDiscovering.visibility = View.VISIBLE
                    binding.tvSubtitle.text = if (state == ControlClient.State.RECONNECTING) {
                        "Connection interrupted; retrying…"
                    } else {
                        "Connecting to ${connectingServerName.ifEmpty { ControlClient.serverIp }}…"
                    }
                }
            }
            ControlClient.State.READY, ControlClient.State.STREAMING -> {
                // Drive navigation off *state* (a StateFlow, always replayed to a fresh
                // collector) rather than the one-shot HELLO_OK event (a SharedFlow with no
                // replay): if the app was briefly backgrounded right as HELLO_OK arrived, the
                // event would be lost, but the state transition to READY is never lost, so we
                // can't strand the user on this screen with no way forward.
                binding.progressDiscovering.visibility = View.GONE
                binding.btnConnect.isEnabled = true
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
            is ServerMessage.PairOk -> {
                // ControlClient re-sends HELLO with the new token itself; the UI only
                // needs to take down the PIN dialog.
                dismissPairDialog()
            }
            is ServerMessage.PairFail -> {
                if (msg.attemptsLeft > 0) {
                    pairDialogPinField?.error = "Wrong PIN, ${msg.attemptsLeft} attempt(s) left"
                    pairDialogPinField?.text?.clear()
                } else {
                    Snackbar.make(binding.rootLayout, "Too many failed attempts", Snackbar.LENGTH_LONG).show()
                    dismissPairDialog()
                    binding.progressDiscovering.visibility = View.GONE
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
                    binding.btnConnect.isEnabled = true
                    binding.progressDiscovering.visibility = View.GONE
                    dismissPairDialog()
                    binding.tvSubtitle.text = "Screen in use · choose how to connect"
                    showRoleDialog()
                } else if (roleDialog?.isShowing == true) {
                    // Trailing socket-close noise for the rejection the dialog handles.
                } else {
                    pendingConnect = false
                    binding.btnConnect.isEnabled = true
                    binding.tvSubtitle.text = "Could not connect · tap a server to retry"
                    binding.progressDiscovering.visibility = View.GONE
                    dismissPairDialog()
                    Snackbar.make(binding.rootLayout, describeError(msg), Snackbar.LENGTH_LONG).show()
                }
            }
            else -> {}
        }
    }

    private fun describeError(msg: ServerMessage.Error): String = when (msg.code) {
        "CONNECT_FAILED" -> "Could not connect: ${msg.message.ifEmpty { "connection failed" }}"
        "CONNECTION_LOST" -> "Connection lost"
        "BAD_VERSION" -> "Server protocol mismatch: ${msg.message}"
        "BUSY" -> msg.message.ifEmpty { "That connection role is already in use" }
        else -> msg.message.ifEmpty { "Connection error (${msg.code})" }
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
                binding.tvSubtitle.text = "Connecting as touchpad & keyboard…"
                ControlClient.connect(
                    ControlClient.serverIp,
                    ControlClient.serverPort,
                    role = "controller"
                )
            }
            .setNegativeButton(R.string.role_dialog_output) { _, _ ->
                Snackbar.make(binding.rootLayout, R.string.role_output_busy, Snackbar.LENGTH_LONG).show()
                binding.tvSubtitle.text = "Could not connect · tap a server to retry"
            }
            .setNeutralButton(R.string.pair_dialog_negative, null)
            .show()
        roleDialog = dialog
    }

    private fun showPairDialog() {
        if (pairDialog?.isShowing == true) return
        val dialogBinding = DialogPairBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Pair with ${connectingServerName.ifEmpty { "server" }}")
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.pair_dialog_positive, null)
            .setNegativeButton(R.string.pair_dialog_negative) { _, _ ->
                ControlClient.disconnect()
                binding.progressDiscovering.visibility = View.GONE
            }
            .setCancelable(false)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val pin = dialogBinding.etPin.text?.toString()?.trim().orEmpty()
                if (pin.length == 6 && pin.all(Char::isDigit)) {
                    dialogBinding.etPin.error = null
                    ControlClient.sendPairCode(pin)
                } else {
                    dialogBinding.etPin.error = getString(R.string.pair_dialog_body)
                }
            }
        }
        pairDialogPinField = dialogBinding.etPin
        pairDialog = dialog
        dialog.show()
    }

    private fun dismissPairDialog() {
        pairDialog?.dismiss()
        pairDialog = null
        pairDialogPinField = null
    }
}
