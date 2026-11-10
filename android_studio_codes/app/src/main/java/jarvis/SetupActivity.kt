package com.example.myapp.jarvis

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.ContactsContract
import android.text.InputType
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.myapp.jarvis.JarvisUi as Ui

/**
 * First-launch questions so JARVIS knows who's who before you start
 * (e.g. "mom" → the contact saved as "birth giver").
 * Everything is saved on the phone. Open it again any time from the JARVIS screen.
 */
class SetupActivity : AppCompatActivity() {

    private class Row(val word: EditText, val contact: AutoCompleteTextView)

    private lateinit var prefs: JarvisPrefs
    private lateinit var store: MemoryStore
    private lateinit var rowsBox: LinearLayout
    private lateinit var contactsNote: TextView
    private lateinit var confirmCalls: SwitchCompat
    private lateinit var confirmWa: SwitchCompat
    private lateinit var keyInput: EditText
    private val rows = mutableListOf<Row>()
    private var contactNames: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Ui.BG
        prefs = JarvisPrefs(this)
        store = MemoryStore.get(this)

        val root = Ui.column(this, 20)
        root.addView(Ui.title(this, "Setup"))
        root.addView(Ui.spaced(this, Ui.label(this,
            "A few questions so I don't get confused later, sir. You can change these any time.",
            14f, Ui.MUTED), 6))

        // 1. Who's who
        root.addView(Ui.sectionHeader(this, "1. Who's who in your contacts"))
        root.addView(Ui.label(this,
            "Left: what you'll say to me. Right: the name saved in your phone's contacts. " +
                "Start typing on the right and pick from the list.", 13f, Ui.MUTED))
        contactsNote = Ui.label(this, "", 13f, Ui.DANGER)
        root.addView(Ui.spaced(this, contactsNote, 6))
        rowsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(rowsBox)
        root.addView(Ui.spaced(this, Ui.button(this, "Add another person") { addRow("", "") }))

        // 2. Ask first?
        root.addView(Ui.sectionHeader(this, "2. Should I ask before acting?"))
        confirmCalls = toggle("Ask me “Call …?” before calling", prefs.confirmCalls)
        root.addView(confirmCalls)
        confirmWa = toggle("Ask me before sending WhatsApp messages", prefs.confirmWhatsApp)
        root.addView(Ui.spaced(this, confirmWa, 4))

        // 3. AI key
        root.addView(Ui.sectionHeader(this, "3. AI brain (optional, free)"))
        root.addView(Ui.label(this,
            "Lets me answer questions and understand however you phrase things. " +
                "Get a free key at console.groq.com → API Keys, and paste it here. Leave it empty to skip.",
            13f, Ui.MUTED))
        keyInput = input("Paste key here (starts with gsk_)", prefs.groqKey).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        root.addView(Ui.spaced(this, keyInput, 8))

        root.addView(Ui.spaced(this, Ui.button(this, "Save", primary = true) { save() }, 24))

        setContentView(ScrollView(this).apply { setBackgroundColor(Ui.BG); addView(root) })

        val saved = store.aliases()
        if (saved.isEmpty()) {
            addRow("mom", "birth giver")
            addRow("girlfriend", "mamacita")
        } else {
            saved.forEach { (w, c) -> addRow(w, c) }
        }
        addRow("", "")
        loadContacts(askIfMissing = true)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ) loadContacts(askIfMissing = false)
    }

    private fun loadContacts(askIfMissing: Boolean) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            contactsNote.text = "Allow contacts so I can suggest names and check the spelling."
            if (askIfMissing) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_CONTACTS), REQ)
            }
            return
        }
        contactsNote.text = ""
        Thread {
            val names = mutableSetOf<String>()
            try {
                contentResolver.query(
                    ContactsContract.Contacts.CONTENT_URI,
                    arrayOf(ContactsContract.Contacts.DISPLAY_NAME), null, null, null
                )?.use { c ->
                    while (c.moveToNext()) {
                        val n = c.getString(0)?.trim()
                        if (!n.isNullOrEmpty()) names.add(n)
                    }
                }
            } catch (_: Exception) { }
            runOnUiThread {
                contactNames = names.sortedBy { it.lowercase() }
                rows.forEach { attachAdapter(it.contact) }
            }
        }.start()
    }

    private fun addRow(word: String, contact: String) {
        val w = input("e.g. mom", word)
        val c = AutoCompleteTextView(this)
        style(c, "Name in contacts", contact)
        attachAdapter(c)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(w, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(Ui.label(this@SetupActivity, "→", 16f, Ui.MUTED).apply {
                setPadding(Ui.dp(context, 8), 0, Ui.dp(context, 8), 0)
            })
            addView(c, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.4f))
        }
        rows.add(Row(w, c))
        rowsBox.addView(Ui.spaced(this, row, 8))
    }

    private fun attachAdapter(v: AutoCompleteTextView) {
        v.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, contactNames))
        v.threshold = 1
    }

    private fun save() {
        val pairs = rows.mapNotNull { r ->
            val w = r.word.text.toString().trim().lowercase().removePrefix("my ").trim()
            val c = r.contact.text.toString().trim()
            if (w.isEmpty() || c.isEmpty()) null else w to c
        }
        val known = contactNames.map { it.lowercase() }.toSet()
        val missing = if (known.isEmpty()) emptyList()
        else pairs.filter { it.second.lowercase() !in known }.map { it.second }

        store.clearAliases()
        pairs.forEach { (w, c) -> store.setAlias(w, c.lowercase()) }
        prefs.confirmCalls = confirmCalls.isChecked
        prefs.confirmWhatsApp = confirmWa.isChecked
        prefs.groqKey = keyInput.text.toString().trim()
        prefs.setupDone = true

        if (missing.isEmpty()) {
            Toast.makeText(this, "Saved. Ready when you are, sir.", Toast.LENGTH_SHORT).show()
            finish()
        } else {
            AlertDialog.Builder(this)
                .setTitle("Saved, but please check")
                .setMessage(
                    "I couldn't find these names in your contacts:\n\n" + missing.joinToString("\n") +
                        "\n\nCheck the spelling, or pick from the list while typing."
                )
                .setPositiveButton("Fix it", null)
                .setNegativeButton("It's fine") { _, _ -> finish() }
                .show()
        }
    }

    private fun input(hint: String, value: String) = EditText(this).also { style(it, hint, value) }

    private fun style(e: EditText, hint: String, value: String) {
        e.hint = hint
        e.setText(value)
        e.setTextColor(Ui.TEXT)
        e.setHintTextColor(Ui.MUTED)
        e.textSize = 15f
        e.isSingleLine = true
    }

    private fun toggle(text: String, checked: Boolean) = SwitchCompat(this).apply {
        this.text = text
        isChecked = checked
        setTextColor(Ui.TEXT)
        textSize = 15f
        setPadding(0, Ui.dp(context, 8), 0, Ui.dp(context, 8))
    }

    companion object { private const val REQ = 21 }
}
