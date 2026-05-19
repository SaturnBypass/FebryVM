package com.febry.vm.ui

import android.os.Bundle
import com.febry.vm.R
import com.febry.vm.core.CoreServiceManager

class ScStopActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        moveTaskToBack(true)

        setContentView(R.layout.activity_none)

        if (CoreServiceManager.isRunning()) {
            CoreServiceManager.stopVService(this)
        }
        finish()
    }
}
