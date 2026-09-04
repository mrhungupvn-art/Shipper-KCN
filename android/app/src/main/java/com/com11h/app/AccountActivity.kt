package com.com11h.app

import android.content.Intent
import android.os.Bundle

/** Compatibility entry point. Account data is handled by MainActivity + AccountSync. */
class AccountActivity : SessionActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, MainActivity::class.java).putExtra("screen", "profile"))
        finish()
    }
}
