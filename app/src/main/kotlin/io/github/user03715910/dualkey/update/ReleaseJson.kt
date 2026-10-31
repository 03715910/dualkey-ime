package io.github.user03715910.dualkey.update

import io.github.dualkey.core.ReleaseInfo
import org.json.JSONObject

object ReleaseJson {
    fun parse(text: String): ReleaseInfo {
        val j=JSONObject(text)
        val notes=j.getJSONArray("releaseNotes")
        return ReleaseInfo(j.getString("packageName"),j.getLong("versionCode"),j.getString("versionName"),
            j.getString("releaseDate"),j.getString("apkUrl"),j.getString("websiteUrl"),j.getString("sha256"),
            j.getLong("sizeBytes"),j.getInt("minSdk"),j.optBoolean("required",false),(0 until notes.length()).map { notes.getString(it) })
    }
}
