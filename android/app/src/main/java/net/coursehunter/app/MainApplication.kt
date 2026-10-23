package net.coursehunter.app

import android.app.Application
import com.lynx.service.http.LynxHttpService
import com.lynx.service.log.LynxLogService
import com.lynx.tasm.LynxEnv
import com.lynx.tasm.service.LynxServiceCenter

class MainApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        LynxServiceCenter.inst().registerService(LynxLogService)
        LynxServiceCenter.inst().registerService(LynxHttpService)

        LynxEnv.inst().init(this, null, null, null)
    }
}
