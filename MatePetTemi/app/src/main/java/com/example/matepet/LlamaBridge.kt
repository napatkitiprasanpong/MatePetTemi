package com.example.matepet

fun interface TokenCallback { fun onToken(piece: ByteArray): Boolean }  // return false to stop

object LlamaBridge {
    init { System.loadLibrary("matepet") }
    external fun nativeLoad(path: String, threads: Int): Boolean
    external fun nativeGenerate(system: String, user: String, maxTokens: Int, cb: TokenCallback)
}
