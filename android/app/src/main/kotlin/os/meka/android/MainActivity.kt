package os.meka.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import os.meka.android.designsystem.MekaTheme
import os.meka.android.today.TodayRoute

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val core = (application as MekaApplication).core
        setContent { MekaTheme { TodayRoute(core) } }
    }

    override fun onStart() {
        super.onStart()
        (application as MekaApplication).core.startSync()
    }

    override fun onStop() {
        (application as MekaApplication).core.stopSync()
        super.onStop()
    }
}
