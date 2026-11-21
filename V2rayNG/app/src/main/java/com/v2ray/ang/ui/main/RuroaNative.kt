package com.v2ray.ang.ui.main

import android.content.Context

object RuroaNative {
    init {
        System.loadLibrary("ruroa")
    }

    external fun verifyEnvironment(packageName: String): Boolean
    external fun signReferralBase(base5: String): String
    external fun validateReferralCode(code: String, myCode: String): Boolean
    external fun getChannelUrl(): String

    fun initGuard(context: Context) {
        verifyEnvironment(context.packageName)
    }

    fun generateSignedCode(): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val base5 = (1..5).map { alphabet.random() }.joinToString("")
        return signReferralBase(base5)
    }
}
