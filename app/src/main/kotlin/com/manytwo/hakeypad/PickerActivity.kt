package com.manytwo.hakeypad

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** Loads the entities from Home Assistant and lets the user tick the ones to show. */
class PickerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SERVER = "server"
        const val EXTRA_TOKEN = "token"
        const val EXTRA_SELECTED = "selected"

        /** Domains that make sense on a keypad: things you can switch, plus readings. */
        val DOMAINS = listOf("switch", "light", "fan", "input_boolean", "script", "scene",
            "button", "input_button", "cover", "lock", "climate", "sensor", "binary_sensor")
    }

    private var all: List<EntityState> = emptyList()
    private var shown: List<EntityState> = emptyList()
    private val selected = LinkedHashSet<String>()
    private lateinit var status: TextView
    private lateinit var filter: EditText
    private val adapter = Adapter()
    private var client: HaClient? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_picker)
        status = findViewById(R.id.picker_status)
        filter = findViewById(R.id.filter)
        val list = findViewById<RecyclerView>(R.id.picker_list)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        selected.addAll(intent.getStringArrayListExtra(EXTRA_SELECTED) ?: arrayListOf())

        filter.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = applyFilter()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        findViewById<Button>(R.id.btn_done).setOnClickListener {
            setResult(Activity.RESULT_OK, Intent().putStringArrayListExtra(EXTRA_SELECTED, ArrayList(selected)))
            finish()
        }

        status.text = "Loading from Home Assistant…"
        client = HaClient(intent.getStringExtra(EXTRA_SERVER) ?: "", intent.getStringExtra(EXTRA_TOKEN) ?: "",
            object : HaClient.Listener {
                override fun onConnection(status: HaClient.Status, detail: String) {
                    when (status) {
                        HaClient.Status.AUTH_FAILED -> { this@PickerActivity.status.text = "Token rejected"; client?.stop() }
                        HaClient.Status.DISCONNECTED -> { this@PickerActivity.status.text = "Can't connect: $detail"; client?.stop() }
                        else -> {}
                    }
                }
                override fun onAllStates(states: Map<String, EntityState>) {
                    all = states.values
                        .filter { it.domain in DOMAINS }
                        .sortedWith(compareBy({ DOMAINS.indexOf(it.domain) }, { it.name.lowercase() }))
                    client?.stop()
                    applyFilter()
                }
                override fun onStateChanged(state: EntityState) {}
            }).also { it.start() }
    }

    private fun applyFilter() {
        val words = filter.text.toString().lowercase().split(' ').filter { it.isNotBlank() }
        shown = all.filter { e ->
            val hay = (e.name + " " + e.entityId).lowercase()
            words.all { it in hay }
        }
        status.text = "${shown.size} of ${all.size} shown · ${selected.size} ticked"
        adapter.notifyDataSetChanged()
    }

    override fun onDestroy() {
        super.onDestroy()
        client?.stop()
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val check: CheckBox = v.findViewById(R.id.pick_check)
            val name: TextView = v.findViewById(R.id.pick_name)
            val id: TextView = v.findViewById(R.id.pick_id)
        }

        override fun getItemCount() = shown.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_pick, parent, false))

        override fun onBindViewHolder(h: VH, position: Int) {
            val e = shown[position]
            h.name.text = e.name
            h.id.text = "${e.entityId} · ${listOfNotNull(e.state, e.unit).joinToString(" ")}"
            h.check.setOnCheckedChangeListener(null)
            h.check.isChecked = e.entityId in selected
            val toggle = {
                if (!selected.remove(e.entityId)) selected.add(e.entityId)
                h.check.isChecked = e.entityId in selected
                status.text = "${shown.size} of ${all.size} shown · ${selected.size} ticked"
            }
            h.itemView.setOnClickListener { toggle() }
            h.check.setOnClickListener { toggle() }
        }
    }
}
