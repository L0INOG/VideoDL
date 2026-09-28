package com.videodl.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileNotFoundException;
import java.net.URLDecoder;

/**
 * 【存储】仅 Android 8/9 使用的文件提供器。
 *
 * 功能: 让已下载到公共下载目录的文件能通过 content:// 被「打开」，
 *       避免 file:// URI 在新系统上被禁止；Android 10+ 走 MediaStore 不经过这里。
 * 链路: DownloadService.saveLegacy 写文件 → legacyUri() 生成 content:// 地址 →
 *       相册/播放器经本 Provider 只读打开。
 * 安全: 防路径穿越——只允许单段文件名，且仅映射 Download/VideoDL 目录。
 */
public class SavedFileProvider extends ContentProvider {

    /** 与 AndroidManifest 中 provider 的 authorities 保持一致 */
    static final String AUTHORITY = "com.videodl.app.files";

    /** legacyUri 里的路径段（历史版本为 "douyin"，无消费方后统一为 "saved"） */
    static final String PATH_SAVED = "saved";

    private static File baseDir() {
        return new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), DownloadService.DIR_NAME);
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    private static File fileFor(Uri uri) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (name == null) {
            throw new FileNotFoundException("空路径");
        }
        name = URLDecoder.decode(name);
        // 防路径穿越：只允许单段文件名
        if (name.contains("/") || name.contains("\\") || name.contains("..")) {
            throw new FileNotFoundException("非法路径");
        }
        File f = new File(baseDir(), name);
        if (!f.isFile()) {
            throw new FileNotFoundException(name);
        }
        return f;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        return ParcelFileDescriptor.open(fileFor(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        File f;
        try {
            f = fileFor(uri);
        } catch (FileNotFoundException e) {
            return null;
        }
        if (projection == null) {
            projection = new String[]{
                    MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.SIZE};
        }
        MatrixCursor c = new MatrixCursor(projection);
        Object[] row = new Object[projection.length];
        for (int i = 0; i < projection.length; i++) {
            String col = projection[i];
            if (MediaStore.MediaColumns._ID.equals(col)) {
                row[i] = 1L;
            } else if (MediaStore.MediaColumns.DISPLAY_NAME.equals(col)) {
                row[i] = f.getName();
            } else if (MediaStore.MediaColumns.SIZE.equals(col)) {
                row[i] = f.length();
            } else {
                row[i] = null;
            }
        }
        c.addRow(row);
        return c;
    }

    /** 按扩展名给出 MIME，供外部（相册/播放器）识别文件类型。 */
    @Override
    public String getType(Uri uri) {
        String name = uri.getLastPathSegment() == null ? "" : uri.getLastPathSegment().toLowerCase();
        if (name.endsWith(".mp4")) {
            return "video/mp4";
        }
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (name.endsWith(".png")) {
            return "image/png";
        }
        if (name.endsWith(".webp")) {
            return "image/webp";
        }
        if (name.endsWith(".heic") || name.endsWith(".heif")) {
            return "image/heic";
        }
        return "application/octet-stream";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
