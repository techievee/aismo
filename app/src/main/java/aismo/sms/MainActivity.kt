package aismo.sms

import android.app.Activity
import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.FragmentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.launch
import aismo.sms.data.settings.AppSettings
import aismo.sms.telephony.Actions
import aismo.sms.ui.BackupScreen
import aismo.sms.ui.BlockedScreen
import aismo.sms.ui.CategoryOrderScreen
import aismo.sms.ui.CategoriesScreen
import aismo.sms.ui.FinanceScreen
import aismo.sms.ui.InboxActions
import aismo.sms.ui.InboxScreen
import aismo.sms.ui.NewMessageScreen
import aismo.sms.ui.OnboardingScreen
import aismo.sms.ui.ProtectionScreen
import aismo.sms.ui.RemindersScreen
import aismo.sms.ui.RulesScreen
import aismo.sms.ui.SearchScreen
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import aismo.sms.domain.guide.Guide
import aismo.sms.ui.AboutScreen
import aismo.sms.ui.AppearanceScreen
import aismo.sms.ui.GuideScreen
import aismo.sms.ui.LicenceTextScreen
import aismo.sms.ui.LicencesScreen
import aismo.sms.ui.PrivacyScreen
import aismo.sms.ui.LocalCustomCategoryIcons
import aismo.sms.ui.SettingsScreen
import aismo.sms.ui.StarredScreen
import aismo.sms.ui.ThreadScreen
import aismo.sms.ui.canReadSms
import aismo.sms.ui.theme.AismoTheme

object Routes {
    const val ONBOARDING = "onboarding"
    const val INBOX = "inbox"
    const val THREAD = "thread/{key}?address={address}"
    const val NEW = "new?to={to}&body={body}"
    const val SEARCH = "search"
    const val STARRED = "starred"
    const val FINANCE = "finance"
    const val REMINDERS = "reminders"
    const val PROTECTION = "protection"
    const val BLOCKED = "blocked"
    const val SETTINGS = "settings"
    const val CATEGORIES = "settings/categories"
    const val CATEGORY_ORDER = "settings/category-order"
    const val RULES = "settings/rules"
    const val BACKUP = "settings/backup"
    const val APPEARANCE = "settings/appearance"
    const val ABOUT = "about"
    const val HELP = "help"
    const val README = "readme"
    const val PRIVACY = "privacy"
    const val LICENCES = "licences"
    const val LICENCE_TEXT = "licences/{asset}?title={title}"

    fun licenceText(asset: String, title: String) = "licences/${Uri.encode(asset)}?title=${Uri.encode(title)}"
    fun thread(key: String, address: String = "") = "thread/${Uri.encode(key)}?address=${Uri.encode(address)}"
    fun new(to: String = "", body: String = "") = "new?to=${Uri.encode(to)}&body=${Uri.encode(body)}"
}

/**
 * A [FragmentActivity] rather than a plain [androidx.activity.ComponentActivity]: the biometric library's prompt takes
 * a FragmentActivity, so without this the fingerprint unlock would silently fall back to the PIN.
 */
class MainActivity : FragmentActivity() {
    private val pendingIntent: MutableState<Intent?> = mutableStateOf(null)

    /** See [RequestCodeRemapper] for why this exists; it survives process death through the instance state below. */
    private val remapper = RequestCodeRemapper()

    override fun onCreate(savedInstanceState: Bundle?) {
        // Restore before super so the translation is in place when the framework delivers pending results.
        savedInstanceState?.let { remapper.restore(it.getIntArray(STATE_REMAP), it.getInt(STATE_NEXT_LOW, 0x1000)) }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pendingIntent.value = intent
        // Read once before the first frame so a chosen theme is there from the start instead of flashing the default.
        val first = runBlocking { container.settings.current() }
        setContent {
            val settings by container.settings.settings.collectAsStateWithLifecycle(first)
            val customIcons by remember { container.db.config().observeCategories().map { list -> list.associate { it.key to it.icon } } }
                .collectAsStateWithLifecycle(emptyMap())
            CompositionLocalProvider(LocalCustomCategoryIcons provides customIcons) {
                AismoTheme(settings.theme, settings.palette, settings.pureBlack) { AppNav(pendingIntent) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingIntent.value = intent
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putIntArray(STATE_REMAP, remapper.savedPairs())
        outState.putInt(STATE_NEXT_LOW, remapper.nextLow)
        super.onSaveInstanceState(outState)
    }

    /**
     * Every [startActivityForResult] / [startIntentSenderForResult] the activity-result registry sends goes
     * through [remap] here; the matching result comes back through [onActivityResult] /
     * [onRequestPermissionsResult], which translate the code back and hand it to the registry directly.
     *
     * The result cannot simply be forwarded to [super]: this is a [FragmentActivity], and it treats any
     * request code with the upper 16 bits set — precisely the band the registry allocates from — as a
     * fragment result and swallows it as an "unknown Fragment" result before the registry ever looks it up,
     * so no registry callback (photo picker, document picker, role picker, ...) would ever fire. Dispatching
     * straight to [activityResultRegistry] is the same call [ComponentActivity] makes; codes the registry
     * does not own are fragment codes (the biometric prompt) and still reach [super].
     *
     * (Permission requests cannot be remapped on the way out: the framework's [Activity.requestPermissions]
     * is final. But their results do come back through [onRequestPermissionsResult] carrying the registry's
     * high code, so they need the same direct dispatch.)
     */
    override fun startActivityForResult(intent: Intent, requestCode: Int, options: Bundle?) {
        super.startActivityForResult(intent, remapper.remap(requestCode), options)
    }

    override fun startIntentSenderForResult(
        intent: IntentSender,
        requestCode: Int,
        fillInIntent: Intent?,
        flagsMask: Int,
        flagsValues: Int,
        extraFlags: Int,
        options: Bundle?,
    ) {
        super.startIntentSenderForResult(intent, remapper.remap(requestCode), fillInIntent, flagsMask, flagsValues, extraFlags, options)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val out = remapper.unremap(requestCode)
        if (!activityResultRegistry.dispatchResult(out, resultCode, data)) {
            super.onActivityResult(out, resultCode, data)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        val out = remapper.unremap(requestCode)
        if (
            !activityResultRegistry.dispatchResult(
                out,
                Activity.RESULT_OK,
                Intent()
                    .putExtra(ActivityResultContracts.RequestMultiplePermissions.EXTRA_PERMISSIONS, permissions)
                    .putExtra(
                        ActivityResultContracts.RequestMultiplePermissions.EXTRA_PERMISSION_GRANT_RESULTS,
                        grantResults,
                    ),
            )
        ) {
            super.onRequestPermissionsResult(out, permissions, grantResults)
        }
    }

    private companion object {
        const val STATE_REMAP = "activity_result_rc_remap"
        const val STATE_NEXT_LOW = "activity_result_rc_next"
    }
}

@Composable
private fun AppNav(pending: MutableState<Intent?>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val onboardingDone by produceState<Boolean?>(null) { value = context.container.settings.current().onboardingDone }
    val done = onboardingDone ?: return
    val nav = rememberNavController()
    val start = if (done && context.canReadSms()) Routes.INBOX else Routes.ONBOARDING

    LaunchedEffect(pending.value) {
        val intent = pending.value ?: return@LaunchedEffect
        if (start == Routes.ONBOARDING) return@LaunchedEffect
        handleIntent(nav, intent, context.container)
        pending.value = null
    }

    NavHost(navController = nav, startDestination = start) {
        composable(Routes.ONBOARDING) {
            OnboardingScreen(onContinue = {
                scope.launch { context.container.settings.update { it.copy(onboardingDone = true) } }
                nav.navigate(Routes.INBOX) { popUpTo(Routes.ONBOARDING) { inclusive = true } }
            })
        }
        composable(Routes.INBOX) {
            InboxScreen(
                InboxActions(
                    openThread = { nav.navigate(Routes.thread(it)) },
                    newMessage = { nav.navigate(Routes.new()) },
                    search = { nav.navigate(Routes.SEARCH) },
                    finance = { nav.navigate(Routes.FINANCE) },
                    reminders = { nav.navigate(Routes.REMINDERS) },
                    protection = { nav.navigate(Routes.PROTECTION) },
                    blocked = { nav.navigate(Routes.BLOCKED) },
                    starred = { nav.navigate(Routes.STARRED) },
                    settings = { nav.navigate(Routes.SETTINGS) },
                    about = { nav.navigate(Routes.ABOUT) },
                ),
            )
        }
        composable(
            Routes.THREAD,
            arguments = listOf(
                navArgument("key") { type = NavType.StringType },
                navArgument("address") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { entry ->
            ThreadScreen(
                key = entry.arguments?.getString("key").orEmpty(),
                addressHint = entry.arguments?.getString("address")?.ifEmpty { null },
                onBack = { nav.popBackStack() },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
            )
        }
        composable(
            Routes.NEW,
            arguments = listOf(
                navArgument("to") { type = NavType.StringType; defaultValue = "" },
                navArgument("body") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { entry ->
            NewMessageScreen(
                initialTo = entry.arguments?.getString("to").orEmpty(),
                initialBody = entry.arguments?.getString("body").orEmpty(),
                onBack = { nav.popBackStack() },
                onOpenThread = { key, address -> nav.navigate(Routes.thread(key, address)) { popUpTo(Routes.INBOX) } },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.SEARCH) { SearchScreen(onBack = { nav.popBackStack() }, onOpen = { k, a -> nav.navigate(Routes.thread(k, a)) }) }
        composable(Routes.STARRED) { StarredScreen(onBack = { nav.popBackStack() }, onOpen = { k, a -> nav.navigate(Routes.thread(k, a)) }) }
        composable(Routes.FINANCE) { FinanceScreen(onBack = { nav.popBackStack() }, onOpen = { k, a -> nav.navigate(Routes.thread(k, a)) }) }
        composable(Routes.REMINDERS) { RemindersScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.PROTECTION) { ProtectionScreen(onBack = { nav.popBackStack() }, onOpen = { k, a -> nav.navigate(Routes.thread(k, a)) }) }
        composable(Routes.BLOCKED) { BlockedScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { nav.popBackStack() },
                onCategories = { nav.navigate(Routes.CATEGORIES) },
                onCategoryOrder = { nav.navigate(Routes.CATEGORY_ORDER) },
                onRules = { nav.navigate(Routes.RULES) },
                onBackup = { nav.navigate(Routes.BACKUP) },
                onAppearance = { nav.navigate(Routes.APPEARANCE) },
                onHelp = { nav.navigate(Routes.HELP) },
                onAbout = { nav.navigate(Routes.ABOUT) },
                onPrivacy = { nav.navigate(Routes.PRIVACY) },
            )
        }
        composable(Routes.CATEGORIES) { CategoriesScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.CATEGORY_ORDER) { CategoryOrderScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.RULES) { RulesScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.BACKUP) { BackupScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.APPEARANCE) { AppearanceScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.ABOUT) {
            AboutScreen(
                onBack = { nav.popBackStack() }, onHelp = { nav.navigate(Routes.HELP) }, onReadme = { nav.navigate(Routes.README) },
                onPrivacy = { nav.navigate(Routes.PRIVACY) }, onLicences = { nav.navigate(Routes.LICENCES) },
            )
        }
        composable(Routes.HELP) { GuideScreen("Help and FAQ", Guide.help, onBack = { nav.popBackStack() }, expandable = true, intro = "Tap a topic to open it.") }
        composable(Routes.README) { GuideScreen("Read me", Guide.readme, onBack = { nav.popBackStack() }, expandable = false) }
        composable(Routes.PRIVACY) { PrivacyScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.LICENCES) { LicencesScreen(onBack = { nav.popBackStack() }, onOpenText = { asset, title -> nav.navigate(Routes.licenceText(asset, title)) }) }
        composable(
            Routes.LICENCE_TEXT,
            arguments = listOf(navArgument("asset") { type = NavType.StringType }, navArgument("title") { type = NavType.StringType; defaultValue = "Licence" }),
        ) { entry ->
            LicenceTextScreen(entry.arguments?.getString("asset").orEmpty(), entry.arguments?.getString("title").orEmpty(), onBack = { nav.popBackStack() })
        }
    }
}

/** Routes notification taps, `sms:` links and shared text to the right screen. */
private fun handleIntent(nav: NavHostController, intent: Intent, container: AppContainer) {
    val key = intent.getStringExtra(Actions.EXTRA_KEY)
    val category = intent.getStringExtra(Actions.EXTRA_CATEGORY)
    when {
        key != null -> nav.navigate(Routes.thread(key))
        category != null -> {
            container.requestedCategory.value = category
            nav.navigate(Routes.INBOX) { popUpTo(Routes.INBOX) { inclusive = false }; launchSingleTop = true }
        }
        intent.action == Intent.ACTION_SEND && intent.type == "text/plain" ->
            nav.navigate(Routes.new(body = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()))
        intent.data?.scheme in setOf("sms", "smsto", "mms", "mmsto") -> {
            val data = intent.data!!
            val to = data.schemeSpecificPart.substringBefore('?')
            val body = data.getQueryParameter("body") ?: intent.getStringExtra("sms_body").orEmpty()
            nav.navigate(Routes.new(to, body))
        }
    }
}
