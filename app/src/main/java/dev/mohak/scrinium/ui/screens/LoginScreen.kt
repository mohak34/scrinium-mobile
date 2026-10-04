package dev.mohak.scrinium.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mohak.scrinium.BuildConfig
import dev.mohak.scrinium.R
import dev.mohak.scrinium.ui.MainViewModel
import dev.mohak.scrinium.ui.Sc
import dev.mohak.scrinium.ui.Sym
import dev.mohak.scrinium.ui.Type

@Composable
fun LoginScreen(vm: MainViewModel) {
    val busy by vm.signInBusy.collectAsStateWithLifecycle()
    val error by vm.signInError.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Sc.bg)
            .systemBarsPadding()
            .padding(horizontal = 28.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("Scrinium", style = Type.title.copy(fontSize = 30.sp))
        Text(
            "Your notes and tasks, synced with your own server.",
            style = Type.body.copy(color = Sc.text2),
            modifier = Modifier.padding(top = 6.dp, bottom = 28.dp)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(if (busy) Sc.press else Sc.fill)
                .clickable(enabled = !busy) { vm.requestSignIn() },
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Sym(R.drawable.ms_login, tint = if (busy) Sc.text3 else Sc.onFill)
            Text(
                if (busy) "Signing in" else "Continue with Google",
                style = Type.body.copy(fontWeight = FontWeight.SemiBold, color = if (busy) Sc.text3 else Sc.onFill),
                modifier = Modifier.padding(start = 10.dp)
            )
        }
        error?.let {
            Text(it, style = Type.meta.copy(color = Sc.red), modifier = Modifier.padding(top = 14.dp))
        }
        Spacer(Modifier.height(14.dp))
        Text("Only allowed accounts can sign in.", style = Type.meta)
        Text(BuildConfig.SCRINIUM_API_URL.substringAfter("://"), style = Type.mono, modifier = Modifier.padding(top = 2.dp))
    }
}
