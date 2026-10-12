package os.meka.android.goals

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaTheme
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.appear
import os.meka.android.designsystem.mekaRecomposer
import os.meka.android.designsystem.rememberAppearance

/**
 * How MEKA uses Health Connect, shown from Health Connect's permission screen and Settings' permission usage (Health
 * Connect requires it of every app that reads health data). The lines fade up in order ([appear]; Off: at once).
 */
class HealthPrivacyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent(parent = mekaRecomposer()) {
            MekaTheme {
                Column(
                    Modifier.fillMaxSize().background(Meka.colors.background).systemBarsPadding().padding(MekaSpace.gutter),
                    verticalArrangement = Arrangement.spacedBy(MekaSpace.m),
                ) {
                    Text("MEKA and Health Connect", style = MekaType.greeting, color = Meka.colors.textPrimary,
                        modifier = Modifier.appear(rememberAppearance(0)))
                    LINES.forEachIndexed { i, line ->
                        Text(line, style = MekaType.body, color = Meka.colors.textSecondary, modifier = Modifier.appear(rememberAppearance(i + 1)))
                    }
                }
            }
        }
    }

    companion object {
        val LINES = listOf(
            "MEKA reads only your exercise sessions, and only today's, while MEKA is open.",
            "A workout ticks the habit it matches, like Gym or Run, and Activity lists it with Undo.",
            "What Health Connect holds stays on this phone. Only the tick is synced to your other devices through MEKA's own server; nothing goes to MEKA's AI or anyone else.",
            "Turn it off in MEKA under Goals → Workouts, or remove MEKA's access in Health Connect.",
        )
    }
}
