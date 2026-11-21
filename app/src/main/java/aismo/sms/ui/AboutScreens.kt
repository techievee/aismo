package aismo.sms.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import aismo.sms.R
import aismo.sms.domain.guide.Guide
import aismo.sms.domain.guide.GuideSection

/** Opens a page in the browser. The app has no internet access of its own; the browser does the connecting. */
private fun openLink(context: Context, url: String) {
    try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: ActivityNotFoundException) { }
}

/** The version name and code the app was built with, for example "0.3.0 (3)". */
fun versionLabel(context: Context): String = runCatching {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    "${info.versionName} (${info.longVersionCode})"
}.getOrDefault("unknown")

/** The front page of help and information: who the app is, and the way to everything written about it. */
@Composable
fun AboutScreen(onBack: () -> Unit, onHelp: () -> Unit, onReadme: () -> Unit, onPrivacy: () -> Unit, onLicences: () -> Unit) {
    val context = LocalContext.current
    SimpleScaffold("About aismo", onBack) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Image(painterResource(R.drawable.ic_aismo_logo), contentDescription = "aismo icon", modifier = Modifier.size(112.dp))
                Text("aismo", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                Text("AI SMS Manager", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Version ${versionLabel(context)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
            Text(
                "Your messages, sorted automatically and kept on your phone. aismo is open source under the MIT licence.",
                Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyLarge,
            )
            SettingsGroup("Help and information", Icons.Filled.Info, SettingsAccent.General) {
                NavRow(Icons.AutoMirrored.Filled.HelpOutline, SettingsAccent.General, "Help and FAQ", "How to do things, and what to try when something does not work", chevron = true, onClick = onHelp)
                NavRow(Icons.AutoMirrored.Filled.MenuBook, SettingsAccent.Organizer, "Read me", "What aismo is, what it can do, and its limits", chevron = true, onClick = onReadme)
                NavRow(Icons.Filled.PrivacyTip, SettingsAccent.Codes, "Privacy and permissions", "What stays on your phone, and why each permission is asked for", chevron = true, onClick = onPrivacy)
                NavRow(Icons.Filled.Gavel, SettingsAccent.Data, "Open-source licences", "The software and models aismo is built with", chevron = true, onClick = onLicences)
                NavRow(Icons.Filled.Code, SettingsAccent.Writing, "Source code", "${Guide.SOURCE_URL.removePrefix("https://")}. Opens in your browser.") { openLink(context, Guide.SOURCE_URL) }
            }
        }
    }
}

/** A page of the guide: the sections, each a card. In [expandable] mode (the help) only the title shows until it is tapped. */
@Composable
fun GuideScreen(title: String, sections: List<GuideSection>, onBack: () -> Unit, expandable: Boolean, intro: String? = null) {
    var open by rememberSaveable(title) { mutableStateOf(setOf<String>()) }
    SimpleScaffold(title, onBack) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (intro != null) item { Text(intro, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(sections, key = { it.id }) { section ->
                val expanded = !expandable || section.id in open
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column {
                        Row(
                            Modifier.fillMaxWidth().then(if (expandable) Modifier.clickable { open = if (expanded) open - section.id else open + section.id } else Modifier).padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(section.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            if (expandable) Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = if (expanded) "Collapse" else "Expand")
                        }
                        AnimatedVisibility(expanded) { SectionBody(section) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionBody(section: GuideSection) {
    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        section.paragraphs.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
        section.steps.forEachIndexed { i, step -> Row { Text("${i + 1}.", Modifier.padding(end = 8.dp), fontWeight = FontWeight.SemiBold); Text(step, style = MaterialTheme.typography.bodyMedium) } }
        section.bullets.forEach { Row { Text("•", Modifier.padding(end = 8.dp)); Text(it, style = MaterialTheme.typography.bodyMedium) } }
    }
}

@Composable
fun PrivacyScreen(onBack: () -> Unit) {
    SimpleScaffold("Privacy and permissions", onBack) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
            item {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Everything stays on this phone", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(
                        "aismo does not hold Android's internet permission, so it cannot send your messages, contacts or anything else to any server. " +
                            "Sorting, search and the AI features all run on the phone. Picture messages travel through Android's own MMS service, and a backup only goes where you save it.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "It has no account, no ads and no usage statistics. One library it uses, ONNX Runtime, asks for internet access and includes a client that reports usage. Both are removed from the app, and a test checks the installed app on every run.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text("Permissions", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                    Text("Every permission the app holds, and what it is for.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(Guide.permissions, key = { it.permission }) { p ->
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    headlineContent = { Text(p.label, fontWeight = FontWeight.Medium) },
                    supportingContent = {
                        Column {
                            Text(p.why)
                            Text(p.permission.removePrefix("android.permission."), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, fontFamily = FontFamily.Monospace)
                        }
                    },
                )
                HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            }
        }
    }
}

@Composable
fun LicencesScreen(onBack: () -> Unit, onOpenText: (asset: String, title: String) -> Unit) {
    val context = LocalContext.current
    SimpleScaffold("Open-source licences", onBack) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
            item {
                Text(
                    "aismo is MIT licensed. It is built with the following, each under its own licence. The full texts ship inside the app.",
                    Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(Guide.libraries, key = { it.name }) { lib ->
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(lib.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("${lib.licence} · ${lib.role}", style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        lib.licenceAsset?.let { Text("Licence text", Modifier.clickable { onOpenText(it, "${lib.name} licence") }.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium) }
                        lib.noticesAsset?.let { Text("Notices", Modifier.clickable { onOpenText(it, "${lib.name} notices") }.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium) }
                        Text("Website", Modifier.clickable { openLink(context, lib.url) }.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
                    }
                }
                HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            }
        }
    }
}

/** A licence or notices file from the app's assets. The long ones are read off the main thread and shown a block at a time. */
@Composable
fun LicenceTextScreen(asset: String, title: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val text by produceLicenceText(context, asset)
    SimpleScaffold(title, onBack) { padding ->
        val blocks = text
        if (blocks == null) {
            Column(Modifier.padding(padding).fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { CircularProgressIndicator() }
        } else {
            SelectionContainer {
                LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
                    items(blocks.size) { i -> Text(blocks[i], fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp) }
                }
            }
        }
    }
}

@Composable
private fun produceLicenceText(context: Context, asset: String) = androidx.compose.runtime.produceState<List<String>?>(null, asset) {
    value = withContext(Dispatchers.IO) {
        runCatching {
            // Split into blocks of 40 lines so a notices file of megabytes is not one giant piece of text.
            context.assets.open("${Guide.ASSET_DIR}/$asset").bufferedReader().use { it.readText() }.lines().chunked(40).map { it.joinToString("\n") }
        }.getOrDefault(listOf("This file could not be read."))
    }
}
