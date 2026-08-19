package com.bit.data.provider

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import com.bit.database.AppDatabase
import com.bit.database.dao.WorkspaceDao
import com.bit.models.table_schema.WorkspaceEntity
import kotlinx.coroutines.runBlocking
import me.rerere.workspace.WorkspaceManager
import java.io.File

/**
 * Storage Access Framework provider exposing workspace files directory to Android system file pickers.
 */
class WorkspaceDocumentsProvider : DocumentsProvider() {

    private fun manager(): WorkspaceManager {
        val ctx = context ?: error("Context not available")
        return WorkspaceManager(baseDir = File(ctx.filesDir, "workspaces"))
    }

    private fun dao(): WorkspaceDao {
        val ctx = context ?: error("Context not available")
        return AppDatabase.getDatabase(ctx).workspaceDao()
    }

    private fun allWorkspaces(): List<WorkspaceEntity> = runCatching {
        runBlocking { dao().getAll() }
    }.getOrDefault(emptyList()).ifEmpty {
        val baseDir = File(context?.filesDir, "workspaces")
        val dirs = baseDir.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") } ?: emptyList()
        dirs.map { dir ->
            WorkspaceEntity(
                id = dir.name,
                root = dir.name,
                name = if (dir.name == "default") "Default Workspace" else "Workspace (${dir.name.take(8)})",
                createdAt = dir.lastModified(),
                updatedAt = dir.lastModified()
            )
        }.ifEmpty {
            listOf(
                WorkspaceEntity(
                    id = "default",
                    root = "default",
                    name = "Default Workspace",
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
    }

    private fun workspaceName(root: String): String =
        allWorkspaces().firstOrNull { it.root == root }?.name ?: root

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)
        val ctx = context ?: return cursor
        cursor.newRow().apply {
            add(Root.COLUMN_ROOT_ID, ROOT_ID)
            add(Root.COLUMN_DOCUMENT_ID, ROOT_DOC_ID)
            add(Root.COLUMN_TITLE, "BIT Workspaces")
            add(Root.COLUMN_FLAGS, Root.FLAG_LOCAL_ONLY or Root.FLAG_SUPPORTS_IS_CHILD or Root.FLAG_SUPPORTS_CREATE)
            add(Root.COLUMN_ICON, com.bit.R.mipmap.ic_launcher)
            add(Root.COLUMN_MIME_TYPES, "*/*")
        }
        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val target = parseDocId(documentId)
        if (target.isRoot) {
            cursor.newRow().apply {
                add(Document.COLUMN_DOCUMENT_ID, ROOT_DOC_ID)
                add(Document.COLUMN_DISPLAY_NAME, "BIT Workspaces")
                add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR)
                add(Document.COLUMN_FLAGS, Document.FLAG_DIR_SUPPORTS_CREATE)
                add(Document.COLUMN_SIZE, null)
                add(Document.COLUMN_LAST_MODIFIED, null)
            }
        } else {
            runCatching {
                addFileRow(cursor, target.root, resolveFile(target.root, target.relPath))
            }
        }
        return cursor
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<String>?,
        sortOrder: String?,
    ): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val parent = parseDocId(parentDocumentId)
        if (parent.isRoot) {
            for (ws in allWorkspaces()) {
                val dir = manager().filesDir(ws.root).also { it.mkdirs() }
                addFileRow(cursor, ws.root, dir)
            }
        } else {
            runCatching {
                val dir = resolveFile(parent.root, parent.relPath)
                if (dir.isDirectory) {
                    dir.listFiles()
                        .orEmpty()
                        .filter { !it.name.startsWith(".l2s.") }
                        .sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
                        .forEach { addFileRow(cursor, parent.root, it) }
                }
            }
        }
        return cursor
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        val target = parseDocId(documentId)
        require(!target.isRoot) { "Cannot open root as a file: $documentId" }
        val file = resolveFile(target.root, target.relPath)
        val isWrite = mode.contains("w") || mode.contains("W") || mode.contains("+")
        if (isWrite && !file.exists()) {
            file.parentFile?.mkdirs()
            file.createNewFile()
        }
        require(file.isFile) { "Target is not a regular file: $documentId" }
        val accessMode = ParcelFileDescriptor.parseMode(mode)
        return ParcelFileDescriptor.open(file, accessMode)
    }

    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String,
    ): String {
        val parent = parseDocId(parentDocumentId)
        val targetRoot = if (parent.isRoot) {
            val list = allWorkspaces()
            if (list.isNotEmpty()) list.first().root else "default"
        } else {
            parent.root
        }

        val parentDir = if (parent.isRoot) {
            manager().filesDir(targetRoot).also { it.mkdirs() }
        } else {
            resolveFile(parent.root, parent.relPath).also { it.mkdirs() }
        }

        val targetFile = File(parentDir, displayName)
        if (mimeType == Document.MIME_TYPE_DIR) {
            targetFile.mkdirs()
        } else {
            if (!targetFile.exists()) {
                targetFile.parentFile?.mkdirs()
                targetFile.createNewFile()
            }
        }
        return makeDocId(targetRoot, targetFile)
    }

    override fun deleteDocument(documentId: String) {
        val target = parseDocId(documentId)
        require(!target.isRoot) { "Cannot delete root document" }
        runCatching {
            val file = resolveFile(target.root, target.relPath)
            if (file.isDirectory) {
                file.deleteRecursively()
            } else {
                file.delete()
            }
        }
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        val parent = parseDocId(parentDocumentId)
        val child = parseDocId(documentId)
        if (parent.isRoot) return !child.isRoot
        if (parent.root != child.root) return false
        val parentFile = resolveFile(parent.root, parent.relPath)
        val childFile = resolveFile(child.root, child.relPath)
        return childFile.canonicalPath.startsWith(parentFile.canonicalPath + File.separator)
    }

    private fun addFileRow(cursor: MatrixCursor, root: String, file: File) {
        val isWsRoot = file.canonicalPath == manager().filesDir(root).canonicalPath
        val displayName = if (isWsRoot) workspaceName(root) else file.name
        val mimeType = if (file.isDirectory) {
            Document.MIME_TYPE_DIR
        } else {
            val ext = file.extension.lowercase()
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        }
        val flags = if (file.isDirectory) {
            Document.FLAG_DIR_SUPPORTS_CREATE
        } else {
            Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_DELETE
        }

        cursor.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, makeDocId(root, file))
            add(Document.COLUMN_DISPLAY_NAME, displayName)
            add(Document.COLUMN_MIME_TYPE, mimeType)
            add(Document.COLUMN_FLAGS, flags)
            add(Document.COLUMN_SIZE, if (file.isDirectory) null else file.length())
            add(Document.COLUMN_LAST_MODIFIED, file.lastModified())
        }
    }

    private fun resolveFile(root: String, relPath: String): File {
        val base = manager().filesDir(root)
        val file = if (relPath.isBlank()) base else File(base, relPath)
        val canonical = file.canonicalFile
        val canonicalBase = base.canonicalFile
        require(canonical == canonicalBase || canonical.path.startsWith(canonicalBase.path + File.separator)) {
            "Path traversal detected: root=$root, relPath=$relPath"
        }
        return file
    }

    private fun makeDocId(root: String, file: File): String {
        val base = manager().filesDir(root).canonicalFile
        val target = file.canonicalFile
        if (target == base) return "ws/$root"
        val rel = target.relativeTo(base).path.replace(File.separatorChar, '/')
        return "ws/$root/$rel"
    }

    private fun parseDocId(documentId: String): Target {
        if (documentId == ROOT_DOC_ID) return Target(isRoot = true, root = "", relPath = "")
        val trimmed = documentId.removePrefix("ws/")
        val slashIdx = trimmed.indexOf('/')
        return if (slashIdx == -1) {
            Target(isRoot = false, root = trimmed, relPath = "")
        } else {
            Target(
                isRoot = false,
                root = trimmed.substring(0, slashIdx),
                relPath = trimmed.substring(slashIdx + 1),
            )
        }
    }

    private data class Target(val isRoot: Boolean, val root: String, val relPath: String)

    private companion object {
        const val ROOT_ID = "bit_workspace_root"
        const val ROOT_DOC_ID = "root"

        val DEFAULT_ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_TITLE,
            Root.COLUMN_FLAGS,
            Root.COLUMN_ICON,
            Root.COLUMN_MIME_TYPES,
        )

        val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_FLAGS,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
        )
    }
}
