package com.v2ray.ang.ui.main

import android.content.Context

object RuroaNative {
    init {
        System.loadLibrary("ruroa")
    }

    external fun verifyAppIntegrity(context: Context): Boolean
    external fun getPrimarySubUrl(): String
    external fun getCdnSubUrl(): String
}
