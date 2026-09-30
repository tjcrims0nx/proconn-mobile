package com.proconn.mobile.store

import android.content.Context
import com.proconn.mobile.model.CrosshairDesign
import org.json.JSONObject
import java.io.File

/** Holds the live design and persists named designs as JSON in the app's files dir. */
object DesignStore {
    @Volatile
    var current: CrosshairDesign = CrosshairDesign()

    private fun dir(c: Context): File = File(c.filesDir, "designs").apply { mkdirs() }

    private fun sanitize(n: String): String =
        n.replace(Regex("[^A-Za-z0-9 _-]"), "_").trim().take(40).ifBlank { "design" }

    fun save(c: Context, name: String) {
        val clean = sanitize(name)
        current.name = clean
        File(dir(c), "$clean.json").writeText(current.toJson().toString())
    }

    fun list(c: Context): List<String> =
        dir(c).listFiles { f -> f.isFile && f.extension == "json" }
            ?.map { it.nameWithoutExtension }?.sorted() ?: emptyList()

    fun load(c: Context, name: String): CrosshairDesign? = try {
        val d = CrosshairDesign.fromJson(JSONObject(File(dir(c), "$name.json").readText()))
        current = d
        d
    } catch (e: Exception) {
        null
    }

    fun delete(c: Context, name: String) {
        File(dir(c), "$name.json").delete()
    }
}
