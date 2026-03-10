package com.androidclaw.androidclaw

object ShellUtils {
    fun executeCommand(command: String, useRoot: Boolean): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(if (useRoot) "su" else "sh")
            val os = process.outputStream.bufferedWriter()
            os.write(command + "\n")
            os.write("exit\n")
            os.flush()
            process.waitFor() == 0
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}