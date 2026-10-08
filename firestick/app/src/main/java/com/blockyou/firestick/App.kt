package com.blockyou.firestick

import android.app.Application
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        NewPipe.init(OkHttpDownloader, Localization("pt", "BR"), ContentCountry("BR"))
    }
}
