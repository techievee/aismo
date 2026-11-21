package aismo.sms.domain.guide

/** A titled block of the in-app guide: some sentences, then numbered steps, then bullet points. Any part may be empty. */
data class GuideSection(
    val id: String,
    val title: String,
    val paragraphs: List<String> = emptyList(),
    val steps: List<String> = emptyList(),
    val bullets: List<String> = emptyList(),
)

/** Why the app holds a permission, in plain words. */
data class PermissionInfo(val permission: String, val label: String, val why: String)

/** Something the app is built from or uses, with its licence. The texts are in assets/licenses. */
data class LibraryInfo(val name: String, val licence: String, val role: String, val url: String, val licenceAsset: String? = null, val noticesAsset: String? = null)

/**
 * What the About, Read me, Help and Privacy screens say. It lives in one place, as data, so it can be checked: the
 * permission list must match what the app really asks for, and every licence file named here must exist.
 */
object Guide {
    const val SOURCE_URL = "https://github.com/techievee/aismo"
    const val ASSET_DIR = "licenses"

    val readme: List<GuideSection> = listOf(
        GuideSection(
            "what", "What aismo is",
            paragraphs = listOf(
                "aismo (AI SMS Manager) is a text and picture messaging app. It sorts your messages for you, so one-time codes, bank alerts, bills, orders, travel and promotions each have their own place, and it keeps everything on your phone.",
                "It was written from scratch as an original app. It has no account, no ads and no usage statistics.",
            ),
        ),
        GuideSection(
            "does", "What it can do",
            bullets = listOf(
                "Send and receive SMS and MMS (pictures, videos and group messages), with two SIMs.",
                "File messages into 14 built-in categories plus your own, which you can reorder, hide, mute, lock and give icons.",
                "Show one-time codes with a copy button, summarise bank alerts, keep track of bills, trips and deliveries, and warn about likely scams.",
                "Total your spending by month and by merchant, with budgets.",
                "Search by words, or by meaning: \"power bill\" finds the electricity notice.",
                "Rewrite your draft on the phone with a small AI model: fix everything, fix spelling and grammar, more polite, friendlier, or shorter.",
                "Hold a message for a few seconds after you tap Send, so you can undo it.",
                "Make encrypted backups to a folder you choose.",
                "Change the look: light, dark or pure black, with seven colour themes.",
            ),
        ),
        GuideSection(
            "private", "Private by design",
            paragraphs = listOf("aismo does not hold Android's internet permission, so it cannot connect to anything. Your messages are read, sorted and searched on this phone only."),
            bullets = listOf(
                "The AI features run entirely on the phone. Search by meaning uses a model built into the app. The writing assistant uses a model file that you download once in your browser and choose in Settings.",
                "Picture messages go through Android's own MMS service, as they do in any messaging app.",
                "A backup only goes where you save it.",
            ),
        ),
        GuideSection(
            "organize", "How messages are organized",
            paragraphs = listOf("aismo recognises messages with built-in rules that run on the phone: a code and what it is for, a bank alert with its amount and merchant, a bill with a due date. The category you see is its best guess."),
            bullets = listOf(
                "You can always move a conversation: open it, tap the three dots, then Change category.",
                "Settings → Rules files a sender or a phrase under a category every time.",
                "The built-in rules are general and will not know every sender. Add your own rules for the ones it misses.",
            ),
        ),
        GuideSection(
            "limits", "Good to know",
            bullets = listOf(
                "aismo is a young app. Some things, pictures and videos in particular, may behave differently depending on your phone or carrier.",
                "The AI can be wrong. A rewrite can change what you meant, so read it before you use it. Nothing is changed or sent without your tap.",
                "A message held by Undo send goes out as soon as you leave the app.",
                "Backups are encrypted with your passphrase. If you lose the passphrase, the backup cannot be opened.",
            ),
        ),
    )

    val help: List<GuideSection> = listOf(
        GuideSection(
            "getting-started", "Getting started",
            steps = listOf(
                "Open aismo and allow access to your messages and contacts.",
                "Tap the button or the banner to make aismo your default SMS app. Android needs this before an app can send and receive texts.",
                "Wait a moment while your messages are read and sorted. The category bar at the bottom of the inbox fills in as it goes.",
            ),
            paragraphs = listOf("You can go back to your old messaging app at any time in Android's settings, under Default apps."),
        ),
        GuideSection(
            "cant-send", "I can't send or receive messages",
            bullets = listOf(
                "aismo must be your default SMS app. If the inbox shows a banner asking for this, tap it.",
                "Check that the SMS and phone permissions are allowed: Android Settings, Apps, aismo, Permissions.",
                "On a phone with two SIMs, check which SIM the message box says it will send from, and tap it to change.",
                "A short code or a business name often does not accept replies. The message box tells you when that is the case.",
                "A message that failed to send stays in the conversation, marked as failed, and can be tried again.",
            ),
        ),
        GuideSection(
            "categories", "Categories, rules and icons",
            bullets = listOf(
                "To move one conversation, open it, tap the three dots, then Change category.",
                "Settings → Rules: file a sender or a phrase under a category every time.",
                "Settings → Custom categories: tap + to add your own. Pick an icon for it from 42 (the tag is the default). The icon is shown at the left of every conversation in that category and in the category bar.",
                "Settings → Inbox categories: show or hide categories in the bar, move them up or down, and turn off their alerts. A hidden category keeps its messages and its tab, but its messages no longer appear under All; tap the tab to open it and see them. Lock a category (the padlock) and it stays in the bar, with a padlock instead of its icon, and its messages only open after your fingerprint or the app's PIN.",
            ),
        ),
        GuideSection(
            "search", "Search",
            paragraphs = listOf(
                "Tap the magnifier in the inbox. Messages with your words come first. With Search by meaning switched on (Settings → Search) a \"Similar in meaning\" list follows, so \"power bill\" can find the electricity notice.",
                "That AI search is done by a small model built into aismo, running entirely on this phone. Your words and messages never leave it; the app has no internet permission at all.",
                "The first time, your messages are read in the background, newest first. Settings shows how far it has got.",
            ),
        ),
        GuideSection(
            "writing", "Rewriting your message with AI",
            steps = listOf(
                "Settings → Writing assistant → Get the model. Your browser opens on a download page for the model (about 470 to 490 MB).",
                "When it has downloaded, tap Choose model file and pick it. aismo checks that it is the right file and keeps a private copy.",
                "In a message, tap the wand next to Send and choose Fix everything, Fix spelling and grammar, More polite, Friendlier or Shorter.",
                "Read the suggestion, then tap Use this, Try again or Keep mine.",
            ),
            bullets = listOf(
                "There are two engines, llama.cpp and LiteRT-LM. Each needs its own model file. llama.cpp is the default.",
                "The first rewrite takes a few seconds longer while the model loads.",
                "Shorter is the least reliable choice. Always read the result.",
                "Remove model deletes the file to free the space.",
            ),
        ),
        GuideSection(
            "undo", "Undo send",
            steps = listOf("Settings → Sending → Undo send, then choose how long to hold a message: 3 to 30 seconds."),
            bullets = listOf(
                "After you tap Send, a banner counts down with Undo and Send now. Undo puts the text back in the message box.",
                "Press and hold the Send button to send straight away.",
                "A held message goes out at once if you leave the app or the screen turns off, so it is never lost.",
                "Replies from a notification are not held.",
            ),
        ),
        GuideSection(
            "mms", "Pictures, videos and group messages",
            bullets = listOf(
                "These are MMS. They need mobile data to be on and MMS to be allowed by your carrier.",
                "Settings → Multimedia messages: download automatically, download while roaming, and send long texts as MMS.",
                "A long text can go as one picture message instead of many text parts. The message box tells you when that will happen.",
                "A picture message that has not downloaded yet can be tapped to download it.",
            ),
        ),
        GuideSection(
            "dual-sim", "Two SIMs",
            paragraphs = listOf("The message box shows which SIM it will send from; tap it to change. A conversation remembers the SIM it last used. Set a default in Settings → General → Default SIM."),
        ),
        GuideSection(
            "backup", "Backup and restore",
            steps = listOf(
                "Settings → Data → Backup and restore.",
                "Choose a passphrase of at least 8 characters and save the backup to a folder.",
                "To restore, pick the backup file and enter its passphrase.",
            ),
            bullets = listOf(
                "The backup is encrypted. Without the passphrase it cannot be opened, and aismo cannot recover it.",
                "Switch on Include MMS and attachments to include pictures. The screen shows how big the backup will be.",
                "Restoring adds what is missing; it does not duplicate messages that are already there.",
                "Automatic backups can be switched on once a folder is chosen.",
            ),
        ),
        GuideSection(
            "notifications", "Notifications and one-time codes",
            bullets = listOf(
                "A one-time code is shown with a Copy button. Settings → One-time codes can delete code messages after you copy them, or after some hours.",
                "Settings → Inbox categories: turn Alerts off for a category to stop its notifications. Its messages still arrive.",
                "Weekly spam review: a Sunday-morning reminder to look through spam (Settings → Notifications).",
                "To silence one conversation, open it, tap the three dots, then Mute notifications.",
            ),
        ),
        GuideSection(
            "spam", "Spam, scams and blocking",
            paragraphs = listOf(
                "aismo scores each message and warns about likely scams. The Spam and scam alerts page in the inbox menu lists them, and lets you delete one, block its sender, or say it is not spam.",
                "To block a sender from a conversation, tap the three dots, then Block sender. Blocked senders are kept in Android's own block list and are listed under Blocked senders.",
            ),
        ),
        GuideSection(
            "appearance", "Colours and themes",
            paragraphs = listOf("Settings → Appearance. Choose System, Light or Dark, switch on Pure black for OLED screens, and pick Material You (colours from your wallpaper, Android 12 and newer) or Ocean, Forest, Sunset, Rose, Violet or Graphite."),
        ),
        GuideSection(
            "troubleshooting", "Something doesn't work",
            bullets = listOf(
                "Check that aismo is your default SMS app and that its permissions are on.",
                "If notifications do not appear, allow notifications for aismo in Android's settings and check that the category's Alerts are on.",
                "If the rewrite button says there is no model, follow Rewriting your message with AI above.",
                "If search by meaning finds nothing yet, give it time. It is still reading your messages, and Settings → Search shows how far it has got.",
                "Battery savers can delay background work such as automatic backups. Exclude aismo from battery saving if you rely on them.",
                "Still stuck? Open an issue on GitHub (About aismo → Source code).",
            ),
        ),
    )

    /** Every permission the installed app holds, and why. A test compares this with the manifest and with the installed app. */
    val permissions: List<PermissionInfo> = listOf(
        PermissionInfo("android.permission.READ_SMS", "Read your messages", "Shows, sorts and searches your messages."),
        PermissionInfo("android.permission.RECEIVE_SMS", "Receive texts", "Tells aismo when a text arrives."),
        PermissionInfo("android.permission.SEND_SMS", "Send texts", "Sends the texts you write."),
        PermissionInfo("android.permission.WRITE_SMS", "Store messages", "Saves sent and received messages, and marks them read or deletes them. Android only allows this to the default SMS app."),
        PermissionInfo("android.permission.RECEIVE_MMS", "Receive picture messages", "Tells aismo when a picture or group message arrives."),
        PermissionInfo("android.permission.RECEIVE_WAP_PUSH", "Receive picture message notices", "Receives the notice that a picture message is waiting to be downloaded."),
        PermissionInfo("android.permission.READ_CONTACTS", "Read your contacts", "Shows names instead of numbers and suggests contacts as you type. Contacts are never copied anywhere."),
        PermissionInfo("android.permission.READ_PHONE_STATE", "Know your SIMs", "Tells your SIMs apart so a message goes out on the one you choose."),
        PermissionInfo("android.permission.READ_PHONE_NUMBERS", "Know your number", "Shows each SIM's number in the SIM picker and recognises you in group messages."),
        PermissionInfo("android.permission.POST_NOTIFICATIONS", "Show notifications", "Notifies you of new messages, codes, reminders and alerts."),
        PermissionInfo("android.permission.VIBRATE", "Vibrate", "Lets notifications vibrate the phone."),
        PermissionInfo("android.permission.RECEIVE_BOOT_COMPLETED", "Start after a restart", "Restarts reminders, backups and the weekly spam review when the phone restarts."),
        PermissionInfo("android.permission.WAKE_LOCK", "Finish background work", "Added by Android's background-work library, so jobs such as a backup can finish with the screen off."),
        PermissionInfo("android.permission.ACCESS_NETWORK_STATE", "See the connection state", "Added by the same library, which waits for a usable connection. It does not let aismo connect to anything."),
        PermissionInfo("android.permission.FOREGROUND_SERVICE", "Run long jobs", "Added by the same library, for long jobs such as reading your messages for search."),
        PermissionInfo("android.permission.USE_BIOMETRIC", "Unlock secured categories", "Added by the biometric library, so aismo can ask for your fingerprint or face to unlock a category you have locked. It is only asked when something is locked, and it cannot grant aismo anything else."),
    )

    val libraries: List<LibraryInfo> = listOf(
        LibraryInfo("llama.cpp and ggml", "MIT", "Runs the writing assistant's GGUF models.", "https://github.com/ggml-org/llama.cpp", "llama.cpp-LICENSE.txt"),
        LibraryInfo("LiteRT-LM", "Apache-2.0", "Google's on-device language model runtime, the second writing assistant engine.", "https://github.com/google-ai-edge/LiteRT-LM", "litertlm-LICENSE.txt", "litertlm-ThirdPartyNotices.txt"),
        LibraryInfo("ONNX Runtime", "MIT", "Runs the search by meaning model.", "https://onnxruntime.ai", "onnxruntime-LICENSE.txt", "onnxruntime-ThirdPartyNotices.txt"),
        LibraryInfo("BGE-small-en-v1.5", "MIT", "The search by meaning model, built into the app.", "https://huggingface.co/BAAI/bge-small-en-v1.5", "bge-LICENSE.txt"),
        LibraryInfo("Qwen2.5-0.5B-Instruct and Granite 4.0 350M", "Apache-2.0", "The writing assistant models you can download yourself. They are not part of the app.", "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF"),
        LibraryInfo("Jetpack, Compose, Kotlin and other libraries", "Apache-2.0", "What the app itself is built with: screens, the database, background work and phone number handling.", "https://developer.android.com/jetpack"),
    )
}
