package io.github.aivanyuk.airkast.sample

import android.app.Application
import android.content.Context

/** Holds the [Cast], so a cast outlives the activity that started it. */
class SampleApplication : Application() {
    lateinit var cast: Cast
        private set

    override fun onCreate() {
        super.onCreate()
        cast = Cast(this)
    }
}

val Context.cast: Cast get() = (applicationContext as SampleApplication).cast
