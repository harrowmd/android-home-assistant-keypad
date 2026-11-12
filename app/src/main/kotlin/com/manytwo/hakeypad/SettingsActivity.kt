package com.manytwo.hakeypad

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/** Server, token, look and the list of buttons. Opened by holding the keypad title for 2 s. */
class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var server: EditText
    private lateinit var token: EditText
    private lateinit var title: EditText
    private lateinit var columns: EditText
    private lateinit var dimAfter: EditText
    private lateinit var dimPercent: EditText
    private lateinit var pin: EditText
    private lateinit var buttonList: LinearLayout
    private lateinit var testResult: TextView
    private var buttons = mutableListOf<String>()

    private val pick = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val chosen = r.data?.getStringArrayListExtra(PickerActivity.EXTRA_SELECTED) ?: return@registerForActivityResult
        // Keep the existing order for buttons that stay, add new ones at the end.
        val kept = buttons.filter { it in chosen }
        buttons = (kept + chosen.filter { it !in kept }).toMutableList()
        showButtons()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(R.layout.activity_settings)
        server = findViewById(R.id.server)
        token = findViewById(R.id.token)
        title = findViewById(R.id.title)
        columns = findViewById(R.id.columns)
        dimAfter = findViewById(R.id.dim_after)
        dimPercent = findViewById(R.id.dim_percent)
        pin = findViewById(R.id.pin)
        buttonList = findViewById(R.id.button_list)
        testResult = findViewById(R.id.test_result)

        server.setText(prefs.serverUrl)
        token.setText(prefs.token)
        title.setText(prefs.title)
        columns.setText(prefs.columns.toString())
        dimAfter.setText(prefs.dimAfterSec.toString())
        dimPercent.setText(prefs.dimPercent.toString())
        pin.setText(prefs.pin)
        buttons = prefs.buttons.toMutableList()
        showButtons()

        findViewById<TextView>(R.id.version).text =
            "HA Keypad ${BuildConfig.VERSION_NAME} · built ${BuildConfig.BUILD_TIME}"

        findViewById<Button>(R.id.btn_test).setOnClickListener { test() }
        findViewById<Button>(R.id.btn_choose).setOnClickListener {
            if (server.text.isBlank() || token.text.isBlank()) {
                toast("Enter the server and token first"); return@setOnClickListener
            }
            pick.launch(Intent(this, PickerActivity::class.java)
                .putExtra(PickerActivity.EXTRA_SERVER, server.text.toString())
                .putExtra(PickerActivity.EXTRA_TOKEN, token.text.toString())
                .putStringArrayListExtra(PickerActivity.EXTRA_SELECTED, ArrayList(buttons)))
        }
        findViewById<Button>(R.id.btn_home).setOnClickListener {
            // Lets the user pick this app as the Home app, so it is always on screen.
            startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
        }
        findViewById<Button>(R.id.btn_save).setOnClickListener { save(); finish() }
        findViewById<Button>(R.id.btn_cancel).setOnClickListener { finish() }
    }

    private fun save() {
        prefs.serverUrl = server.text.toString()
        prefs.token = token.text.toString()
        prefs.title = title.text.toString().ifBlank { "Keypad" }
        prefs.columns = columns.text.toString().toIntOrNull() ?: 2
        prefs.dimAfterSec = dimAfter.text.toString().toIntOrNull() ?: 60
        prefs.dimPercent = dimPercent.text.toString().toIntOrNull() ?: 3
        prefs.pin = pin.text.toString()
        prefs.buttons = buttons
        toast("Saved")
    }

    /** One row per chosen button: name, ▲ ▼ to reorder, ✎ to rename, ✕ to remove. */
    private fun showButtons() {
        buttonList.removeAllViews()
        if (buttons.isEmpty()) {
            buttonList.addView(TextView(this).apply {
                text = "No buttons yet. Tap \"Choose buttons\"."
                setPadding(0, 8, 0, 8)
            })
            return
        }
        buttons.forEachIndexed { i, id ->
            val row = LayoutInflater.from(this).inflate(R.layout.item_button_row, buttonList, false)
            row.findViewById<TextView>(R.id.row_name).text =
                (prefs.label(id)?.let { "$it  " } ?: "") + id
            row.findViewById<View>(R.id.row_up).setOnClickListener {
                if (i > 0) { buttons.add(i - 1, buttons.removeAt(i)); showButtons() }
            }
            row.findViewById<View>(R.id.row_down).setOnClickListener {
                if (i < buttons.size - 1) { buttons.add(i + 1, buttons.removeAt(i)); showButtons() }
            }
            row.findViewById<View>(R.id.row_rename).setOnClickListener { rename(id) }
            row.findViewById<View>(R.id.row_remove).setOnClickListener { buttons.removeAt(i); showButtons() }
            buttonList.addView(row)
        }
    }

    private fun rename(id: String) {
        val input = EditText(this).apply { setText(prefs.label(id) ?: ""); hint = "Leave empty to use the HA name" }
        AlertDialog.Builder(this)
            .setTitle("Button label")
            .setMessage(id)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ -> prefs.setLabel(id, input.text.toString()); showButtons() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private var testClient: HaClient? = null

    private fun test() {
        testClient?.stop()
        testResult.text = "Testing…"
        testClient = HaClient(server.text.toString(), token.text.toString(), object : HaClient.Listener {
            override fun onConnection(status: HaClient.Status, detail: String) {
                when (status) {
                    HaClient.Status.CONNECTED -> testResult.text = "Connected ✓ — loading states…"
                    HaClient.Status.AUTH_FAILED -> { testResult.text = "Token rejected: $detail"; testClient?.stop() }
                    HaClient.Status.DISCONNECTED -> { testResult.text = "Can't connect: $detail"; testClient?.stop() }
                    HaClient.Status.CONNECTING -> {}
                }
            }
            override fun onAllStates(states: Map<String, EntityState>) {
                testResult.text = "Connected ✓ — ${states.size} entities"
                testClient?.stop()
            }
            override fun onStateChanged(state: EntityState) {}
        }).also { it.start() }
    }

    override fun onDestroy() {
        super.onDestroy()
        testClient?.stop()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
