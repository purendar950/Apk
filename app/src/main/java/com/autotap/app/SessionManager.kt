package com.autotap.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

data class CaptureSession(
    val id: String,
    val timestamp: Long,
    val questionCount: Int,
    val frameCount: Int,
    val sessionDir: String,
    val stitchedFiles: List<String>,
    val pdfPath: String?
) {
    fun dateFormatted(): String {
        val sdf = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }

    fun sessionDirFile(context: Context) = File(context.filesDir, "sessions/$id")

    fun frameFiles(context: Context): List<File> {
        val dir = File(sessionDirFile(context), "frames")
        return dir.listFiles()?.filter { it.name.endsWith(".png") }?.sortedBy { it.name } ?: emptyList()
    }

    fun stitchedFileObjects(context: Context): List<File> {
        return stitchedFiles.map { File(it) }.filter { it.exists() }
    }

    fun pdfFile(): File? {
        return pdfPath?.let { File(it) }.takeIf { it?.exists() == true }
    }

}

object SessionManager {
    private const val SESSIONS_FILE = "sessions.json"

    fun saveSession(context: Context, session: CaptureSession) {
        val sessions = loadAllSessions(context).toMutableList()
        sessions.add(0, session)
        persistSessions(context, sessions)
    }

    fun loadAllSessions(context: Context): List<CaptureSession> {
        val file = File(context.filesDir, SESSIONS_FILE)
        if (!file.exists()) return emptyList()
        return try {
            val json = file.readText()
            val arr = JSONArray(json)
            val sessions = (0 until arr.length()).mapNotNull { i ->
                try {
                    val obj = arr.getJSONObject(i)
                    CaptureSession(
                        id = obj.getString("id"),
                        timestamp = obj.getLong("timestamp"),
                        questionCount = obj.getInt("questionCount"),
                        frameCount = obj.optInt("frameCount", 0),
                        sessionDir = obj.optString("sessionDir", ""),
                        stitchedFiles = run {
                            val arr = obj.optJSONArray("stitchedFiles")
                            if (arr != null) (0 until arr.length()).map { arr.getString(it) } else emptyList()
                        },
                        pdfPath = if (obj.has("pdfPath") && !obj.isNull("pdfPath")) obj.getString("pdfPath") else null
                    )
                } catch (e: Exception) {
                    null // Skip corrupted entries
                }
            }
            sessions.sortedByDescending { it.timestamp }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun deleteSession(context: Context, session: CaptureSession) {
        val sessions = loadAllSessions(context).toMutableList()
        sessions.removeAll { it.id == session.id }
        persistSessions(context, sessions)
        // Delete files
        runCatching { session.sessionDirFile(context).deleteRecursively() }
    }

    fun createSessionDir(context: Context): Pair<String, File> {
        val id = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val dir = File(context.filesDir, "sessions/$id")
        dir.mkdirs()
        File(dir, "frames").mkdirs()
        File(dir, "stitched").mkdirs()
        return Pair(id, dir)
    }

    private fun persistSessions(context: Context, sessions: List<CaptureSession>) {
        val arr = JSONArray()
        for (s in sessions) {
            val obj = JSONObject().apply {
                put("id", s.id)
                put("timestamp", s.timestamp)
                put("questionCount", s.questionCount)
                put("frameCount", s.frameCount)
                put("sessionDir", s.sessionDir)
                put("stitchedFiles", JSONArray(s.stitchedFiles))
                if (s.pdfPath != null) put("pdfPath", s.pdfPath)
            }
            arr.put(obj)
        }
        File(context.filesDir, SESSIONS_FILE).writeText(arr.toString())
    }
}
