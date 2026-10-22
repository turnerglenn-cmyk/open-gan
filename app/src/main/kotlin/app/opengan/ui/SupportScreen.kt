package app.opengan.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

// TODO: replace with the project's real donation and source links before release.
private const val DONATE_URL = "https://github.com/sponsors/OWNER"
private const val SOURCE_URL = "https://github.com/OWNER/open-gan"

@Composable
fun SupportScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    fun open(url: String) = context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    Column(
        modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Free, open, and on your side", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Open GAN has no ads, no tracking, no accounts and no paywalls. It does not even have " +
                "permission to use the internet: your solves stay on your phone.",
        )
        Text(
            "It is built by cubers in their spare time. If it helps your training, a small donation " +
                "keeps development going and pays for testing hardware.",
        )
        Button(onClick = { open(DONATE_URL) }) { Text("Donate") }
        OutlinedButton(onClick = { open(SOURCE_URL) }) { Text("Source code") }
        Text("Credits", style = MaterialTheme.typography.titleMedium)
        Text(
            "Smart cube protocol: gan-web-bluetooth by Andy Fedotov (MIT).\n" +
                "Robot protocol documented by rsimp/gan-scrambler.\n" +
                "Cube-clock timing approach pioneered by Chen Shuang in csTimer.\n" +
                "Not affiliated with or endorsed by GAN.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
