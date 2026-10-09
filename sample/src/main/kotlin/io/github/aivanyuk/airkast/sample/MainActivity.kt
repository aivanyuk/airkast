package io.github.aivanyuk.airkast.sample

import android.content.ComponentName
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import io.github.aivanyuk.airkast.sample.ui.CastScreen
import io.github.aivanyuk.airkast.sample.ui.SampleTheme

/** Hosts [CastScreen] over the process's [Cast], with a MediaController to [CastService] while shown. */
class MainActivity : ComponentActivity() {
    private var controller by mutableStateOf<MediaController?>(null)
    private var connecting: ListenableFuture<MediaController>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SampleTheme {
                Scaffold { padding -> CastScreen(cast, controller, Modifier.padding(padding)) }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Connecting a controller starts CastService, whose MediaSession puts up the notification.
        val token = SessionToken(this, ComponentName(this, CastService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        connecting = future
        future.addListener({ if (connecting === future) controller = future.get() }, MoreExecutors.directExecutor())
    }

    override fun onStop() {
        controller = null
        connecting?.let(MediaController::releaseFuture)
        connecting = null
        super.onStop()
    }
}
