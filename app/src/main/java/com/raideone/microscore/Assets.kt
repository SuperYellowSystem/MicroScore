package com.raideone.microscore

import android.content.Context
import android.content.res.AssetManager
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class Assets {

    companion object {
        const val TESS_FOLDER = "tessdata"
    }

    /**
     * Returns locally accessible directory where our assets are extracted.
     */
    private fun getLocalDir(context: Context): File {
        return context.filesDir
    }

    /**
     * Returns locally accessible directory path which contains the TESS_FOLDER subdirectory
     * with *.traineddata files.
     */
    fun getTessDataPath(context: Context): String {
        return getLocalDir(context).absolutePath
    }

    fun extractAssets(context: Context) {
        val am = context.assets
        val localDir = getLocalDir(context)
        if (!localDir.exists() && !localDir.mkdir()) {
            throw RuntimeException("Can't create directory $localDir")
        }
        val tessDir = File(getTessDataPath(context), TESS_FOLDER)
        if (!tessDir.exists() && !tessDir.mkdir()) {
            throw RuntimeException("Can't create directory $tessDir")
        }

        // Extract all assets to our local directory.
        // All *.traineddata into "tessdata" subdirectory, other files into root.
        try {
            for (assetName in am.list("")!!) {
                if (isAssetDirectory(am, assetName)) {
                    continue
                }
                val targetFile: File = if (assetName.endsWith(".traineddata")) {
                    File(tessDir, assetName)
                } else {
                    File(localDir, assetName)
                }
                if (!targetFile.exists()) {
                    copyFile(am, assetName, targetFile)
                }
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    private fun copyFile(
        am: AssetManager, assetName: String,
        outFile: File
    ) {
        try {
            am.open(assetName).use { `in` ->
                FileOutputStream(outFile).use { out ->
                    val buffer = ByteArray(1024)
                    var read: Int
                    while (`in`.read(buffer).also { read = it } != -1) {
                        out.write(buffer, 0, read)
                    }
                }
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    private fun isAssetDirectory(assetManager: AssetManager, path: String): Boolean {
        val list = assetManager.list(path)
        return !list.isNullOrEmpty()
    }
}