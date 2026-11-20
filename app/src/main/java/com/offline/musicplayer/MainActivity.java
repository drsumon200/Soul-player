package com.offline.musicplayer;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.FileInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class MainActivity extends Activity {
    private static final int REQ_PERM = 1001;
    private static final int REQ_FILE = 1002;
    private static final String HOST = "local.music";

    private BgWebView webView;
    private ValueCallback<Uri[]> fileCallback;
    private boolean isPlayingNow = false;

    private static class BgWebView extends WebView {
        BgWebView(Context ctx) { super(ctx); }
        @Override
        protected void onWindowVisibilityChanged(int visibility) {
            if (visibility != View.GONE) super.onWindowVisibilityChanged(View.VISIBLE);
        }
    }

    private static class BoundedInputStream extends FilterInputStream {
        private long remaining;
        BoundedInputStream(InputStream in, long maxBytes) { super(in); this.remaining = maxBytes; }
        @Override public int read() throws IOException {
            if (remaining <= 0) return -1;
            int r = super.read();
            if (r != -1) remaining--;
            return r;
        }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (remaining <= 0) return -1;
            int r = super.read(b, off, (int) Math.min(len, remaining));
            if (r != -1) remaining -= r;
            return r;
        }
    }

    private final BroadcastReceiver ctrlReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String cmd = intent.getStringExtra(MusicService.EXTRA_CMD);
            if (cmd == null || webView == null) return;
            String js = "if(window.__playerControls){window.__playerControls." + cmd + "();}";
            runOnUiThread(() -> webView.evaluateJavascript(js, null));
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new BgWebView(this);
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setAllowFileAccess(true);

        webView.addJavascriptInterface(new AndroidBridge(), "AndroidMusic");
        webView.setWebViewClient(new LocalAssetClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = cb;
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("audio/*");
                i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                try { startActivityForResult(i, REQ_FILE); return true; }
                catch (Exception e) { fileCallback = null; return false; }
            }
        });

        setContentView(webView);

        IntentFilter filter = new IntentFilter(MusicService.ACTION_CTRL);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(ctrlReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(ctrlReceiver, filter);

        webView.loadUrl("https://" + HOST + "/index.html");
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_FILE && fileCallback != null) {
            Uri[] out = null;
            if (res == RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    int n = data.getClipData().getItemCount();
                    out = new Uri[n];
                    for (int i = 0; i < n; i++) out[i] = data.getClipData().getItemAt(i).getUri();
                } else if (data.getData() != null) {
                    out = new Uri[]{ data.getData() };
                }
            }
            fileCallback.onReceiveValue(out);
            fileCallback = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (isPlayingNow) moveTaskToBack(true);
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(ctrlReceiver); } catch (Exception ignored) {}
        stopService(new Intent(this, MusicService.class));
        if (webView != null) webView.destroy();
        super.onDestroy();
    }

    private boolean hasAudioPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            return checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED;
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private void requestNeededPermissions() {
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{
                    Manifest.permission.READ_MEDIA_AUDIO,
                    Manifest.permission.POST_NOTIFICATIONS
            }, REQ_PERM);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(new String[]{ Manifest.permission.READ_EXTERNAL_STORAGE }, REQ_PERM);
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] grants) {
        super.onRequestPermissionsResult(req, perms, grants);
        if (req == REQ_PERM && hasAudioPermission()) {
            scanDeviceMusicAsync();
        }
    }

    private void scanDeviceMusicAsync() {
        new Thread(() -> {
            JSONArray arr = new JSONArray();
            Set<Long> validArtIds = new HashSet<>();
            Set<Long> checkedArtIds = new HashSet<>();
            String[] proj = {
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.ARTIST,
                    MediaStore.Audio.Media.ALBUM,
                    MediaStore.Audio.Media.ALBUM_ID,
                    MediaStore.Audio.Media.DURATION,
                    MediaStore.Audio.Media.SIZE,
                    MediaStore.Audio.Media.DATE_ADDED,
                    MediaStore.Audio.Media.DATA
            };
            String sel = MediaStore.Audio.Media.IS_MUSIC + " != 0";
            try (Cursor c = getContentResolver().query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj, sel, null,
                    MediaStore.Audio.Media.TITLE + " ASC")) {
                if (c != null) {
                    int iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
                    int iTitle = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE);
                    int iArtist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);
                    int iAlbum = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM);
                    int iAlbumId = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID);
                    int iDur = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);
                    int iSize = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE);
                    int iDate = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED);
                    int iData = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA);

                    while (c.moveToNext()) {
                        try {
                            long dur = c.getLong(iDur);
                            if (dur > 0 && dur < 5000) continue;
                            long id = c.getLong(iId);
                            long albumId = c.getLong(iAlbumId);
                            String path = c.getString(iData);
                            String folder = "/Music";
                            String ext = "MP3";
                            if (path != null) {
                                int slash = path.lastIndexOf('/');
                                if (slash > 0) {
                                    int prevSlash = path.lastIndexOf('/', slash - 1);
                                    folder = prevSlash >= 0 ? path.substring(prevSlash, slash) : path.substring(0, slash);
                                }
                                int dot = path.lastIndexOf('.');
                                if (dot > 0 && dot < path.length() - 1) ext = path.substring(dot + 1).toUpperCase();
                            }

                            String artUrl = null;
                            if (albumId > 0) {
                                if (!checkedArtIds.contains(albumId)) {
                                    checkedArtIds.add(albumId);
                                    Uri au = ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), albumId);
                                    try (InputStream test = getContentResolver().openInputStream(au)) {
                                        if (test != null) validArtIds.add(albumId);
                                    } catch (Exception ignored) {}
                                }
                                if (validArtIds.contains(albumId)) {
                                    artUrl = "https://" + HOST + "/art/" + albumId;
                                }
                            }

                            JSONObject o = new JSONObject();
                            o.put("id", id);
                            o.put("title", c.getString(iTitle));
                            o.put("artist", c.getString(iArtist));
                            o.put("album", c.getString(iAlbum));
                            o.put("folder", folder);
                            o.put("duration", dur);
                            o.put("size", c.getLong(iSize));
                            o.put("dateAdded", c.getLong(iDate) * 1000L);
                            o.put("ext", ext);
                            o.put("url", "https://" + HOST + "/track/" + id);
                            if (artUrl != null) o.put("artworkUrl", artUrl);
                            arr.put(o);
                        } catch (Exception ignored) {}
                    }
                }
            } catch (Exception ignored) {}

            final String payload = JSONObject.quote(arr.toString());
            runOnUiThread(() -> webView.evaluateJavascript(
                    "if(window.onNativeMusicScanned){window.onNativeMusicScanned(" + payload + ");}", null));
        }).start();
    }

    private class AndroidBridge {
        @JavascriptInterface
        public void scanAllMusic() {
            runOnUiThread(() -> {
                if (hasAudioPermission()) scanDeviceMusicAsync();
                else requestNeededPermissions();
            });
        }

        @JavascriptInterface
        public void updateNotification(String title, String artist, boolean playing) {
            isPlayingNow = playing;
            Intent svc = new Intent(MainActivity.this, MusicService.class);
            svc.putExtra("title", title);
            svc.putExtra("artist", artist);
            svc.putExtra("playing", playing);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc);
            else startService(svc);
        }
    }

    private class LocalAssetClient extends WebViewClient {
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
            Uri u = req.getUrl();
            if (u == null || !HOST.equals(u.getHost())) return super.shouldInterceptRequest(view, req);
            String path = u.getPath();
            if (path == null) return null;

            try {
                if ("/index.html".equals(path) || "/".equals(path)) {
                    return new WebResourceResponse("text/html", "utf-8", getAssets().open("index.html"));
                }

                if (path.startsWith("/track/")) {
                    long id = Long.parseLong(path.substring(7));
                    Uri mediaUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id);
                    String mime = getContentResolver().getType(mediaUri);
                    if (mime == null) mime = "audio/mpeg";
                    ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(mediaUri, "r");
                    if (pfd == null) return null;
                    long total = pfd.getStatSize();
                    FileInputStream fis = new ParcelFileDescriptor.AutoCloseInputStream(pfd);

                    String range = req.getRequestHeaders() != null ? req.getRequestHeaders().get("Range") : null;
                    Map<String, String> headers = new HashMap<>();
                    headers.put("Accept-Ranges", "bytes");
                    headers.put("Access-Control-Allow-Origin", "*");

                    if (range != null && range.startsWith("bytes=") && total > 0) {
                        String[] parts = range.substring(6).split("-");
                        long start = parts[0].isEmpty() ? 0 : Long.parseLong(parts[0]);
                        long end = (parts.length > 1 && !parts[1].isEmpty()) ? Long.parseLong(parts[1]) : total - 1;
                        if (end >= total) end = total - 1;
                        long len = Math.max(0, end - start + 1);
                        fis.getChannel().position(start);
                        headers.put("Content-Range", "bytes " + start + "-" + end + "/" + total);
                        headers.put("Content-Length", String.valueOf(len));
                        return new WebResourceResponse(mime, null, 206, "Partial Content", headers, new BoundedInputStream(fis, len));
                    } else {
                        if (total > 0) headers.put("Content-Length", String.valueOf(total));
                        return new WebResourceResponse(mime, null, 200, "OK", headers, fis);
                    }
                }

                if (path.startsWith("/art/")) {
                    long albumId = Long.parseLong(path.substring(5));
                    Uri artUri = ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), albumId);
                    InputStream is = getContentResolver().openInputStream(artUri);
                    if (is != null) return new WebResourceResponse("image/jpeg", null, is);
                }
            } catch (Exception ignored) {}
            return null;
        }
    }
}
```
