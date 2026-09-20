package app.mihon.ftp.worker

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class EmbeddedFtpServer(
    private val context: Context,
    private val port: Int,
    private val rootProvider: () -> DocumentFile?,
    private val authProvider: () -> Pair<String, String>, // username, password (empty = anonymous allowed)
) {
    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private val threadPool: ExecutorService = Executors.newCachedThreadPool()

    fun start() {
        if (isRunning) return
        isRunning = true
        threadPool.execute {
            try {
                val server = ServerSocket(port)
                serverSocket = server
                Log.i(TAG, "Embedded FTP Server listening on port $port")
                while (isRunning && !server.isClosed) {
                    try {
                        val clientSocket = server.accept()
                        threadPool.execute {
                            handleClient(clientSocket)
                        }
                    } catch (e: IOException) {
                        if (!isRunning) break
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "FTP Server failed to start on port $port: ${e.message}", e)
            } finally {
                stop()
            }
        }
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null
    }

    private fun handleClient(socket: Socket) {
        socket.soTimeout = 60_000 // 60s idle timeout
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))

            fun send(line: String) {
                writer.write(line + "\r\n")
                writer.flush()
            }

            send("220 Mihon Worker FTP Server ready.")

            var user = ""
            var authenticated = false
            var currentPath = "/"
            var passiveServer: ServerSocket? = null
            var activeAddress: InetAddress? = null
            var activePort: Int = 0
            var renameFrom: DocumentFile? = null
            var restartOffset: Long = 0L

            fun checkAuth(): Boolean {
                val (expectedUser, _) = authProvider()
                if (expectedUser.isBlank()) return true // anonymous
                return authenticated
            }

            fun resolve(path: String): DocumentFile? {
                val root = rootProvider() ?: return null
                val normalized = normalizePath(currentPath, path)
                if (normalized == "/" || normalized.isBlank()) return root

                val parts = normalized.trim('/').split('/').filter { it.isNotEmpty() }
                var current: DocumentFile = root
                for (part in parts) {
                    current = current.findFile(part) ?: return null
                }
                return current
            }

            fun resolveParentAndName(path: String): Pair<DocumentFile, String>? {
                val root = rootProvider() ?: return null
                val normalized = normalizePath(currentPath, path).trim('/')
                if (normalized.isBlank()) return null

                val parts = normalized.split('/').filter { it.isNotEmpty() }
                val fileName = parts.last()
                val dirParts = parts.dropLast(1)

                var current: DocumentFile = root
                for (part in dirParts) {
                    val child = current.findFile(part)
                    current = if (child != null && child.isDirectory) {
                        child
                    } else if (child != null) {
                        return null
                    } else {
                        current.createDirectory(part) ?: return null
                    }
                }
                return Pair(current, fileName)
            }

            fun openDataSocket(): Socket? {
                return try {
                    if (passiveServer != null) {
                        passiveServer?.soTimeout = 15_000
                        val dataSocket = passiveServer?.accept()
                        passiveServer?.close()
                        passiveServer = null
                        dataSocket
                    } else if (activeAddress != null && activePort > 0) {
                        val dataSocket = Socket(activeAddress, activePort)
                        activeAddress = null
                        activePort = 0
                        dataSocket
                    } else null
                } catch (e: Exception) {
                    passiveServer?.close()
                    passiveServer = null
                    null
                }
            }

            while (isRunning && !socket.isClosed) {
                val rawLine = reader.readLine() ?: break
                val line = rawLine.trim()
                if (line.isEmpty()) continue

                val spaceIdx = line.indexOf(' ')
                val cmd = (if (spaceIdx == -1) line else line.substring(0, spaceIdx)).uppercase(Locale.ROOT)
                val arg = if (spaceIdx == -1) "" else line.substring(spaceIdx + 1).trim()

                when (cmd) {
                    "USER" -> {
                        user = arg
                        val (expectedUser, _) = authProvider()
                        if (expectedUser.isBlank() || user.equals("anonymous", ignoreCase = true)) {
                            authenticated = true
                            send("230 User logged in, proceed.")
                        } else {
                            send("331 User name okay, need password.")
                        }
                    }
                    "PASS" -> {
                        val (expectedUser, expectedPass) = authProvider()
                        if (expectedUser.isBlank() ||
                            (user.equals(expectedUser, ignoreCase = true) && arg == expectedPass)) {
                            authenticated = true
                            send("230 User logged in, proceed.")
                        } else {
                            send("530 Not logged in, invalid credentials.")
                        }
                    }
                    "QUIT" -> {
                        send("221 Goodbye.")
                        break
                    }
                    "NOOP" -> send("200 OK.")
                    "SYST" -> send("215 UNIX Type: L8")
                    "FEAT" -> {
                        send("211-Features:")
                        send(" UTF8")
                        send(" SIZE")
                        send(" PASV")
                        send(" EPSV")
                        send(" REST STREAM")
                        send("211 End")
                    }
                    "OPTS" -> {
                        if (arg.startsWith("UTF8", ignoreCase = true)) {
                            send("200 Always in UTF8 mode.")
                        } else {
                            send("200 Command okay.")
                        }
                    }
                    "TYPE" -> {
                        val t = arg.uppercase(Locale.ROOT)
                        if (t == "I" || t == "A") {
                            send("200 Type set to $t.")
                        } else {
                            send("504 Unsupported type.")
                        }
                    }
                    "PWD" -> {
                        if (!checkAuth()) { send("530 Please login with USER and PASS."); continue }
                        send("257 \"$currentPath\" is current directory.")
                    }
                    "CWD" -> {
                        if (!checkAuth()) { send("530 Please login with USER and PASS."); continue }
                        val target = normalizePath(currentPath, arg)
                        val targetDir = resolve(target)
                        if (targetDir != null && targetDir.isDirectory) {
                            currentPath = target
                            send("250 Directory successfully changed to \"$currentPath\".")
                        } else {
                            send("550 Failed to change directory: $arg")
                        }
                    }
                    "CDUP" -> {
                        if (!checkAuth()) { send("530 Please login with USER and PASS."); continue }
                        currentPath = normalizePath(currentPath, "..")
                        send("250 Directory successfully changed to \"$currentPath\".")
                    }
                    "PASV" -> {
                        if (!checkAuth()) { send("530 Please login with USER and PASS."); continue }
                        passiveServer?.close()
                        val pServer = ServerSocket(0)
                        passiveServer = pServer

                        var localIp = socket.localAddress
                        if (localIp.isAnyLocalAddress || localIp.isLoopbackAddress) {
                            localIp = getLocalLanAddress() ?: socket.localAddress
                        }
                        val ipBytes = localIp.address
                        val pPort = pServer.localPort
                        val p1 = pPort / 256
                        val p2 = pPort % 256
                        val ipStr = "${ipBytes[0].toUByte()},${ipBytes[1].toUByte()},${ipBytes[2].toUByte()},${ipBytes[3].toUByte()},$p1,$p2"
                        send("227 Entering Passive Mode ($ipStr).")
                    }
                    "EPSV" -> {
                        if (!checkAuth()) { send("530 Please login with USER and PASS."); continue }
                        passiveServer?.close()
                        val pServer = ServerSocket(0)
                        passiveServer = pServer
                        send("229 Entering Extended Passive Mode (|||${pServer.localPort}|).")
                    }
                    "PORT" -> {
                        if (!checkAuth()) { send("530 Please login with USER and PASS."); continue }
                        try {
                            val parts = arg.split(',').map { it.trim().toInt() }
                            if (parts.size == 6) {
                                val ip = InetAddress.getByAddress(byteArrayOf(parts[0].toByte(), parts[1].toByte(), parts[2].toByte(), parts[3].toByte()))
                                val p = (parts[4] shl 8) or parts[5]
                                activeAddress = ip
                                activePort = p
                                send("200 PORT command successful.")
                            } else {
                                send("501 Syntax error in parameters.")
                            }
                        } catch (_: Exception) {
                            send("501 Syntax error in parameters.")
                        }
                    }
                    "LIST", "NLST" -> {
                        if (!checkAuth()) { send("530 Please login with USER and PASS."); continue }
                        val targetDir = if (arg.isBlank() || arg.startsWith("-")) resolve(currentPath) else resolve(arg)
                        if (targetDir == null || !targetDir.isDirectory) {
                            send("550 Directory not found.")
                            passiveServer?.close()
                            passiveServer = null
                            continue
                        }

                        send("150 Opening ASCII mode data connection for file list.")
                        val dataSocket = openDataSocket()
                        if (dataSocket == null) {
                            send("425 Can't open data connection.")
                            continue
                        }

                        try {
                            val dataWriter = BufferedWriter(OutputStreamWriter(dataSocket.getOutputStream(), Charsets.UTF_8))
                            val isNlst = cmd == "NLST"
                            val children = targetDir.listFiles()
                            val now = System.currentTimeMillis()
                            val recentFmt = SimpleDateFormat("MMM dd HH:mm", Locale.US)
                            val olderFmt = SimpleDateFormat("MMM dd  yyyy", Locale.US)
                            val sixMonths = 180L * 24 * 3600 * 1000

                            for (child in children) {
                                val name = child.name ?: continue
                                if (isNlst) {
                                    dataWriter.write("$name\r\n")
                                } else {
                                    val isDir = child.isDirectory
                                    val typeChar = if (isDir) 'd' else '-'
                                    val perms = if (isDir) "rwxr-xr-x" else "rw-r--r--"
                                    val size = if (isDir) 0L else child.length()
                                    val mtime = child.lastModified().takeIf { it > 0 } ?: now
                                    val dateStr = if (now - mtime < sixMonths && mtime <= now) {
                                        recentFmt.format(Date(mtime))
                                    } else {
                                        olderFmt.format(Date(mtime))
                                    }
                                    dataWriter.write("$typeChar$perms 1 mihon mihon $size $dateStr $name\r\n")
                                }
                            }
                            dataWriter.flush()
                            dataSocket.close()
                            send("226 Transfer complete.")
                        } catch (e: Exception) {
                            send("426 Data transfer failed: ${e.message}")
                        } finally {
                            try { dataSocket.close() } catch (_: Exception) {}
                        }
                    }
                    "SIZE" -> {
                        if (!checkAuth()) { send("530 Please login with USER and PASS."); continue }
                        val file = resolve(arg)
                        if (file != null && file.isFile) {
                            send("213 ${file.length()}")
                        } else {
                            send("550 File not found.")
                        }
                    }
                    "REST" -> {
                        val offset = arg.toLongOrNull()
                        if (offset != null && offset >= 0) {
                            restartOffset = offset
                            send("350 Restarting at $restartOffset. Send STORE or RETRIEVE to initiate transfer.")
                        } else {
                            send("501 Syntax error in parameters.")
                        }
                    }
                    "RETR" -> {
                        if (!checkAuth()) { send("530 Please login with USER and PASS."); continue }
                        val file = resolve(arg)
                        if (file == null || !file.isFile) {
                            send("550 File not found or is a directory.")
                            passiveServer?.close()
                            passiveServer = null
                            continue
                        }

                        val length = file.length()
                        send("150 Opening BINARY mode data connection for ${file.name} ($length bytes).")
                        val dataSocket = openDataSocket()
                        if (dataSocket == null) {
                            send("425 Can't open data connection.")
                            continue
                        }

                        try {
                            context.contentResolver.openInputStream(file.uri)?.use { input ->
                                if (restartOffset > 0) {
                                    input.skip(restartOffset)
                                    restartOffset = 0L
                                }
                                val output = dataSocket.getOutputStream()
                                val buf = ByteArray(64 * 1024)
                                var read: Int
                                while (input.read(buf).also { read = it } != -1) {
                                    output.write(buf, 0, read)
                                }
                                output.flush()
                            }
                            dataSocket.close()
                            send("226 Transfer complete.")
                        } catch (e: Exception) {
                            send("426 Data transfer failed: ${e.message}")
                        } finally {
                            try { dataSocket.close() } catch (_: Exception) {}
                        }
                    }
                    "STOR" -> {
                        if (!checkAuth()) { send("530 Please login with USER and PASS."); continue }
                        val resolved = resolveParentAndName(arg)
                        if (resolved == null) {
                            send("550 Invalid path.")
                            passiveServer?.close()
                            passiveServer = null
                            continue
                        }

                        val (parentDir, fileName) = resolved
                        send("150 Opening BINARY mode data connection for $fileName.")
                        val dataSocket = openDataSocket()
                        if (dataSocket == null) {
                            send("425 Can't open data connection.")
                            continue
                        }

                        try {
                            parentDir.findFile(fileName)?.delete()
                            val newFile = parentDir.createFile("application/octet-stream", fileName)
                                ?: throw IOException("Cannot create file in storage")

                            context.contentResolver.openOutputStream(newFile.uri)?.use { output ->
                                val input = dataSocket.getInputStream()
                                val buf = ByteArray(64 * 1024)
                                var read: Int
                                while (input.read(buf).also { read = it } != -1) {
                                    output.write(buf, 0, read)
                                }
                                output.flush()
                            }
                            dataSocket.close()
                            send("226 Transfer complete.")
                        } catch (e: Exception) {
                            send("426 Data transfer failed: ${e.message}")
                        } finally {
                            try { dataSocket.close() } catch (_: Exception) {}
                        }
                    }
                    "MKD" -> {
                        if (!checkAuth()) { send("530 Please login with USER and PASS."); continue }
                        val resolved = resolveParentAndName(arg)
                        if (resolved == null) {
                            send("550 Invalid path.")
                            continue
                        }
                        val (parentDir, dirName) = resolved
                        val existing = parentDir.findFile(dirName)
                        if (existing != null && existing.isDirectory) {
                            send("257 \"$arg\" directory already exists.")
                        } else if (existing != null) {
                            send("550 File with same name already exists.")
                        } else {
                            val created = parentDir.createDirectory(dirName)
                            if (created != null) {
                                send("257 \"$arg\" directory created.")
                            } else {
                                send("550 Failed to create directory.")
                            }
                        }
                    }
                    "RMD" -> {
                        if (!checkAuth()) { send("530 Please login with USER and PASS."); continue }
                        val dir = resolve(arg)
                        if (dir != null && dir.isDirectory) {
                            if (dir.delete()) {
                                send("250 Directory removed.")
                            } else {
                                send("550 Failed to remove directory.")
                            }
                        } else {
                            send("550 Directory not found.")
                        }
                    }
                    "DELE" -> {
                        if (!checkAuth()) { send("530 Please login with USER and PASS."); continue }
                        val file = resolve(arg)
                        if (file != null && file.isFile) {
                            if (file.delete()) {
                                send("250 File deleted.")
                            } else {
                                send("550 Failed to delete file.")
                            }
                        } else {
                            send("550 File not found.")
                        }
                    }
                    "RNFR" -> {
                        if (!checkAuth()) { send("530 Please login with USER and PASS."); continue }
                        val item = resolve(arg)
                        if (item != null) {
                            renameFrom = item
                            send("350 Ready for RNTO.")
                        } else {
                            send("550 File or directory not found.")
                        }
                    }
                    "RNTO" -> {
                        if (!checkAuth()) { send("530 Please login with USER and PASS."); continue }
                        val source = renameFrom
                        if (source == null) {
                            send("503 Bad sequence of commands, send RNFR first.")
                        } else {
                            renameFrom = null
                            val targetName = arg.trim('/').substringAfterLast('/')
                            if (source.renameTo(targetName)) {
                                send("250 Rename successful.")
                            } else {
                                send("550 Rename failed.")
                            }
                        }
                    }
                    else -> {
                        send("502 Command not implemented: $cmd")
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun normalizePath(base: String, input: String): String {
        val trimmed = input.trim()
        val path = if (trimmed.startsWith("/")) trimmed else "$base/$trimmed"
        val stack = mutableListOf<String>()
        for (part in path.split('/').filter { it.isNotEmpty() }) {
            if (part == "..") {
                if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
            } else if (part != ".") {
                stack.add(part)
            }
        }
        return "/" + stack.joinToString("/")
    }

    private fun getLocalLanAddress(): InetAddress? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (intf in interfaces) {
                if (intf.isLoopback || !intf.isUp) continue
                for (addr in intf.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        return addr
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    companion object {
        private const val TAG = "MihonWorkerFtp"
    }
}
