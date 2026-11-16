package com.example.chessassistant

import android.content.Context
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.Locale

class StockfishManager(private val context: Context) {

    data class Result(val move: String, val score: String)

    private var process: Process? = null
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null

    /** null — успех, иначе текст ошибки. */
    fun start(): String? {
        val bin = File(context.applicationInfo.nativeLibraryDir, "libstockfish.so")
        if (!bin.exists()) return "Нет libstockfish.so (запустите get_stockfish.sh и пересоберите)"
        return try {
            val p = ProcessBuilder(bin.absolutePath).redirectErrorStream(true).start()
            process = p
            reader = BufferedReader(InputStreamReader(p.inputStream))
            writer = BufferedWriter(OutputStreamWriter(p.outputStream))
            send("uci"); waitFor("uciok")
            send("setoption name Threads value 2")
            send("setoption name Hash value 64")
            send("isready"); waitFor("readyok")
            null
        } catch (e: Exception) {
            "Ошибка запуска движка: ${e.message}"
        }
    }

    private fun waitFor(token: String) {
        while (true) {
            val line = reader?.readLine() ?: return
            if (line.startsWith(token)) return
        }
    }

    @Synchronized
    fun bestMove(fen: String, moveTimeMs: Int = 1000): Result? {
        try {
            send("position fen $fen")
            send("go movetime $moveTimeMs")
            var score = ""
            while (true) {
                val line = reader?.readLine() ?: return null
                if (line.startsWith("info") && " pv " in line && " score " in line) {
                    val parts = line.split(" ")
                    val i = parts.indexOf("score")
                    if (i >= 0 && i + 2 < parts.size) {
                        val v = parts[i + 2].toIntOrNull()
                        if (v != null) {
                            score = if (parts[i + 1] == "mate") "#$v"
                            else String.format(Locale.US, "%+.2f", v / 100.0)
                        }
                    }
                } else if (line.startsWith("bestmove")) {
                    val m = line.split(" ").getOrNull(1) ?: return null
                    return if (m == "(none)") null else Result(m, score)
                }
            }
        } catch (e: Exception) {
            return null
        }
    }

    private fun send(cmd: String) {
        writer?.write(cmd + "\n")
        writer?.flush()
    }

    fun stop() {
        try {
            send("quit")
            process?.destroy()
        } catch (_: Exception) {
        }
    }
}
