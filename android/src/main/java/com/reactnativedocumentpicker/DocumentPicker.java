package com.reactnativedocumentpicker;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.util.Log;
import android.webkit.MimeTypeMap;
import android.util.Base64;
import android.content.Context;

import com.facebook.react.bridge.ActivityEventListener;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Callback;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableMap;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * @see <a href="https://developer.android.com/guide/topics/providers/document-provider.html">android documentation</a>
 */
public class DocumentPicker extends ReactContextBaseJavaModule implements ActivityEventListener {
    private static final String NAME = "RNDocumentPicker";
    private static final int READ_REQUEST_CODE = 41;

    private static class Fields {
        private static final String FILE_SIZE = "fileSize";
        private static final String FILE_NAME = "fileName";
        private static final String TYPE = "type";
        private static final String DATA = "data";
    }

    private Callback callback;

    public DocumentPicker(ReactApplicationContext reactContext) {
        super(reactContext);
        this.reactContext = reactContext;
        this.reactContext.addActivityEventListener(this);
    }

    private final ReactApplicationContext reactContext;

    @Override
    public String getName() {
        return NAME;
    }

    @ReactMethod
    public void show(ReadableMap args, Callback callback) {
        Intent intent;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
            intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        } else {
            intent = new Intent(Intent.ACTION_PICK);
        }
        intent.addCategory(Intent.CATEGORY_OPENABLE);

        if (!args.isNull("filetype")) {
            ReadableArray filetypes = args.getArray("filetype");
            if (filetypes.size() > 0) {
                intent.setType(filetypes.getString(0));
            }
        }

        this.callback = callback;

        getReactApplicationContext().startActivityForResult(intent, READ_REQUEST_CODE, Bundle.EMPTY);
    }

    // removed @Override temporarily just to get it working on RN0.33 and RN0.32 - will remove
    public void onActivityResult(Activity activity, int requestCode, int resultCode, Intent data) {
        onActivityResult(requestCode, resultCode, data);
    }

    private File createFileFromURI(Uri uri) throws Exception {
        File file = new File(reactContext.getExternalCacheDir(), "document-" + uri.getLastPathSegment());
        InputStream input = reactContext.getContentResolver().openInputStream(uri);
        OutputStream output = new FileOutputStream(file);
        try {
            byte[] buffer = new byte[4 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            output.flush();
        } finally {
            output.close();
            input.close();
        }
        return file;
    }

    private String getBase64StringFromFile(String absoluteFilePath) {
        InputStream inputStream = null;
        try {
            inputStream = new FileInputStream(new File(absoluteFilePath));
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }

        byte[] bytes;
        byte[] buffer = new byte[8192];
        int bytesRead;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                output.write(buffer, 0, bytesRead);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        bytes = output.toByteArray();
        return Base64.encodeToString(bytes, Base64.NO_WRAP);
  }

    // removed @Override temporarily just to get it working on RN0.33 and RN0.32
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != READ_REQUEST_CODE)
            return;

        if (resultCode != Activity.RESULT_OK) {
            callback.invoke("Bad result code: " + resultCode, null);
            return;
        }

        if (data == null) {
            callback.invoke("No data", null);
            return;
        }

        try {
            Uri uri = data.getData();
            callback.invoke(null, toMapWithMetadata(uri));
        } catch (Exception e) {
            Log.e(NAME, "Failed to read", e);
            callback.invoke(e.getMessage(), null);
        }
    }

    private WritableMap toMapWithMetadata(Uri uri) {
        WritableMap map;
        if(uri.toString().startsWith("/")) {
            map = metaDataFromFile(new File(uri.toString()));
        } else if (uri.toString().startsWith("http")) {
            map = metaDataFromUri(uri);
        } else {
            map = metaDataFromContentResolver(uri);
        }

        map.putString("uri", uri.toString());

        return map;
    }

    private WritableMap metaDataFromUri(Uri uri) {
        WritableMap map = Arguments.createMap(); 

        File outputDir = getReactApplicationContext().getCacheDir();

        try{
            File file = createFileFromURI(uri);
            try {
                File downloaded = download(uri, outputDir);

                map.putDouble(Fields.FILE_SIZE, new Long(downloaded.length()).doubleValue());
                map.putString(Fields.FILE_NAME, downloaded.getName());
                map.putString(Fields.TYPE, mimeTypeFromName(uri.toString()));
                map.putString(Fields.DATA, getBase64StringFromFile(file.getAbsolutePath()));
            } catch (IOException e) {
                Log.e("DocumentPicker", "Failed to download file", e);
            }
        }
        catch (Exception e){
            // document not in cache
            Log.e("ERROR", "Error Message: ", e);
        }

        return map;
    }

    private WritableMap metaDataFromFile(File file) {
        WritableMap map = Arguments.createMap();

        if(!file.exists())
            return map;

        map.putDouble(Fields.FILE_SIZE, new Long(file.length()).doubleValue());
        map.putString(Fields.FILE_NAME, file.getName());
        map.putString(Fields.TYPE, mimeTypeFromName(file.getAbsolutePath()));
        map.putString(Fields.DATA, getBase64StringFromFile(file.getAbsolutePath()));

        return map;
    }

    private WritableMap metaDataFromContentResolver(Uri uri) {
        WritableMap map = Arguments.createMap();

        ContentResolver contentResolver = getReactApplicationContext().getContentResolver();

        map.putString(Fields.TYPE, contentResolver.getType(uri));

        Cursor cursor = contentResolver.query(uri, null, null, null, null, null);

        try {
            if (cursor != null && cursor.moveToFirst()) {

                map.putString(Fields.FILE_NAME, cursor.getString(cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)));

                int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (!cursor.isNull(sizeIndex)) {
                    String size = cursor.getString(sizeIndex);
                    if (size != null)
                        map.putDouble(Fields.FILE_SIZE, Double.valueOf(size));
                }
                try{
                    File file = createFileFromURI(uri);
                    map.putString("data", getBase64StringFromFile(file.getAbsolutePath()));
                }
                catch (Exception e){
                    Log.e("ERROR", "Error Message: ", e);
                }
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }

        return map;
    }

    public Context getContext(){
        return getReactApplicationContext();
    }

    private static File download(Uri uri, File outputDir) throws IOException {
        File file = File.createTempFile("prefix", "extension", outputDir);

        URL url = new URL(uri.toString());

        ReadableByteChannel channel = Channels.newChannel(url.openStream());
        try{
            FileOutputStream stream = new FileOutputStream(file);

            try {
                stream.getChannel().transferFrom(channel, 0, Long.MAX_VALUE);
                return file;
            } finally {
                stream.close();
            }
        } finally {
            channel.close();
        }
    }

    private static String mimeTypeFromName(String absolutePath) {
        String extension = MimeTypeMap.getFileExtensionFromUrl(absolutePath);
        if (extension != null) {
            return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
        } else {
            return null;
        }
    }

    // Required for RN 0.30+ modules than implement ActivityEventListener
    public void onNewIntent(Intent intent) {
    }
}
