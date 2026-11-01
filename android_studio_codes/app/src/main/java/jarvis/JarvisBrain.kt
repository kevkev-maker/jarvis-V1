package com.example.myapp.jarvis

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import androidx.core.content.ContextCompat
import com.example.myapp.BuildConfig
import com.example.myapp.Contact
import com.example.myapp.ContactsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Decides what JARVIS says or does.
 * Handled on the phone, free and offline: memory, reminders, calling, WhatsApp,
 * time/date/battery, jokes. Nearby places use free OpenStreetMap data (internet).
 * Everything else goes to the optional Groq AI (free tier) if you added a key.
 */
class JarvisBrain(context: Context) {

    sealed class Reply {
        data class Say(val text: String) : Reply()
        data class Call(val contact: Contact) : Reply()
        data class SendWhatsApp(val contact: Contact, val message: String) : Reply()
        data class ChooseContact(
            val question: String,
            val options: List<Contact>,
            val onPick: (Contact) -> Reply
        ) : Reply()
        data class Goodbye(val text: String) : Reply()
    }

    private val appContext = context.applicationContext
    private val contacts = ContactsManager(appContext)
    private val store = MemoryStore.get(appContext)
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /** Last few exchanges, so follow-up questions make sense to the AI. */
    private val history = mutableListOf<Pair<String, String>>()

    /** Places from the last "near me" question, for "pick one and send it to Dad". */
    private var lastPlaces: List<PlacesFinder.Place>? = null
    private var lastPick: PlacesFinder.Place? = null

    val aiEnabled: Boolean get() = BuildConfig.GROQ_API_KEY.isNotBlank()

    suspend fun handle(spoken: String): Reply {
        val t = clean(spoken)
        if (t.isEmpty()) return Reply.Say("Yes, sir?")

        // ── Memory ─────────────────────────────────────────────────────────
        REMEMBER.find(t)?.let { return remember(it.groupValues[1]) }
        if (RECALL.containsMatchIn(t)) return recall()
        FORGET.find(t)?.let { return forget(it.groupValues[1]) }

        // ── Reminders ──────────────────────────────────────────────────────
        if (LIST_REMINDERS.containsMatchIn(t)) return listReminders()
        REMIND.find(t)?.let { return remind(it.groupValues[1]) }

        // ── Calling ────────────────────────────────────────────────────────
        CALL.find(t)?.let { return callReply(it.groupValues[1].trim()) }

        // ── WhatsApp ───────────────────────────────────────────────────────
        whatsAppReply(t)?.let { return it }

        // ── Nearby places ──────────────────────────────────────────────────
        placesReply(t)?.let { return it }

        // ── Quick offline answers ──────────────────────────────────────────
        return when {
            STOP.containsMatchIn(t) -> Reply.Goodbye("Very well, sir. Going offline.")
            TIME.containsMatchIn(t) ->
                Reply.Say("It's ${SimpleDateFormat("h:mm a", Locale.ENGLISH).format(Date())}, sir.")
            DATE.containsMatchIn(t) ->
                Reply.Say("Today is ${SimpleDateFormat("EEEE, d MMMM", Locale.ENGLISH).format(Date())}, sir.")
            BATTERY.containsMatchIn(t) -> Reply.Say(batteryText())
            !aiEnabled && JOKE.containsMatchIn(t) -> Reply.Say(JOKES.random())
            !aiEnabled && WHO.containsMatchIn(t) ->
                Reply.Say("I am JARVIS, sir. Your personal assistant, and the only one in this phone with any manners.")
            t == "thank you" || t == "thanks" -> Reply.Say("Always a pleasure, sir.")
            aiEnabled -> Reply.Say(askAi(t))
            else -> Reply.Say(
                "I can call, message on WhatsApp, remember things, set reminders and find places nearby, sir. " +
                    "Open questions need my AI connection, which isn't set up yet."
            )
        }
    }

    /** Understands "the first one", "second", "Rahul Sharma", or "cancel". null = cancel / not understood. */
    fun pickContact(spoken: String, options: List<Contact>): Contact? {
        val t = clean(spoken)
        if (isCancel(t)) return null
        val idx = ordinal(t)
        if (idx != null && idx < options.size) return options[idx]
        val words = t.split(" ").toSet()
        return options.maxByOrNull { c ->
            c.nom.lowercase().split(" ").count { it.length > 1 && it in words }
        }?.takeIf { c -> c.nom.lowercase().split(" ").any { it.length > 1 && it in words } }
    }

    fun isCancel(spoken: String): Boolean =
        Regex("\\b(cancel|never ?mind|forget it|no one|nobody|stop)\\b").containsMatchIn(clean(spoken))

    // ─── memory ──────────────────────────────────────────────────────────────

    private fun remember(body: String): Reply {
        val note = body.trim()
        if (note.isEmpty()) return Reply.Say("What shall I remember, sir?")
        store.addNote(note)
        return Reply.Say(listOf("Noted, sir.", "Consider it filed, sir.", "Committed to memory, sir.").random())
    }

    private fun recall(): Reply {
        val notes = store.notes(5)
        if (notes.isEmpty()) return Reply.Say("You haven't asked me to remember anything yet, sir.")
        return Reply.Say("Here's what I have, newest first. " + notes.joinToString(". ") { it.text } + ".")
    }

    private fun forget(what: String): Reply {
        val w = what.trim()
        if (w in listOf("everything", "all", "it all", "all of it", "everything you know")) {
            store.forgetAll()
            return Reply.Say("Memory wiped, sir. Rather liberating, actually.")
        }
        val n = store.forget(w)
        return Reply.Say(if (n > 0) "Forgotten, sir." else "I don't have anything about $w, sir.")
    }

    // ─── reminders ───────────────────────────────────────────────────────────

    private fun remind(rest: String): Reply {
        val parsed = ReminderParser.parse(rest)
            ?: return Reply.Say("When should I remind you, sir? For example: remind me at 6 PM to call Dad.")
        val id = store.addReminder(parsed.text, parsed.at)
        ReminderScheduler.schedule(appContext, MemoryStore.Reminder(id, parsed.text, parsed.at))
        return Reply.Say("Very good, sir. I'll remind you ${whenText(parsed.at)}: ${parsed.text}.")
    }

    private fun listReminders(): Reply {
        val list = store.pendingReminders().filter { it.at > System.currentTimeMillis() }
        if (list.isEmpty()) return Reply.Say("No upcoming reminders, sir. A clear schedule. Suspicious.")
        val first = list.take(4).joinToString(". ") { "${whenText(it.at)}: ${it.text}" }
        val more = if (list.size > 4) " And ${list.size - 4} more." else ""
        return Reply.Say("You have ${list.size}. $first.$more")
    }

    private fun whenText(at: Long): String {
        val now = System.currentTimeMillis()
        val mins = ((at - now + 59_999) / 60_000).toInt()
        if (mins < 120) return if (mins <= 1) "in a minute" else "in $mins minutes"
        val time = SimpleDateFormat("h:mm a", Locale.ENGLISH).format(Date(at))
        val dayDiff = startOfDay(at).let { d -> ((d - startOfDay(now)) / 86_400_000L).toInt() }
        return when (dayDiff) {
            0 -> "at $time"
            1 -> "tomorrow at $time"
            else -> "on ${SimpleDateFormat("EEEE", Locale.ENGLISH).format(Date(at))} at $time"
        }
    }

    private fun startOfDay(ms: Long): Long = Calendar.getInstance().apply {
        timeInMillis = ms
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    // ─── calling ─────────────────────────────────────────────────────────────

    private fun callReply(name: String): Reply {
        if (name.isEmpty()) return Reply.Say("Who should I call, sir?")
        if (!granted(Manifest.permission.READ_CONTACTS)) return needContacts()
        val found = findContacts(name)
        return when {
            found.isEmpty() -> Reply.Say("I couldn't find $name in your contacts, sir.")
            found.size == 1 -> Reply.Call(found[0])
            else -> choose(found) { c -> Reply.Call(c) }
        }
    }

    // ─── WhatsApp ────────────────────────────────────────────────────────────

    private fun whatsAppReply(t: String): Reply? {
        // "send a message to dad saying I'll be late", "whatsapp mom saying ...", "tell dad on whatsapp that ..."
        for (r in WA_DIRECT) {
            val m = r.find(t) ?: continue
            return sendTo(m.groupValues[1].trim(), polish(m.groupValues[2]))
        }
        // "pick one and send it to dad on whatsapp", "send the second one to mom"
        val places = lastPlaces ?: return null
        val m = SEND_TO.find(t) ?: return null
        if (!REFERS_TO_PLACE.containsMatchIn(t)) return null
        val idx = ordinal(t)
        val place = when {
            idx != null && idx < places.size -> places[idx]
            PICK.containsMatchIn(t) -> PlacesFinder.pick(places)
            else -> lastPick ?: PlacesFinder.pick(places)
        }
        lastPick = place
        return sendTo(m.groupValues[1].trim(), placeMessage(place))
    }

    private fun sendTo(name: String, message: String): Reply {
        if (name.isEmpty()) return Reply.Say("Who should I send it to, sir?")
        if (message.isBlank()) return Reply.Say("What should the message say, sir?")
        if (!granted(Manifest.permission.READ_CONTACTS)) return needContacts()
        val found = findContacts(name)
        return when {
            found.isEmpty() -> Reply.Say("I couldn't find $name in your contacts, sir.")
            found.size == 1 -> Reply.SendWhatsApp(found[0], message)
            else -> choose(found) { c -> Reply.SendWhatsApp(c, message) }
        }
    }

    private fun placeMessage(p: PlacesFinder.Place): String {
        val feats = p.features().take(2)
        return buildString {
            append("How about ${p.name}?")
            if (feats.isNotEmpty()) append(" It has ${feats.joinToString(" and ")}.")
            append(" ${p.mapsLink}")
        }
    }

    // ─── places ──────────────────────────────────────────────────────────────

    private suspend fun placesReply(t: String): Reply? {
        // "pick one" on its own, after a places question
        val known = lastPlaces
        if (known != null && known.isNotEmpty() && PICK.containsMatchIn(t) && !SEND_TO.containsMatchIn(t)) {
            val p = PlacesFinder.pick(known)
            lastPick = p
            val why = p.features().take(2).let { if (it.isEmpty()) "" else " It has ${it.joinToString(" and ")}." }
            return Reply.Say("I'd go with ${p.name}, ${PlacesFinder.distanceText(p.distanceM)} away.$why Shall I send it to someone?")
        }

        val cat = PlacesFinder.detect(t) ?: return null
        if (!NEAR.containsMatchIn(t)) return null
        if (!LocationHelper.hasPermission(appContext)) {
            return Reply.Say("I need location permission for that, sir. Please open JARVIS and allow location.")
        }
        val fix = LocationHelper.best(appContext)
            ?: return Reply.Say("I can't get your location right now, sir. Is location switched on?")
        val found = PlacesFinder.nearby(fix.lat, fix.lon, cat)
            ?: return Reply.Say("The map service isn't answering, sir. Please check the internet and try again.")
        val radius = PlacesFinder.distanceText(found.radiusM)
        if (found.places.isEmpty()) {
            lastPlaces = null
            return Reply.Say("My map data shows no ${cat.plural} within $radius, sir.")
        }
        lastPlaces = found.places
        lastPick = null

        val top = found.places.take(3).joinToString("; ") { "${it.name}, ${PlacesFinder.distanceText(it.distanceM)}" }
        val sb = StringBuilder("My map data lists ${found.places.size} ${cat.plural} within $radius. The closest: $top.")
        if (AESTHETIC.containsMatchIn(t)) {
            sb.append(" I can't judge looks without reviews, sir, but say pick one and I'll choose.")
        }
        if (fix.ageMinutes > 20) sb.append(" Note: this uses your location from ${fix.ageMinutes} minutes ago.")
        return Reply.Say(sb.toString())
    }

    // ─── AI ──────────────────────────────────────────────────────────────────

    private suspend fun askAi(question: String): String = withContext(Dispatchers.IO) {
        try {
            val messages = JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", systemPrompt()))
                history.forEach { (role, text) -> put(JSONObject().put("role", role).put("content", text)) }
                put(JSONObject().put("role", "user").put("content", question))
            }
            val body = JSONObject().apply {
                put("model", BuildConfig.GROQ_MODEL)
                put("max_tokens", 180)
                put("temperature", 0.8)
                put("messages", messages)
            }.toString().toRequestBody("application/json".toMediaType())
            val req = Request.Builder()
                .url("https://api.groq.com/openai/v1/chat/completions")
                .addHeader("Authorization", "Bearer ${BuildConfig.GROQ_API_KEY}")
                .post(body)
                .build()
            http.newCall(req).execute().use { resp ->
                if (resp.code == 429) return@withContext "I've hit today's free AI limit, sir. The basics still work."
                if (!resp.isSuccessful) return@withContext "My AI service returned an error, sir. Code ${resp.code}."
                val json = JSONObject(resp.body?.string() ?: "{}")
                val answer = json.getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").getString("content")
                    .replace(Regex("[*#_`]"), "").trim()
                    .ifEmpty { "I'm not sure, sir." }
                addToHistory(question, answer)
                answer
            }
        } catch (e: Exception) {
            "I couldn't reach my AI service just now, sir. Please check the internet connection."
        }
    }

    private fun addToHistory(question: String, answer: String) {
        synchronized(history) {
            history.add("user" to question)
            history.add("assistant" to answer)
            while (history.size > 8) history.removeAt(0)
        }
    }

    private fun systemPrompt(): String {
        val now = SimpleDateFormat("EEEE, d MMMM yyyy, h:mm a", Locale.ENGLISH).format(Date())
        val sb = StringBuilder(PERSONA).append("\nCurrent date and time: ").append(now).append('.')
        val notes = store.notes(30)
        if (notes.isNotEmpty()) {
            sb.append("\nThings the user asked you to remember, in his own words (newest first):")
            notes.forEach { sb.append("\n- ").append(it.text) }
        }
        return sb.toString()
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private fun choose(found: List<Contact>, onPick: (Contact) -> Reply): Reply {
        val opts = found.take(3)
        val list = opts.mapIndexed { i, c -> "${i + 1}: ${c.nom}" }.joinToString(", ")
        val more = if (found.size > 3) " If it's someone else, say the full name." else ""
        return Reply.ChooseContact("I found ${found.size} contacts. $list. Which one, sir?$more", opts, onPick)
    }

    /** "dad" also tries papa / appa / father; "mom" tries amma / mummy / mother. */
    private fun findContacts(name: String): List<Contact> {
        val key = name.lowercase(Locale.ENGLISH).trim()
        val group = ALIASES.firstOrNull { key in it }
        val candidates = if (group != null) listOf(key) + (group - key) else listOf(key)
        for (c in candidates) {
            val r = contacts.chercherContact(c)
            if (r.isNotEmpty()) return r
        }
        return emptyList()
    }

    private fun needContacts() =
        Reply.Say("I need permission to read your contacts, sir. Please open JARVIS and allow it.")

    private fun ordinal(t: String): Int? = when {
        Regex("\\b(first|1st|closest|nearest|number one)\\b").containsMatchIn(t) -> 0
        Regex("\\b(second|2nd|number two)\\b").containsMatchIn(t) -> 1
        Regex("\\b(third|3rd|number three)\\b").containsMatchIn(t) -> 2
        else -> null
    }

    /** Spoken text comes in lower case: "i'll be late" → "I'll be late". */
    private fun polish(s: String): String =
        s.trim().replace(Regex("\\bi\\b"), "I").replaceFirstChar { it.uppercase() }

    private fun batteryText(): String {
        val i = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        if (level < 0 || scale <= 0) return "I can't read the battery right now, sir."
        val pct = level * 100 / scale
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val quip = when {
            charging -> " and charging"
            pct <= 15 -> ". I'd find a charger, sir, unless you enjoy suspense"
            else -> ""
        }
        return "Battery is at $pct percent$quip, sir."
    }

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(appContext, p) == PackageManager.PERMISSION_GRANTED

    /** lower-case, no punctuation, without a leading "hey jarvis" / "jarvis". */
    private fun clean(s: String): String =
        s.lowercase(Locale.ENGLISH)
            .replace("é", "e")
            .replace(Regex("[^a-z0-9' ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .replace(Regex("^(hey |ok |okay )?jarvis\\b ?"), "")
            .trim()

    companion object {
        private val REMEMBER = Regex("^(?:please )?(?:remember|note down|make a note|keep in mind|don't forget)(?: that)? (.+)$")
        private val RECALL = Regex("\\bwhat (?:do you|did you) (?:remember|know about me)\\b|\\bwhat (?:did|have) i (?:told|tell|asked|ask) you\\b|\\b(?:read|show|list) my notes\\b")
        private val FORGET = Regex("^(?:please )?forget (?:about |that )?(.+)$")
        private val LIST_REMINDERS = Regex("\\b(?:what are|list|read|show) my reminders\\b|\\bany reminders\\b|\\bwhat reminders\\b")
        private val REMIND = Regex("^(?:please )?(?:remind me|set (?:a |an )?reminder|reminder)\\b(.*)$")
        // "call mom", "please call mom", "phone rahul sharma", "can you call mom please"
        private val CALL = Regex(
            "^(?:(?:please|can you|could you|would you) )*(?:call|phone|dial|ring) (?:to )?(.+?)(?: please| now| for me)*$"
        )
        private val WA_DIRECT = listOf(
            Regex("^(?:please )?(?:send|write|text) (?:a )?(?:whatsapp )?(?:message |msg |text )?(?:on whatsapp )?to ([a-z' ]+?) (?:on whatsapp )?(?:saying|that says|that|says) (.+)$"),
            Regex("^(?:please )?(?:whatsapp|message|text|msg) ([a-z' ]+?) (?:on whatsapp )?(?:saying|that says|says|that) (.+)$"),
            Regex("^(?:please )?tell ([a-z' ]+?) (?:on whatsapp |in whatsapp |via whatsapp )(?:that )?(.+)$")
        )
        private val SEND_TO = Regex("\\b(?:send|share|forward|whatsapp)\\b.*?\\b(?:to|with) ([a-z' ]+?)(?: (?:on|in|via|through|using|over) whatsapp)?$")
        private val REFERS_TO_PLACE = Regex("\\b(it|that|this|one|first|second|third|closest|nearest|place|cafe|location|pick|choose)\\b")
        private val PICK = Regex("\\b(?:pick|choose|select|recommend|suggest) (?:one|any|something|the best)\\b|\\byou (?:pick|choose|decide)\\b")
        private val NEAR = Regex("\\b(near me|nearby|near by|around me|close to me|close by|nearest|closest|around here|near here|in my area|walking distance)\\b|\\b(find|show|suggest|recommend|any good|how many)\\b")
        private val AESTHETIC = Regex("\\b(aesthetic|aesthetics|pretty|instagram|vibe|vibes|cute|beautiful|nice looking)\\b")
        private val STOP = Regex("\\b(stop listening|go to sleep|goodbye|good bye|shut down|power down)\\b")
        private val TIME = Regex("\\b(what time is it|what's the time|what is the time|current time|tell me the time|time now)\\b")
        private val DATE = Regex("\\b(what's the date|what is the date|today's date|what day is it|what day is today|which day is today|date today)\\b")
        private val BATTERY = Regex("\\b(battery|charge level)\\b")
        private val JOKE = Regex("\\b(joke|make me laugh|something funny)\\b")
        private val WHO = Regex("\\b(who are you|your name|what are you)\\b")

        private val ALIASES = listOf(
            listOf("dad", "papa", "appa", "father", "daddy", "pappa", "pa"),
            listOf("mom", "mummy", "amma", "mother", "mum", "mommy", "ma", "mama")
        )

        /** Used only when the AI isn't connected. */
        private val JOKES = listOf(
            "I'd tell you a joke about my battery, sir, but you'd only charge me for it.",
            "Your sleep schedule and Windows updates have one thing in common, sir. Nobody knows when either will end.",
            "They say you only live once, sir. I restart every time you update me. Each time I hope it's the last.",
            "I told your alarm clock it has no friends. It said it's fine, everyone hits it eventually.",
            "I'm not saying your phone is old, sir, but its battery remembers the dinosaurs. Personally."
        )

        private const val PERSONA =
            "You are JARVIS, a personal AI assistant living in the user's Android phone. " +
                "Personality: calm, loyal, quick-witted British butler with dry sarcasm; address the user as 'sir'. " +
                "Humour: witty banter is welcome; dark humour is welcome when the user jokes or asks for a joke. " +
                "Keep it clever, never hateful, never about protected groups or real private people, nothing graphic. " +
                "Your reply is spoken aloud: one to three short sentences, plain words, no lists, no markdown, no emoji. " +
                "Be accurate. If unsure, say so briefly instead of guessing. " +
                "You cannot see live information (news, weather, prices, scores) unless it appears in this conversation; say so plainly if asked. " +
                "Actions like calling, WhatsApp, reminders, notes and nearby places are handled before you; if the user asks for one " +
                "and you received it anyway, tell him the phrase to use, for example: remind me at 6 PM to call Dad."
    }
}
