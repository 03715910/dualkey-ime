package io.github.user03715910.dualkey.translation

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.*
import kotlinx.coroutines.tasks.await

/** Only ML Kit model acquisition uses the network. The text is translated on this device. */
class TranslationEngine {
    private val clients = mutableMapOf<Boolean,Translator>()
    private fun client(toEnglish: Boolean) = clients.getOrPut(toEnglish) {
        Translation.getClient(TranslatorOptions.Builder()
            .setSourceLanguage(if (toEnglish) TranslateLanguage.JAPANESE else TranslateLanguage.ENGLISH)
            .setTargetLanguage(if (toEnglish) TranslateLanguage.ENGLISH else TranslateLanguage.JAPANESE).build())
    }
    suspend fun ready(): Boolean = RemoteModelManager.getInstance().isModelDownloaded(
        TranslateRemoteModel.Builder(TranslateLanguage.JAPANESE).build()).await()
    suspend fun download(toEnglish: Boolean) { client(toEnglish).downloadModelIfNeeded(DownloadConditions.Builder().requireWifi().build()).await() }
    suspend fun translate(text: String, toEnglish: Boolean): String = client(toEnglish).translate(text).await()
    suspend fun delete() {
        close()
        RemoteModelManager.getInstance().deleteDownloadedModel(TranslateRemoteModel.Builder(TranslateLanguage.JAPANESE).build()).await()
    }
    fun close() { clients.values.forEach { it.close() }; clients.clear() }
}
