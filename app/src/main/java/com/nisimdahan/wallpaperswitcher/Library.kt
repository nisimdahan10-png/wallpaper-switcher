package com.nisimdahan.wallpaperswitcher

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

/** תמונה בסבב – נבחרה ידנית או הגיעה מתיקייה */
data class Img(val uri: String, val folder: String?)

/** מאגר התמונות: תמונות שנבחרו ידנית + כל התמונות בתיקיות שנבחרו */
object Library {

    fun hasAny(c: Context) = Prefs.getUris(c).isNotEmpty() || Prefs.getFolders(c).isNotEmpty()

    fun all(c: Context): List<Img> {
        val seen = HashSet<String>()
        val out = ArrayList<Img>()
        Prefs.getUris(c).forEach { if (seen.add(it)) out.add(Img(it, null)) }
        Prefs.getFolders(c).forEach { f ->
            scan(c, f).forEach { if (seen.add(it)) out.add(Img(it, f)) }
        }
        return out
    }

    /** כל התמונות בתיקייה (בלי תת-תיקיות), ממוינות לפי שם */
    fun scan(c: Context, treeUri: String): List<String> = try {
        val tree = Uri.parse(treeUri)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            tree, DocumentsContract.getTreeDocumentId(tree)
        )
        val found = ArrayList<Pair<String, String>>()
        c.contentResolver.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
            ),
            null, null, null
        )?.use { cur ->
            while (cur.moveToNext()) {
                val mime = cur.getString(1) ?: continue
                if (!mime.startsWith("image/")) continue
                val uri = DocumentsContract.buildDocumentUriUsingTree(tree, cur.getString(0))
                found.add((cur.getString(2) ?: "") to uri.toString())
            }
        }
        found.sortedBy { it.first.lowercase() }.map { it.second }
    } catch (e: Exception) {
        emptyList()
    }

    /** שם קריא לתיקייה, למשל "Camera" */
    fun folderName(treeUri: String): String = try {
        val id = DocumentsContract.getTreeDocumentId(Uri.parse(treeUri))
        id.substringAfter(':').substringAfterLast('/').ifBlank { "אחסון פנימי" }
    } catch (e: Exception) {
        "תיקייה"
    }

    fun removeManual(c: Context, uri: String) {
        Prefs.setUris(c, Prefs.getUris(c).filter { it != uri })
    }
}
