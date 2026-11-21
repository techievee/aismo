<p align="center"><img src="docs/images/aismo-icon.png" alt="aismo icon: a raised, embossed speech bubble with AI sparkles" width="160"></p>

# aismo: AI SMS Manager

An Android SMS app that files your messages automatically (codes, bank alerts, bills, orders, travel,
promotions, spam) and keeps everything on the phone. Kotlin + Jetpack Compose, minSdk 29, targetSdk 37.

If you installed an earlier version of this app, it used a different package name, so this version installs beside it rather than over
it: install the new app, restore a backup (Settings → Backup and restore) to bring your messages, settings and categories across, then
uninstall the old one and make the new one your default SMS app. The launcher shows the short name **aismo**; Android's settings show the full name.

It is an original implementation, written from scratch.

If aismo is useful to you, you can [buy me a coffee](https://buymeacoffee.com/aismo).

## Features

| Area | What it does |
|---|---|
| Messenger | Default-SMS-app role, receive and send (multipart, sent/delivered status, retry), notification reply / mark read / copy code, `sms:` links, share-to-SMS, respond-via-message |
| MMS | Receive (push notification, automatic or manual download, roaming guard, bounded retries), send with photos, video, audio, contacts and files (images shrunk to the carrier limit, EXIF rotation, SMIL layout), group MMS, attachment thumbnails, open and save attachments, subject display. **SMS and MMS from the same sender are separate conversations** (both use the same normalized number, so every spelling of it still lands in the same thread of its type); a reply stays in the thread it is sent from |
| Long texts | A text longer than the carrier's SMS-to-MMS threshold (default: more than 4 SMS parts, counted for GSM-7 or UCS-2) goes out as one MMS instead of a chain of parts. On by default; you can turn it off or pick the threshold. Never used when MMS is unavailable (mobile data off, carrier MMS disabled) or the text exceeds the carrier's text-size limit; the composer shows what will happen, and a failed converted text offers "Send as text" |
| Undo send | Settings → Sending → Undo send: hold every message for 3, 5, 10, 15 or 30 seconds after you tap Send (off by default). While it waits, a banner above the message box counts down with **Undo** (the text and attachments come back into the box) and **Send now**; pressing and holding the Send button skips the wait for that message. It applies to typed messages, New message and quick-reply chips; a notification's inline reply is sent at once. The message is held by the app, not the screen, so leaving the conversation does not cancel it, and sending a second message to the same person first sends the earlier one so they stay in order. **Leaving the app sends whatever is waiting straight away**, so a held message is never lost to the system closing the app. A held message that then fails comes back into the box with the reason (or as a notification if you were elsewhere) |
| Dual SIM | The SIM picker lists each SIM with its name and number, so two SIMs from one carrier can be told apart. A conversation keeps the SIM it last used, including when you reply from a notification; otherwise your default SIM, then the system's |
| Organizer | 14 built-in categories plus your own (each custom category gets an icon you pick from 42, the plain tag by default, in Settings → Custom categories), per-conversation category with manual override, rules ("sender or text contains X, file under Y"), automatic re-parse |
| Conversation icons | The round picture at the left of each conversation is its category's icon in the category's colour (a receipt for Bills, a key for codes, the paw you chose for your own Pets category), not the first letter of the sender; the category chooser in a thread shows the same icons |
| Category bar | The categories sit along the bottom of the inbox, each with an icon and its unread count, and the bar scrolls to the one being shown. In Settings → Inbox categories you choose which categories are listed (hide the ones you never use; their messages stay under All, and the open category always keeps its tab) and their order (one place at a time with the arrows) |
| Category alerts | Each category has a switch: turn it off and its messages never raise a notification (messages, codes, transaction previews and scam warnings alike). They still arrive and stay in the inbox. Turning it off also clears what is already showing |
| Weekly spam review | A notification every Sunday at about 10:00 when spam is waiting ("12 spam messages in 5 conversations"); tapping it opens the inbox on Spam. Switch in Settings; blocked senders are not counted |
| Parser | Rule-based, on-device: OTP codes (purpose, validity), bank/card/UPI transactions (amount, balance, merchant, account), bill and due-date reminders, orders and tracking links, quick-reply options, spam/scam score with reasons |
| Finance | Monthly spend and income by currency, spend by merchant, budgets with alerts |
| Reminders | Upcoming bills, trips, deliveries, appointments; add to calendar; due-soon notifications |
| Protection | Spam and scam alerts with delete / block / "not spam"; blocked senders via the system block list |
| Search | FTS4 full-text search with prefix match and highlighting, plus optional **search by meaning**: a small embedding model (BGE-small, int8, bundled) runs on the phone, so "power bill" finds the electricity notice. Exact matches come first, then a "Similar in meaning" section; the indexing runs in the background, newest messages first, and the screen says how far it has got |
| Writing assistant | A button in the message box rewrites your draft on the phone with a small language model: **fix everything** (spelling, grammar, punctuation, capitals and text-speak in one go), **fix spelling and grammar** only, **more polite**, **friendlier** or **shorter**. You always see the suggestion first (*Use this*, *Try again*, *Keep mine*); nothing is replaced or sent for you. Two interchangeable engines (llama.cpp and Google LiteRT-LM), chosen in Settings. The model file is not in the app: you download it once in your browser and choose it in Settings, where its SHA-256 is checked. See [Writing assistant](#writing-assistant) |
| Translation | Tap and hold a message → **Translate**: pick where it is from (or let the app guess) and where to translate it into — about 40 languages — and the translation appears under the original, which is never changed. Incoming messages that are not in *your language* (Settings → Translation → Your language, the phone's by default) are translated on the phone automatically and kept under the message, using the writing assistant's model; a switch turns it off. Everything runs on the phone: the app has no network access. See [Translation](#translation) |
| Appearance | Settings → Appearance: light, dark or follow the system; a **pure black** option for the dark theme (saves power on OLED screens); and a colour theme: **Material You** (from your wallpaper, Android 12 or newer, the default) or one of six fixed palettes: Ocean, Forest, Sunset, Rose, Violet, Graphite. Changes show at once, and the choice is in backups. Every palette's text and background colours are contrast-checked by a unit test, in light, dark and pure black |
| Settings | Grouped on rounded cards, each row with an icon on a coloured round background; the inbox's More menu has icons too |
| Help and about | Settings → Help and about, or the inbox's More menu → Help and about: the version, a **Help and FAQ** with steps for each feature and what to try when something does not work, a **Read me**, a **Privacy and permissions** page that explains every permission the app holds (a unit test checks the list against the manifest and an on-device test against the installed app, so it cannot drift), and **Open-source licences** with the full texts shipped in the app. The same content is in this README |
| Codes | Optional auto-delete of code messages after use or N hours |
| Backup | Encrypted backup (AES-256-GCM, PBKDF2) to a folder you choose, streamed in authenticated chunks so large attachments do not need to fit in memory; optional "Include MMS and attachments" (with a size estimate); automatic backups with the passphrase held in the Android Keystore; the whole file is verified before anything is restored, and a merge-restore never duplicates messages (older text-only backups still restore) |

Not implemented on purpose: cloud accounts, ads, analytics, crash reporting, premium purchase and any network
call of its own (MMS transfers go through the platform's MMS service). The app does not even hold the `INTERNET`
permission, and a test checks that. Not implemented yet: generated explanations and AI spam or anomaly scores.

## Writing assistant

Tap the sparkle button next to Send, pick how to rewrite the draft, read the suggestion, and tap *Use this* (or *Try again*, or *Keep mine*).
It works in a conversation and in a new message. Everything runs on the phone; the app has no network access.

**What it offers.** Fix everything (the first choice: spelling, grammar, punctuation, capital letters, text-speak such as "u" and "thx" written out, wording left alone otherwise); fix spelling and grammar only; more polite; friendlier; shorter. Drafts up to 500 characters. The first rewrite after a
while takes a few seconds longer because the model has to load; the model is freed again after two minutes without use, or when Android asks for memory.

**Setting it up** (Settings → Writing assistant):

1. *Get the model* opens your browser on a pinned download for the selected engine (the app does not download anything itself; it has no `INTERNET` permission).
2. *Choose model file* and pick the downloaded file. The app copies it into private storage that is not backed up, checking its SHA-256 on the way;
   a file that is not one of the two below is refused and nothing is kept. The engine switches to match the file you chose.
3. *Engine* chooses between the two; *Remove model* deletes the file; the *Rewrite helper* switch hides the button.

| Engine | Model (Apache-2.0) | File | Size | SHA-256 |
|---|---|---|---|---|
| llama.cpp (default) | Qwen2.5 0.5B Instruct, Q4_K_M | `qwen2.5-0.5b-instruct-q4_k_m.gguf` | 491,400,032 B | `74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db` |
| LiteRT-LM | Granite 4.0 350M, q8 | `granite-4.0-350m_q8_ekv1280.litertlm` | 468,209,584 B | `c8e9a29493f62b7c44461fb36980987c4c1454c75e95f57ba0539a8edc9dce76` |

Downloads: [Qwen](https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/tree/9217f5db79a29953eb74d5343926648285ec7e67) and
[Granite](https://huggingface.co/litert-community/granite-4.0-350m-litert-lm/tree/6f1e9bce89b174930a79de82d0dcdede708f8c34) (pinned commits).
Both engines run on the CPU. Adding another model means adding it to `ModelCatalog` with its checksum, after scoring it with `tools/rephrase-eval`.

**How it keeps the meaning.** The prompts (`app/src/main/assets/rephrase/prompts.json`, shared with the evaluation script) give the model three worked examples
per rewrite type. Before a suggestion is shown, `RephraseGuard` rejects an answer that drops or changes a number, amount, link, e-mail address, weekday or dated month,
invents a number, loses a name or an ALL-CAPS word, adds an emoji, turns a statement into a question (or the reverse), swaps "I" and "you", is much longer or shorter than the draft,
repeats the instructions, or is not shorter when asked to shorten. A rejected answer is retried once with a different roll of the dice; if that fails too, the draft is left alone and the sheet says so.

**What was measured, and what was not.** On the x86_64 emulator CPU, on 16 drafts that I wrote (5 rewrite types; "shorter" only on drafts of 55 characters or more, so 72 rewrites),
scored with the same safety checks plus hand-written who-does-what checks (and, for "fix everything", a capital letter, a full stop and no text-speak left):

| Engine and model | Correct | Already fine, left as is | Declined | Wrong but shown | Average time per rewrite |
|---|---|---|---|---|---|
| llama.cpp, Qwen2.5 0.5B Q4_K_M (default) | 58 | 8 | 4 | 2 | 1.08 s (3.4 s including loading, first answer) |
| LiteRT-LM, Granite 4.0 350M q8 | 50 | 8 | 8 | 6 | 3.0 s |

"Fix everything" alone, of 16 drafts, 4 of which were already clean: Qwen fixed 12, left the 4 clean ones alone and got none wrong; Granite fixed 11, left 5 alone (so it missed one draft that needed fixing)
and showed nothing wrong.

Also tried before the fifth type was added and not offered: Qwen2.5 1.5B on llama.cpp (40 correct of 56, 2.3 s, so bigger was not better here), Qwen3 0.6B (33) and SmolLM2 360M (31) on LiteRT-LM.
Qwen3.5 0.8B would not run on the emulator.

- These are emulator CPU numbers on a small, self-written set that I also tuned the prompts on; expect worse on real messages. The arm64 build
  (including the runtime choice among its seven CPU variants, armv8.0 to armv9.2) is what ships in `release/`.
- "Shorter" is the weak one: roughly half of the shortened drafts were acceptable. It can drop a clause that matters ("because I'm running late") and still pass the checks, which only protect facts, not every idea.
- The checks cannot see who did what or an invented claim ("…I am waiting for your response" was added to "I'm waiting" in one test). That is why a suggestion is always shown first, with a reminder to read it.
- GPU and NPU acceleration (LiteRT-LM supports both) is not used; neither was tried.
- The engine and switch settings are not part of backups, and neither are the model files.
- "Fix everything" can still rewrite more than was asked (it expanded "14 Jan" to "14th January" and "Dr" to "Dr." in a test, which the checks allow because the facts are unchanged).

## Translation

Translation reuses the writing assistant's model: the same file, the same engine choice, set up the same way (Settings → Writing assistant). Once a model is installed, translation needs nothing more.

**Two ways to translate.** Tap and hold a message and choose **Translate**: the sheet asks where the message is from — *Auto* (the app guesses) or a language you pick — and where to translate it into (default: *your language*, from Settings → Translation). The translation is stored under the message in the thread, and the original text is never touched or sent. Separately, *Auto-translate* (on by default) translates incoming messages that are not in your language as they arrive, so they appear translated a few seconds after the message. Messages that are already in your language are left alone, and nothing happens without a model.

**How the language is guessed, offline.** The writing system decides for most non-Latin scripts (Devanagari → Hindi, Arabic → Arabic, CJK → Chinese unless kana are present, and so on). For Latin-script text the app counts a handful of each language's most common words and needs at least two hits; when it cannot tell, it assumes your own language and the guess can be overridden in the sheet. The guessed source is only used to label the sheet and to tell the model where the text comes from.

**What is stored.** One translation per message and target language (Room, in the local index). Re-translating into the same language replaces it; a message whose text changes loses its translations. Translations are derived data and are not part of backups, which carry the original messages.

**How the answer is checked.** The prompts (`app/src/main/assets/translate/prompts.json`) give the model worked examples in the requested direction and tell it to reply with only the translation. An answer is kept only if it is non-empty, not the same text back (that means *already in this language*), not a labelled answer ("Translation: …"), and — for languages with their own script — actually written in that script. A rejected answer is asked once more with a different roll of the dice. A translation is always shown next to the original: a small model can get the meaning wrong, and the original is never replaced.

- Quality of a 0.5 B–0.35 B model at translation has not been measured on a corpus the way the rewrites were; expect occasional mistranslations, which is why the original always stays. The model and engine can be swapped in Settings.

## Build and test

Needs JDK 21 and the Android SDK (platform 37). Point Gradle at them with the environment (or a `local.properties`
with `sdk.dir=...`, which is not committed); no root is needed:

```
export JAVA_HOME=/path/to/jdk-21
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleDebug          # debug APK
./gradlew testDebugUnitTest      # 354 JVM tests: parser, SMS segments, long-text policy, backup, MMS codec, tokenizer, vector math, conversation keys (SMS and MMS thread splitting), category order, SIM choice, spam schedule, writing assistant (prompts, safety checks, model store, service), colour palettes (contrast), hidden categories, category icons, the send hold, the in-app guide
./gradlew assembleRelease        # R8-minified release (unsigned)
```

The first build downloads the 34 MB search model (cached in `~/.gradle/aismo-models`) and the llama.cpp v1.0.0 source (cached in `~/.gradle/aismo-sources`),
each from a pinned address and checked against a pinned SHA-256; that is the only network use and it is at build time, and after that builds work offline.
The native code needs NDK 29.0.13113456 and CMake 3.31.6 (install them with `sdkmanager`). ONNX Runtime adds about 13 MB (compressed) per architecture and the
writing assistant's engines about 12 MB more, so the default build includes arm64-v8a and x86_64 (for the emulator); `-Pabis=arm64-v8a` builds a phone-only APK
of about 59 MB (the two-architecture default is about 96 MB).

Instrumented tests (121, plus 3 live-network tests behind `-e live true`, seeders behind `-e seed true` / `-e seedsearch true`, and the writing-assistant model harness behind `-e llm_backend` / `-e llm_model`) run against the real SMS provider and need the app to hold the default-SMS role.
Gradle uninstalls the app after `connectedAndroidTest`, which drops the role, so install and grant it yourself:

```
./gradlew assembleDebug assembleDebugAndroidTest
adb install -r -t app/build/outputs/apk/debug/app-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
for p in READ_SMS SEND_SMS RECEIVE_SMS READ_CONTACTS READ_PHONE_STATE READ_PHONE_NUMBERS POST_NOTIFICATIONS; do
  adb shell pm grant aismo.sms android.permission.$p; done
adb shell cmd role add-role-holder --user 0 android.app.role.SMS aismo.sms
adb shell am instrument -w aismo.sms.test/androidx.test.runner.AndroidJUnitRunner
```

Emulator notes: the Android 17 preview image crashes its own `system_server`; use the stable API 36.1
`google_apis` x86_64 image. On this host only `-gpu swangle_indirect` starts the emulator. Inject incoming
SMS with `adb emu sms send <sender> "<text>"`.

### MMS testing

The MMS PDU codec is unit-tested against byte vectors built by hand from the OMA specs, plus fuzzing. The engine
tests drive the real MMS provider with the same callbacks the platform delivers. Three `-e live true` tests run real
transfers (an MMS with a photo, a download, and a 700-character text converted to one MMS) through the platform MMS service against a fake MMSC (`tools/fake-mmsc/fake_mmsc.py`), whose own PDU
reader is independent of the app's codec. On an emulator (root image):

```
python3 -I tools/fake-mmsc/fake_mmsc.py 8089 /tmp/mmsc-log &
adb root
adb shell "sqlite3 /data/user_de/0/com.android.providers.telephony/databases/telephony.db \
  \"update carriers set mmsc='http://10.0.2.2:8089/mms', mmsproxy='', mmsport='' where numeric='310260'\""
adb shell cmd phone cc set-value -p enabledMMS true      # this emulator image ships with MMS disabled
adb shell am instrument -w -e live true -e class aismo.sms.MmsLiveNetworkTest \
  aismo.sms.test/androidx.test.runner.AndroidJUnitRunner
```

## Repository layout

```
app/                    the Android application (package aismo.sms)
  src/main/             source, resources, manifest
  src/test/             JVM unit tests
  src/androidTest/      instrumented tests (real SMS/MMS providers)
  schemas/              exported Room schemas (used by the migration test)
tools/fake-mmsc/        fake MMS centre for the live MMS tests
tools/embedding-reference/  Python scripts that made the reference data for the search tests
tools/icon/             render_icon.py draws the app icon (a lit, embossed bubble) from a height map; `python3 tools/icon/render_icon.py` rewrites the icon files
docs/images/            the icon for this README
tools/rephrase-eval/    the writing assistant's test drafts and a script that scores its prompts on any GGUF model (same prompts.json as the app)
THIRD_PARTY_NOTICES.md  licences of the bundled model, runtimes and engines (texts in app/src/main/assets/licenses)
release/                prebuilt APK and its checksum
```

## Releases

`release/` keeps the current prebuilt, R8-minified APK (minSdk 29, so Android 10 or newer) and a `SHA256SUMS` file; check it
with `sha256sum -c SHA256SUMS`. The public Play release notes for each version live in
[`release_notes.md`](release_notes.md).

| File | Version | What it is |
|---|---|---|
| `aismo-0.7.0-arm64-v8a.apk` | 0.7.0 | The 0.7.0 release, 61 MB: the full app — 14 built-in and your own categories, search by keywords and by meaning, the [writing assistant](#writing-assistant) and [translation](#translation), MMS and long texts, undo send, dual SIM, locked categories and encrypted backups. **arm64-v8a phones only**: it will not install on an x86 phone or on a 32-bit-only phone |

0.7.0 is signed with a dedicated release key, not the debug key, so Android will not install it over an earlier aismo build (a beta, or
one installed from this repository). To move: back up your messages and settings in the old app (Settings → Backup and restore),
uninstall it, install this APK, then restore the backup.

Rebuild with `./gradlew assembleRelease` (add `-Pabis=arm64-v8a` for the phone build) and sign the result with your own key
(`zipalign`, then `apksigner sign`) before distributing it.

## Architecture

```
telephony/   receivers, sender, notifications            (Android entry points)
data/        Room database, repository, SMS and MMS providers, MMS engine, settings, contacts, blocked numbers
domain/      parser, categories, conversation keys, finance, MMS PDU codec and layout, WordPiece tokenizer, vector math (pure Kotlin)
data/ai/     bundled model, ONNX Runtime embedder, semantic index (vectors, indexing, search)
domain/rephrase/  writing assistant: prompts, safety checks (RephraseGuard), the Rephraser that retries once (pure Kotlin)
domain/translate/  translation: the language list, the offline language guesser, prompts, the Translator that retries once (pure Kotlin)
domain/llm/    the chat contract shared by both features (TextGenerator, chat messages)
data/llm/    model catalogue and checksum-verified model store, the two engines (llama.cpp through a small JNI bridge in src/main/cpp, LiteRT-LM), RephraseService (the one owner of the loaded model, shared by the writing assistant and translation)
backup/      record format, framing, chunked stream crypto, backup service, Keystore vault
work/        WorkManager workers (import, OTP cleanup, reminders, automatic backup, search indexing)
ui/          Compose screens and view models
```

The system SMS and MMS providers stay the source of truth for messages (MMS rows use a separate id range in the
index because the two providers' ids overlap). Room holds an index of them plus
what the provider cannot: derived facts (OTPs, transactions, reminders, incidents), conversations,
categories, rules, budgets and stars. Conversations are keyed by sender (business senders by their name token,
phone numbers by their last 9 digits), and by type: SMS use the `p:`/`s:`/`g:` form of that key and MMS the
`m:`/`gm:` form, so a sender has one thread of each.

## Known limitations

- MMS has been verified against a fake MMSC, not against a real carrier.
  Carrier quirks (APN proxies, odd Content-Location values, non-standard SMIL) are the likely source of problems.
- MMS gaps: no subject field when composing, no e-mail recipients, no delivery or read reports.
- Long-text conversion uses the carrier's own threshold when the carrier publishes one; many do not, so only the
  default (more than 4 parts) and your own setting have been exercised.
- MMS backups hold each attachment as one block, so an attachment over 64 MB, or one the provider cannot read, is
  left out; the backup screen says how many. Restored MMS keep their text, parts, dates, boxes and stars; a
  restored message that was still sending comes back as failed, not queued.
- Restore matches an existing MMS by second, participants and attachment names/sizes, and an SMS by sender,
  date and text. A backup taken while a message was still in flight can restore a second copy of it.
- The modem reports each part of a multipart SMS seconds apart and each report rewrites the
  stored row by id. A report that arrives after the row was deleted and its id reused would update the wrong
  row; the tests wait for sends to finish instead of the app guarding against it.
- Search by meaning (measured on a small, hand-made set):
  - The ranking is good on short everyday queries: on a hand-made set of 40 messages and 18 queries the best result was right for 12 of 14 queries
    and nothing was shown for the 4 queries with no answer. The similarity floor is a compromise, because this small model scores unrelated texts 0.4 to 0.6:
    about 77% of the results shown were relevant and about 71% of the relevant messages were shown (averaged per query). That set was written and tuned by the same
    person, so expect less on a real inbox, including the occasional loosely related result.
  - The model is English-centred and weakest on single-word queries (`scam` found the phishing messages only at ranks 4 to 12). A phrase works better than a word.
  - Reading the inbox takes about 10 ms per message on the emulator, so 50,000 messages is roughly nine minutes of background work; a phone may be
    slower or faster. A search over 50,000 stored vectors took about 110 ms.
  - Messages are cut to the first 128 tokens, so a very long text is searched by its opening. Vectors are derived data: they are not backed up, and a
    restored inbox is read again.
  - The tokenizer matches the reference Python tokenizer on every case tested (29 hand-picked and 4,000 random Unicode strings on the JVM, 529 on the device),
    except that a literal `[CLS]` or `[SEP]` typed in a message is read as ordinary text. The model's output matches the reference to better than 0.99 cosine
    on every text, and to four decimals on most; the rest is integer-kernel rounding that differs between CPUs.
  - The shipped phone build is about 41 MB (it was 4 MB before): ONNX Runtime is 13 MB compressed per architecture and the model 23 MB.
- Category alerts and the SIM features (verified against the real notification path):
  - Muting a category silences the notifications caused by a message arriving in it. Reminder and budget alerts come from facts stored earlier and are not
    affected, and the weekly spam review still arrives when Spam is muted (switch that one off in Settings).
  - The weekly review is scheduled with WorkManager from the coming Sunday at 10:00, so the system may run it later to save battery, and it needs the app
    not to be force-stopped. It says nothing when there is no spam.
  - Categories move one place at a time with arrows, not by dragging.
  - The dual-SIM screens were checked with a temporarily simulated two-SIM list (name, number, picker, per-thread default), and the SIM-choice rules
    are covered by unit tests and by tests that set the subscription ids in the index. The system provider only stores subscription ids of SIMs that
    exist, so a thread's SIM comes from what the phone really recorded.
  - Some carriers or Android versions do not report a SIM's number; the picker then says "Number not available".
- Undo send (checked against the real SMS provider and by hand):
  - The wait is held in memory, so the guarantee against losing a message is that **leaving the app sends it** (the app going to the background, the screen turning off, switching apps). That means Undo only works while the app is open and in front; a held message cannot be undone from outside it.
  - A held message is not in the conversation yet, so it is not searchable or visible there until it is sent. The banner shows its text.
  - Only one message is held per conversation. A message sent while another is waiting makes the earlier one go out immediately.
  - Replies from a notification and the system's "respond via message" go out at once, without a hold.
  - Whether sending can still fail is only known when the wait ends, so a failure shows up late: back in the box with the reason if you are in that conversation, otherwise as a notification.
- App icon and name: the icon is drawn by `tools/icon/render_icon.py`. The themed (one-colour) icon of Android 13 and newer is a flat silhouette without the 3D effect, because a themed icon is a single colour by design. The home-screen label is "aismo" (the full name would be cut off); Settings → Apps shows "aismo: AI SMS Manager".
- Help and about: the pages are written by hand and describe the app as it is; the permission list is checked by tests, but the help text is not, so if a screen is renamed the text needs updating too.
- Category icons: a custom category's icon comes from a fixed set of 42 (no emoji, photos or your own pictures), and it is the category that has the icon, so a conversation shows the icon of the category it is filed under. Backups made before icons existed restore their categories with the plain tag. An icon was added to the database in a migration (version 3 to 4), tested with existing categories.
- Themes and the settings screen (checked in a few combinations):
  - The six fixed palettes are made from one hue each by a small generator, not by Google's colour tools, so they will not match what Material You would make from the same colour. The unit test checks contrast (text on its background, 4.5:1 or better for body text, 7:1 for the main text), not whether they look good; try them.
  - "Pure black" only changes the dark theme, and the surfaces of cards and sheets stay dark grey so they remain visible against it.
  - Material You needs Android 12; on Android 10 and 11 it is shown as unavailable and the app uses Ocean. That path was written but not run.
  - Hiding a category takes its messages out of the All view but keeps its tab in the bar (the tab is how the category is opened and un-hidden). A message from a hidden category still raises its notification unless alerts are off for that category too, and the spam review notification still opens Spam.
  - There is no navigation drawer: "settings drawer" is the More menu and the Settings screen.
- Parser rules are hand-written and generic with an India-leaning brand list; accuracy on a given market
  needs rules for that market (add them in `domain/parser`, or as in-app rules).
- A bare `$` is read as the device region's dollar currency.
- Phone numbers are keyed by their last 9 digits, which merges national and international forms but could
  merge two numbers that differ only in a leading digit (in each of the SMS and MMS threads).
- Automatic backups run when WorkManager allows (battery not low); they are not exact-time.
