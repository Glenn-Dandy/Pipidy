package de.kewl.pipidy.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import de.kewl.pipidy.BuildConfig
import de.kewl.pipidy.MainViewModel
import de.kewl.pipidy.R
import de.kewl.pipidy.update.Repo
import de.kewl.pipidy.update.UpdateChecker
import de.kewl.pipidy.update.UpdateResult
import kotlinx.coroutines.launch

private sealed interface UpdateUi {
    data object Idle : UpdateUi
    data object Checking : UpdateUi
    data class Done(val result: UpdateResult) : UpdateUi
}

@Composable
fun AboutScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var update by remember { mutableStateOf<UpdateUi>(UpdateUi.Idle) }

    fun open(url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    fun issue(prefix: String, label: String) {
        val body = "\n\n---\n**App:** ${BuildConfig.VERSION_NAME}\n" +
            "**Android:** ${Build.VERSION.RELEASE}\n**Device:** ${Build.MANUFACTURER} ${Build.MODEL}\n"
        open(
            Repo.ISSUES_NEW_URL + "?labels=" + Uri.encode(label) + "&title=" + Uri.encode(prefix) +
                "&body=" + Uri.encode(body)
        )
    }

    fun check() {
        update = UpdateUi.Checking
        scope.launch { update = UpdateUi.Done(UpdateChecker.check(BuildConfig.VERSION_NAME)) }
    }

    Page(
        busy = update == UpdateUi.Checking,
        error = null,
        onDismissError = {},
        header = { TitleBar(stringResource(R.string.about_title), stringResource(R.string.about_kicker), vm::back) },
    ) {
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                // The lamp from the launcher icon, without the round mask the system would add.
                Box(Modifier.size(68.dp).glass(Ink.Amber, 1.4f), contentAlignment = Alignment.Center) {
                    Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.size(96.dp))
                }
                Column {
                    Txt("PIPIDY", Type.Wordmark)
                    Txt(stringResource(R.string.app_name), Type.Display)
                    Label(stringResource(R.string.version, BuildConfig.VERSION_NAME))
                }
            }
            Txt(stringResource(R.string.about_tagline), Type.Small)
        }

        Panel(stringResource(R.string.about_feedback)) {
            ActionRow(stringResource(R.string.report_bug)) { issue("[Bug] ", "bug") }
            Divider()
            ActionRow(stringResource(R.string.request_feature)) { issue("[Feature] ", "enhancement") }
        }

        Panel(stringResource(R.string.about_project)) {
            ActionRow(stringResource(R.string.view_source)) { open(Repo.URL) }
            Divider()
            ActionRow(stringResource(R.string.star_github)) { open(Repo.URL) }
            Divider()
            ActionRow(stringResource(R.string.support_project), accent = true) { open(Repo.SUPPORT_URL) }
        }

        Panel(stringResource(R.string.update_section)) {
            when (val state = update) {
                UpdateUi.Idle -> ActionRow(stringResource(R.string.check_update), onClick = ::check)
                UpdateUi.Checking -> Txt(stringResource(R.string.checking), Type.Small)
                is UpdateUi.Done -> when (val result = state.result) {
                    UpdateResult.UpToDate -> {
                        Txt(stringResource(R.string.up_to_date), Type.Body)
                        GlassButton(stringResource(R.string.check_update), ::check)
                    }
                    is UpdateResult.Available -> {
                        Txt(stringResource(R.string.update_available, result.version), Type.Body, color = Ink.Amber)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            GlassButton(
                                stringResource(R.string.download),
                                { open(result.downloadUrl ?: result.releaseUrl) },
                                Modifier.weight(1f),
                                primary = true,
                            )
                            GlassButton(stringResource(R.string.open_release), { open(result.releaseUrl) }, Modifier.weight(1f))
                        }
                    }
                    UpdateResult.Failed -> {
                        Txt(stringResource(R.string.update_failed), Type.Body, color = Ink.Red)
                        GlassButton(stringResource(R.string.check_update), ::check)
                    }
                }
            }
        }

        Panel(stringResource(R.string.about_notes)) {
            Txt(stringResource(R.string.about_notes_text), Type.Small)
        }

        Column(
            Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Txt("MIT · © ${Repo.OWNER} · ", Type.Small, color = Ink.Faint)
                Txt(Repo.WEBSITE_LABEL, Type.Small, Modifier.clickable { open(Repo.WEBSITE_URL) }, color = Ink.Amber)
            }
            Image(
                painterResource(R.drawable.ic_wozise_logo),
                Repo.WEBSITE_LABEL,
                Modifier.clickable { open(Repo.WEBSITE_URL) },
            )
        }
    }
}

@Composable
private fun ActionRow(label: String, accent: Boolean = false, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .alpha(if (pressed) 0.6f else 1f)
            .clickable(source, null, role = Role.Button, onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Txt(label, Type.Body, Modifier.weight(1f), color = if (accent) Ink.Amber else Ink.Text)
        GlyphIcon(Glyph.Next, Ink.Dim, Modifier.size(16.dp))
    }
}

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().height(1.dp).glass(strength = 0.6f))
}
