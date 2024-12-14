package com.game.flappberry

import com.littlekt.createLittleKtApp

fun main() {
    createLittleKtApp {
        width = 520
        height = 720
        title = "FlappBerry"
        canvasId = "canvas"
    }.start {
        Game(it)
    }
}