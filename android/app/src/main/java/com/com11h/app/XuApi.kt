package com.com11h.app

import org.json.JSONObject

/** API XU phía server. Không lưu số dư XU thật trên thiết bị. */
class XuApi(private val account: AccountSync) {
    fun wallet(): JSONObject = account.request("xu_wallet", "GET")
    fun startView(productId: Int): JSONObject = account.request("xu_start_view", "POST", JSONObject().put("product_id", productId).toString())
    fun completeView(viewId: Int): JSONObject = account.request("xu_complete_view", "POST", JSONObject().put("view_id", viewId).toString())
    fun history(): JSONObject = account.request("xu_history", "GET")
    fun rewards(): JSONObject = account.request("xu_rewards", "GET")
    fun redeem(rewardId: Int): JSONObject = account.request("xu_redeem", "POST", JSONObject().put("reward_id", rewardId).toString())
}
