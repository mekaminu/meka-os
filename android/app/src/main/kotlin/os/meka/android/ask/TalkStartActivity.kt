package os.meka.android.ask

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import os.meka.android.MainActivity
import os.meka.core.domain.TalkStartRules

/**
 * Talk without tapping the mic (slice 1): Android's assistant gesture (the side key held, with MEKA as the digital
 * assistant app; `ACTION_ASSIST`) and a headset's button held (`ACTION_VOICE_COMMAND`) land here. It shows nothing:
 * it hands over to [MainActivity] (the running one if there is one) with "talk:<start>", which opens Ask already
 * listening, and finishes. Nothing is heard until Ask's [TalkController] starts, with the same on-device recogniser
 * and the same microphone permission as tapping the mic.
 */
class TalkStartActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val start = TalkStartRules.fromAction(intent?.action)
        val open = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (start != null) open.putExtra(MainActivity.EXTRA_OPEN, TalkStartRules.openValue(start))
        startActivity(open)
        finish()
    }
}
