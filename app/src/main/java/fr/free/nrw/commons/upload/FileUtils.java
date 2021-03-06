package fr.free.nrw.commons.upload;

import android.annotation.SuppressLint;
import android.content.ContentUris;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.preference.PreferenceManager;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.Date;

import timber.log.Timber;

public class FileUtils {

    /**
     * Get a file path from a Uri. This will get the the path for Storage Access
     * Framework Documents, as well as the _data field for the MediaStore and
     * other file-based ContentProviders.
     *
     * @param context The context.
     * @param uri     The Uri to query.
     * @author paulburke
     */
    // Can be safely suppressed, checks for isKitKat before running isDocumentUri
    @SuppressLint("NewApi")
    @Nullable
    public static String getPath(Context context, Uri uri) {

        String returnPath = null;
        final boolean isKitKat = Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT;

        // DocumentProvider
        if (isKitKat && DocumentsContract.isDocumentUri(context, uri)) {
            // ExternalStorageProvider
            if (isExternalStorageDocument(uri)) {
                final String docId = DocumentsContract.getDocumentId(uri);
                final String[] split = docId.split(":");
                final String type = split[0];

                if ("primary".equalsIgnoreCase(type)) {
                    returnPath = Environment.getExternalStorageDirectory() + "/" + split[1];
                }
            } else if (isDownloadsDocument(uri))  { // DownloadsProvider

                final String id = DocumentsContract.getDocumentId(uri);
                final Uri contentUri = ContentUris.withAppendedId(
                        Uri.parse("content://downloads/public_downloads"), Long.valueOf(id));

                returnPath =  getDataColumn(context, contentUri, null, null);
            } else if (isMediaDocument(uri)) { // MediaProvider

                final String docId = DocumentsContract.getDocumentId(uri);
                final String[] split = docId.split(":");
                final String type = split[0];

                Uri contentUri = null;
                switch (type) {
                    case "image":
                        contentUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
                        break;
                    case "video":
                        contentUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
                        break;
                    case "audio":
                        contentUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
                        break;
                    default:
                        break;
                }

                final String selection = "_id=?";
                final String[] selectionArgs = new String[]{
                        split[1]
                };

                returnPath = getDataColumn(context, contentUri, selection, selectionArgs);
            }
        }
        // MediaStore (and general)
        else if ("content".equalsIgnoreCase(uri.getScheme())) {
            returnPath = getDataColumn(context, uri, null, null);
        }
        // File
        else if ("file".equalsIgnoreCase(uri.getScheme())) {
            returnPath = uri.getPath();
        }

        if(returnPath == null) {
            //fetching path may fail depending on the source URI and all hope is lost
            //so we will create and use a copy of the file, which seems to work
            String copyPath = null;
            try {
                ParcelFileDescriptor descriptor
                        = context.getContentResolver().openFileDescriptor(uri, "r");
                if (descriptor != null) {

                    SharedPreferences sharedPref = PreferenceManager
                            .getDefaultSharedPreferences(context);
                    boolean useExtStorage = sharedPref.getBoolean("useExternalStorage", true);
                    if (useExtStorage) {
                        copyPath = Environment.getExternalStorageDirectory().toString()
                                + "/CommonsApp/" + new Date().getTime() + ".jpg";
                        File newFile = new File(Environment.getExternalStorageDirectory().toString() + "/CommonsApp");
                        newFile.mkdir();
                        FileUtils.copy(
                                descriptor.getFileDescriptor(),
                                copyPath);
                        Timber.d("Filepath (copied): %s", copyPath);
                        return copyPath;
                    }
                    copyPath = context.getCacheDir().getAbsolutePath()
                            + "/" + new Date().getTime() + ".jpg";
                    FileUtils.copy(
                            descriptor.getFileDescriptor(),
                            copyPath);
                    Timber.d("Filepath (copied): %s", copyPath);
                    return copyPath;
                }
            } catch (IOException e) {
                Timber.w(e, "Error in file " + copyPath);
                return null;
            }
        } else {
            return returnPath;
        }

        return null;
    }

    /**
     * Get the value of the data column for this Uri. This is useful for
     * MediaStore Uris, and other file-based ContentProviders.
     *
     * @param context       The context.
     * @param uri           The Uri to query.
     * @param selection     (Optional) Filter used in the query.
     * @param selectionArgs (Optional) Selection arguments used in the query.
     * @return The value of the _data column, which is typically a file path.
     */
    @Nullable
    public static String getDataColumn(Context context, Uri uri, String selection,
                                       String[] selectionArgs) {

        Cursor cursor = null;
        final String column = MediaStore.Images.ImageColumns.DATA;
        final String[] projection = {
                column
        };

        try {
            cursor = context.getContentResolver().query(uri, projection, selection, selectionArgs, null);
            if (cursor != null && cursor.moveToFirst()) {
                final int column_index = cursor.getColumnIndexOrThrow(column);
                return cursor.getString(column_index);
            }
        } catch (IllegalArgumentException e) {
            Timber.d(e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return null;
    }

    /**
     * @param uri The Uri to check.
     * @return Whether the Uri authority is ExternalStorageProvider.
     */
    private static boolean isExternalStorageDocument(Uri uri) {
        return "com.android.externalstorage.documents".equals(uri.getAuthority());
    }

    /**
     * @param uri The Uri to check.
     * @return Whether the Uri authority is DownloadsProvider.
     */
    private static boolean isDownloadsDocument(Uri uri) {
        return "com.android.providers.downloads.documents".equals(uri.getAuthority());
    }

    /**
     * @param uri The Uri to check.
     * @return Whether the Uri authority is MediaProvider.
     */
    private static boolean isMediaDocument(Uri uri) {
        return "com.android.providers.media.documents".equals(uri.getAuthority());
    }

    /**
     * Check if the URI is owned by the current app.
     */
    public static boolean isSelfOwned(Context context, Uri uri) {
        return uri.getAuthority().equals(context.getPackageName() + ".provider");
    }

    /**
     * Copy content from source file to destination file.
     *
     * @param source      stream copied from
     * @param destination stream copied to
     * @throws IOException thrown when failing to read source or opening destination file
     */
    public static void copy(@NonNull FileInputStream source, @NonNull FileOutputStream destination)
            throws IOException {
        FileChannel sourceChannel = source.getChannel();
        FileChannel destinationChannel = destination.getChannel();
        sourceChannel.transferTo(0, sourceChannel.size(), destinationChannel);
    }

    /**
     * Copy content from source file to destination file.
     *
     * @param source      file descriptor copied from
     * @param destination file path copied to
     * @throws IOException thrown when failing to read source or opening destination file
     */
    public static void copy(@NonNull FileDescriptor source, @NonNull String destination)
            throws IOException {
        copy(new FileInputStream(source), new FileOutputStream(destination));
    }

}