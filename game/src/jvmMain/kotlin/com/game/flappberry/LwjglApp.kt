package com.game.flappberry

import com.littlekt.createLittleKtApp

fun main(stringArray: Array<String>) {
    createLittleKtApp {
        width = 520
        height = 720
        title = "FlappBerry"
    }.start {
        Game(it)
    }
}