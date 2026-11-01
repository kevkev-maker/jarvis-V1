# JARVIS — build, install and test

JARVIS is built on top of **"Alexandra" by Leila-dev (MIT licence, see `LICENSE`)**.
The original Alexandra code is kept. JARVIS lives in
`android_studio_codes/app/src/main/java/jarvis/`.

Everything here is free. The only optional online service is Groq (free tier) for open questions.

## What JARVIS can do now

Say **"Jarvis"**, **pause**, wait for **"Greetings, sir. At your service."**, then say one command:

| Say | JARVIS does |
|---|---|
| "Call Mom" / "Call Rahul" | Finds the contact, says "Calling…", calls. Several matches → asks which one |
| "Send a message to Dad saying I'll be late" | Sends it on WhatsApp straight away |
| "WhatsApp Mom saying I reached" / "Tell Dad on WhatsApp that I'm coming" | Same |
| "How many aesthetic cafes are near me?" | Counts cafés nearby (free map data) and reads the closest 3 |
| "Pick one" | Chooses one (closest, plus a bonus for outdoor seating / Wi-Fi etc.) |
| "Pick one and send it to Dad on WhatsApp" | Chooses one and sends Dad the name + Google Maps link |
| "Send the second one to Mom" | Sends that place |
| "Remember that my locker code is on the blue diary" | Saves a note on the phone |
| "What do you remember?" / "Forget the locker" / "Forget everything" | Reads / deletes notes |
| "Remind me in 10 minutes to drink water" | Reminder (notification + spoken) |
| "Remind me tomorrow at 9 to submit the form" / "on Friday evening about the party" | Same |
| "What are my reminders?" | Lists upcoming reminders |
| "What time is it?" / "What's the date?" / "Battery?" | Answers offline |
| "Tell me a joke" | Jokes (dark-ish allowed). Better with the AI on |
| Any question | Answered by the AI (only if you add the free key, see below). It knows your notes |
| "Stop listening" / "Goodbye" | Turns JARVIS off |

Places that JARVIS mentions also work in other categories: restaurants, pharmacies, ATMs, petrol pumps, hospitals, bakeries, ice cream.

**On screen:**
- Phone unlocked: a small JARVIS bar with a blue orb appears at the very top of the screen (over the battery/notification area).
- Phone locked or screen off: the screen turns on and shows a full blue orb, like Siri. It closes by itself.

## Build the APK (Android Studio)

1. Install **Android Studio** (free) from developer.android.com/studio.
2. Unzip the project. Android Studio → **Open** → choose the `android_studio_codes` folder.
3. Wait for "Gradle sync" (first time 5–15 minutes).
4. **Build → Build App Bundle(s) / APK(s) → Build APK(s)** → click **locate** → `app-debug.apk`.

Red errors? Copy the **first** error message and send it to Claude.

## Install

USB: Settings → About phone → tap **Build number** 7 times → Developer options → **USB debugging** on →
connect → press the green **Run ▶** in Android Studio.
Or send `app-debug.apk` to your phone and open it (allow "Install unknown apps").

## Setup in the app (once)

1. **Allow permissions**: Microphone, Contacts, Phone, Notifications, Location.
2. **Download model** (~40 MB).
3. **Choose voice**. Try English (United Kingdom) voices, pitch ~0.85–0.95.
4. **Battery** → Unrestricted.
5. **Allow display over other apps** → JARVIS → on. (Needed for the orb, the top bar, and WhatsApp.)
6. **Turn on JARVIS in Accessibility** → Installed apps → "JARVIS (presses Send in WhatsApp)" → on.
   On Android 13+, if it is greyed out: Settings → Apps → JARVIS → ⋮ (top right) → **Allow restricted settings**, then try again.
7. Press **Start listening**.

## Test checklist (report pass / fail)

| # | Test | Expected |
|---|---|---|
| 1 | "Jarvis" with the app open | Greeting + top bar with orb |
| 2 | "Jarvis" while on YouTube / home screen | Top bar appears at the top |
| 3 | Lock phone, screen off, "Jarvis" | Screen turns on with the blue orb |
| 4 | "Call ___" | Call starts |
| 5 | "Send a message to ___ saying test from Jarvis" | WhatsApp opens and the message is sent |
| 6 | "How many cafes are near me?" | Count + 3 names |
| 7 | Then "pick one and send it to ___ on WhatsApp" | Place + map link sent |
| 8 | "Remember that ___" then later "What do you remember?" | Reads it back |
| 9 | "Remind me in 2 minutes to stretch" | Notification + spoken reminder after 2 min |
| 10 | Restart phone, check a reminder set for later | Still fires |
| 11 | "Tell me a joke" | A joke |
| 12 | Stop listening from the notification | Mic off, notification gone |

Also tell Claude: phone brand/model and Android version.

## Known limits (things to plan around)

- **Say "Jarvis", pause, then the command.** One breath ("Jarvis call Mom") usually only triggers the greeting.
- **Anyone can wake JARVIS today.** The "only my voice" lock is the next phase (see below).
- **Voice is not the film voice.** We can't copy the actor's real voice. The next phase adds a free, offline British male voice.
- **WhatsApp sending** needs the phone unlocked, Accessibility on, and "Display over other apps" allowed.
  It presses the real Send button, so it may break if WhatsApp changes its app. Messages are sent straight away, as you chose.
  JARVIS leaves the WhatsApp chat open afterwards.
- **Places** come from OpenStreetMap: free, but no ratings or reviews, and some new places are missing.
  "How many" means how many are on the map, which may be fewer than in real life. JARVIS can't judge "aesthetic".
- **Location in the background**: Android may block fresh location while JARVIS is in the background. JARVIS then uses
  the last location saved when you opened the app, and says how old it is.
- **Reminders** may be a few minutes late if the phone doesn't allow "exact alarms"
  (Settings → Apps → JARVIS → Alarms & reminders → allow).
- **Phone switched off** (powered down) can't hear anything. "Screen off" is fine.
- **After a reboot** open JARVIS and press Start again (Android rule). Reminders survive reboots.
- **During a call** the call owns the microphone.
- **Accessibility permission is powerful.** The old Alexandra service receives screen events from all apps;
  JARVIS only uses it to press WhatsApp's Send button. Turn it off any time in Accessibility settings.

## Optional: AI answers (free tier)

1. Free account at console.groq.com → create an API key.
2. In `android_studio_codes/local.properties` add: `GROQ_API_KEY=your_key_here`
3. Rebuild the APK.

The key stays on your computer and phone (`local.properties` is never uploaded to GitHub).
Questions you ask, plus your saved notes, are sent to Groq to get an answer. Free tier has daily limits;
when you hit them JARVIS says so and the offline features keep working.

## Next phase (planned, free)

All three use **sherpa-onnx** (open source, Apache 2.0, works offline on Android):
1. **Better wake word**: custom "Jarvis" / "Hey Jarvis" keyword spotting.
2. **Only your voice**: record a short sample once, and JARVIS ignores other voices (good, not bank-grade).
3. **Offline British male voice** (Piper-style voices), much closer to the cinematic feel.

## Privacy

- Wake-word listening, notes and reminders stay on the phone.
- Commands go to the phone's speech recognition (Google may process them online).
- Places: your approximate location is sent to the free OpenStreetMap search service.
- The microphone is on only while the notification shows; "Stop listening" turns it off.
