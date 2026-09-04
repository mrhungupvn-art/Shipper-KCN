package com.com11h.app

import android.content.Intent
import android.os.Bundle

/** Small navigation bridge while the native shell and existing business module coexist. */
class LegacyRouterActivity : SessionActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val screen = intent.getStringExtra("screen")
        when (screen) {
            // Shopping is intentionally routed to the live website so the app and web
            // use the same menu, cart, account and order business rules.
            "menu" -> startActivity(
                Intent(this, WebActivity::class.java)
                    .putExtra("url", "https://com11h.com/menu.php")
            )
            "profile" -> startActivity(Intent(this, AccountActivity::class.java))
            "cart" -> startActivity(
                Intent(this, WebActivity::class.java)
                    .putExtra("url", "https://com11h.com/cart.php")
            )
            "orders" -> startActivity(
                Intent(this, WebActivity::class.java)
                    .putExtra("url", "https://com11h.com/account.php")
            )
            else -> startActivity(Intent(this, HomeActivity::class.java))
        }
        finish()
    }
}
