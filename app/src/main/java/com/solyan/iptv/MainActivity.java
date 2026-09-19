package com.solyan.iptv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.util.LruCache;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.DefaultRenderersFactory;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.analytics.AnalyticsListener;
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.ui.PlayerView;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource;
import com.google.android.exoplayer2.util.MimeTypes;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.SSLException;

public class MainActivity extends Activity {
    private static final String PREFS = "solyan_iptv";
    private static final String KEY_LAST_URL = "last_url";
    private static final int REQ_OPEN_M3U = 3001;

    private enum VideoMode { ADAPTIVE_1080, MITV3_HW_1080, AUTO }

    private TextView statusText;
    private LinearLayout topBar;
    private TextView currentListText;
    private TextView drawerTitle;
    private LinearLayout channelDrawer;
    private ListView channelList;
    private PlayerView playerView;
    private Button modeButton;
    private Button sourceButton;

    private ExoPlayer player;
    private DefaultTrackSelector trackSelector;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService imageIo = Executors.newSingleThreadExecutor();
    private final List<Channel> channels = new ArrayList<>();
    private ChannelAdapter adapter;
    private VideoMode videoMode = VideoMode.ADAPTIVE_1080;
    private String activeDecoder = "";
    private int droppedSinceReport = 0;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable hideTopBarRunnable = () -> hideTopBar();
    private final Runnable hideStatusRunnable = () -> statusText.setVisibility(View.GONE);
    private int selectedPosition = 0;
    private long lastChannelZapAt = 0L;
    private boolean smart720Active = true;
    private int recentRebufferCount = 0;
    private long lastReadyAt = 0L;
    private int droppedSinceQualityCheck = 0;
    private final Runnable recover1080Runnable = () -> {
        if (smart720Active && player != null && player.isPlaying()) {
            smart720Active = (videoMode == VideoMode.ADAPTIVE_1080);
            recentRebufferCount = 0;
            droppedSinceQualityCheck = 0;
            applyVideoMode();
            showStatus("720p ổn định · thử nâng 1080p", 1800);
        }
    };
    private PlaylistStore playlistStore;

    private final LruCache<String, Bitmap> logoCache = new LruCache<String, Bitmap>(4096) {
        @Override protected int sizeOf(String key, Bitmap value) {
            return Math.max(1, value.getByteCount() / 1024);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.statusText);
        topBar = findViewById(R.id.topBar);
        currentListText = findViewById(R.id.currentListText);
        drawerTitle = findViewById(R.id.drawerTitle);
        channelDrawer = findViewById(R.id.channelDrawer);
        channelList = findViewById(R.id.channelList);
        playerView = findViewById(R.id.playerView);
        modeButton = findViewById(R.id.safeButton);
        sourceButton = findViewById(R.id.sourceButton);
        Button channelsButton = findViewById(R.id.channelsButton);

        playlistStore = new PlaylistStore(this);
        adapter = new ChannelAdapter();
        channelList.setAdapter(adapter);

        buildPlayer();
        applyVideoMode();
        updateModeLabel();
        setStatus(CodecProbe.summary());

        sourceButton.setOnClickListener(v -> { showTopBarTemporarily(); showSourceMenu(); });
        channelsButton.setOnClickListener(v -> { showTopBarTemporarily(); toggleChannelDrawer(); });
        modeButton.setOnClickListener(v -> {
            showTopBarTemporarily();
            if (videoMode == VideoMode.ADAPTIVE_1080) videoMode = VideoMode.MITV3_HW_1080;
            else if (videoMode == VideoMode.MITV3_HW_1080) videoMode = VideoMode.AUTO;
            else videoMode = VideoMode.ADAPTIVE_1080;
            smart720Active = false;
            recentRebufferCount = 0;
            droppedSinceQualityCheck = 0;
            uiHandler.removeCallbacks(recover1080Runnable);
            applyVideoMode();
            updateModeLabel();
            showStatus("Chế độ: " + modeText(), 1800);
        });

        channelList.setOnItemClickListener((p, v, pos, id) -> {
            selectedPosition = pos;
            adapter.notifyDataSetChanged();
            playChannel(channels.get(pos));
            hideChannelDrawer();
        });

        channelList.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                selectedPosition = position;
                adapter.notifyDataSetChanged();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });

        autoLoadLastPlaylist();
        enableImmersiveMode();
        showTopBarTemporarily();
        playerView.requestFocus();
    }

    private void showSourceMenu() {
        String[] items = {"MỞ URL", "MỞ FILE / USB", "LIST ĐÃ LƯU"};
        new AlertDialog.Builder(this)
                .setTitle("Nguồn phát")
                .setItems(items, (d, which) -> {
                    if (which == 0) showUrlDialog();
                    else if (which == 1) openFilePicker();
                    else showSavedLists();
                })
                .setNegativeButton("ĐÓNG", null)
                .show();
    }

    private void showUrlDialog() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("https://.../playlist.m3u");
        input.setText(getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_LAST_URL, ""));
        input.setSelectAllOnFocus(true);
        input.setPadding(24, 8, 24, 8);

        new AlertDialog.Builder(this)
                .setTitle("Mở URL")
                .setView(input)
                .setPositiveButton("MỞ", (d, w) -> openTypedUrl(input.getText().toString().trim()))
                .setNegativeButton("HỦY", null)
                .show();
    }

    private void toggleChannelDrawer() {
        if (channelDrawer.getVisibility() == View.VISIBLE) hideChannelDrawer();
        else showChannelDrawer();
    }

    private void showChannelDrawer() {
        if (channels.isEmpty()) {
            setStatus("Chưa có playlist. Chọn NGUỒN để mở URL hoặc file.");
            return;
        }
        channelDrawer.setVisibility(View.VISIBLE);
        showTopBarTemporarily();
        drawerTitle.setText("DANH SÁCH KÊNH  ·  " + channels.size());
        adapter.notifyDataSetChanged();
        channelList.setSelection(Math.max(0, Math.min(selectedPosition, channels.size() - 1)));
        channelList.requestFocus();
    }

    private void hideChannelDrawer() {
        channelDrawer.setVisibility(View.GONE);
        playerView.requestFocus();
        if (player != null && player.isPlaying()) scheduleCleanPlaybackUi();
    }

    private void buildPlayer() {
        DefaultRenderersFactory renderers = new DefaultRenderersFactory(this)
                .setMediaCodecSelector(HardwareCodecSelector.INSTANCE)
                .setEnableDecoderFallback(true);

        trackSelector = new DefaultTrackSelector(this);

        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(20000, 60000, 2500, 6000)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build();

        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent("SolYan-IPTV/0.2.9 MiTV3-60")
                .setConnectTimeoutMs(12000)
                .setReadTimeoutMs(20000)
                .setAllowCrossProtocolRedirects(true);

        DefaultMediaSourceFactory factory = new DefaultMediaSourceFactory(this)
                .setDataSourceFactory(http);

        player = new ExoPlayer.Builder(this, renderers)
                .setTrackSelector(trackSelector)
                .setLoadControl(loadControl)
                .setMediaSourceFactory(factory)
                .build();

        playerView.setPlayer(player);
        playerView.setUseController(false);

        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_BUFFERING) {
                    long now = android.os.SystemClock.elapsedRealtime();
                    if (lastReadyAt > 0 && now - lastReadyAt < 30000) {
                        recentRebufferCount++;
                        if (recentRebufferCount >= 2) activateSmart720("buffer yếu");
                    }
                    showStatus("Đang buffer…", 1200);
                } else if (state == Player.STATE_READY) {
                    lastReadyAt = android.os.SystemClock.elapsedRealtime();
                    schedule1080Recovery();
                    showStatus("Đang phát · " + effectiveQualityText(), 1400);
                    scheduleCleanPlaybackUi();
                } else if (state == Player.STATE_ENDED) {
                    showStatus("Stream đã kết thúc", 3000);
                    showTopBarTemporarily();
                }
            }

            @Override public void onPlayerError(PlaybackException error) {
                showStatus("Lỗi phát: " + readableError(error), 6500);
                showTopBarTemporarily();
            }
        });

        player.addAnalyticsListener(new AnalyticsListener() {
            @Override public void onVideoDecoderInitialized(
                    EventTime eventTime, String decoderName,
                    long initializedTimestampMs, long initializationDurationMs) {
                activeDecoder = decoderName == null ? "" : decoderName;
                boolean sw = HardwareCodecSelector.isSoftwareCodec(activeDecoder);
                if (sw) {
                    showStatus("⚠ Đang dùng software decoder: " + activeDecoder, 6500);
                }
            }

            @Override public void onDroppedVideoFrames(EventTime eventTime, int droppedFrames, long elapsedMs) {
                droppedSinceReport += droppedFrames;
                droppedSinceQualityCheck += droppedFrames;
                if (droppedSinceQualityCheck >= 45) {
                    activateSmart720("decoder rơi frame");
                    droppedSinceQualityCheck = 0;
                }
                if (droppedSinceReport >= 120) droppedSinceReport = 0;
            }
        });
    }

    private String readableError(PlaybackException error) {
        Throwable c = error.getCause();
        String msg = c != null && c.getMessage() != null ? c.getMessage() : error.getMessage();
        if (c instanceof UnknownHostException) return "DNS / host không truy cập được";
        if (c instanceof SocketTimeoutException) return "Kết nối stream bị timeout";
        if (c instanceof SSLException) return "Lỗi SSL/TLS";
        if (msg != null) {
            if (msg.contains("403")) return "403 · stream từ chối truy cập";
            if (msg.contains("404")) return "404 · stream không tồn tại";
            if (msg.toLowerCase().contains("connection refused")) return "Máy chủ từ chối kết nối";
        }
        return error.getErrorCodeName() + (msg == null || msg.isEmpty() ? "" : " · " + msg);
    }

    private void applyVideoMode() {
        if (trackSelector == null) return;
        DefaultTrackSelector.Parameters.Builder b = trackSelector.buildUponParameters();
        b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
         .setExceedVideoConstraintsIfNecessary(true);

        if (videoMode == VideoMode.ADAPTIVE_1080) {
            if (smart720Active) {
                b.setMaxVideoSize(1280, 720)
                 .setMaxVideoFrameRate(60)
                 .setMaxVideoBitrate(5500000)
                 .setPreferredVideoMimeTypes();
            } else {
                b.setMaxVideoSize(1920, 1080)
                 .setMaxVideoFrameRate(60)
                 .setMaxVideoBitrate(12000000)
                 .setPreferredVideoMimeTypes();
            }
        } else if (videoMode == VideoMode.MITV3_HW_1080) {
            b.setMaxVideoSize(1920, 1080)
             .setMaxVideoFrameRate(60)
             .setMaxVideoBitrate(12000000)
             .setPreferredVideoMimeTypes(MimeTypes.VIDEO_H265, MimeTypes.VIDEO_H264);
        } else {
            b.clearVideoSizeConstraints()
             .setMaxVideoFrameRate(Integer.MAX_VALUE)
             .setMaxVideoBitrate(Integer.MAX_VALUE)
             .setPreferredVideoMimeTypes();
        }
        trackSelector.setParameters(b);
    }

    private void updateModeLabel() {
        if (videoMode == VideoMode.ADAPTIVE_1080) modeButton.setText(smart720Active ? "SMART 720" : "SMART 1080");
        else if (videoMode == VideoMode.MITV3_HW_1080) modeButton.setText("1080 HW");
        else modeButton.setText("AUTO");
    }

    private String modeText() {
        if (videoMode == VideoMode.ADAPTIVE_1080) return smart720Active ? "Smart 720" : "Smart 1080";
        if (videoMode == VideoMode.MITV3_HW_1080) return "1080 HW";
        return "AUTO";
    }

    private String decoderSuffix() {
        return activeDecoder.isEmpty() ? "" : " · " + activeDecoder;
    }

    private void openFilePicker() {
        UsbFileBrowser.show(this, new UsbFileBrowser.Callback() {
            @Override public void onFileSelected(File file) { openLocalPlaylistFile(file); }
            @Override public void onNoExternalStorage() {
                setStatus("Không phát hiện USB trực tiếp · mở trình chọn hệ thống");
                openSystemFilePicker();
            }
        });
    }

    private void openSystemFilePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        try {
            startActivityForResult(intent, REQ_OPEN_M3U);
        } catch (ActivityNotFoundException e) {
            Intent fallback = new Intent(Intent.ACTION_GET_CONTENT);
            fallback.addCategory(Intent.CATEGORY_OPENABLE);
            fallback.setType("*/*");
            try {
                startActivityForResult(fallback, REQ_OPEN_M3U);
            } catch (ActivityNotFoundException ex) {
                setStatus("Firmware không có trình chọn file");
            }
        }
    }

    private void openLocalPlaylistFile(File file) {
        String suggestedName = stripPlaylistExtension(file.getName());
        final String defaultName = suggestedName.isEmpty() ? "Playlist TV" : suggestedName;
        setStatus("Đang đọc: " + file.getAbsolutePath());

        io.execute(() -> {
            try {
                String text = readFileText(file);
                List<Channel> parsed = M3uParser.parse(text);
                if (parsed.isEmpty()) throw new Exception("Không tìm thấy kênh trong file");
                runOnUiThread(() -> promptSavePlaylist(text, defaultName, file.getAbsolutePath(), parsed));
            } catch (Exception e) {
                runOnUiThread(() -> setStatus("Không mở được file: " + e.getMessage()));
            }
        });
    }

    private String stripPlaylistExtension(String name) {
        String lower = name.toLowerCase();
        if (lower.endsWith(".m3u8")) return name.substring(0, name.length() - 5);
        if (lower.endsWith(".m3u") || lower.endsWith(".ndl")) return name.substring(0, name.length() - 4);
        if (lower.endsWith(".dl")) return name.substring(0, name.length() - 3);
        return name;
    }

    private String readFileText(File file) throws Exception {
        FileInputStream in = new FileInputStream(file);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
        in.close();
        return new String(out.toByteArray(), "UTF-8");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_OPEN_M3U || resultCode != RESULT_OK || data == null || data.getData() == null) return;

        Uri uri = data.getData();
        final String defaultName = stripPlaylistExtension(fileDisplayName(uri));
        setStatus("Đang đọc file M3U…");

        io.execute(() -> {
            try {
                String text = readUriText(uri);
                List<Channel> parsed = M3uParser.parse(text);
                if (parsed.isEmpty()) throw new Exception("Không tìm thấy kênh trong file");
                runOnUiThread(() -> promptSavePlaylist(text, defaultName.isEmpty() ? "Playlist TV" : defaultName, "FILE", parsed));
            } catch (Exception e) {
                runOnUiThread(() -> setStatus("Không mở được file: " + e.getMessage()));
            }
        });
    }

    private String fileDisplayName(Uri uri) {
        Cursor c = null;
        try {
            c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) return c.getString(i);
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        String s = uri.getLastPathSegment();
        return s == null ? "" : s;
    }

    private String readUriText(Uri uri) throws Exception {
        InputStream in = getContentResolver().openInputStream(uri);
        if (in == null) throw new Exception("Không đọc được dữ liệu");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
        in.close();
        return new String(out.toByteArray(), "UTF-8");
    }

    private void openTypedUrl(String url) {
        if (url.isEmpty()) {
            setStatus("URL đang trống");
            return;
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_LAST_URL, url).apply();

        if (M3uParser.looksLikePlaylistUrl(url)) loadPlaylistUrl(url);
        else playChannel(new Channel("Direct stream", url, "", "", "", Collections.emptyMap()));
    }

    private void loadPlaylistUrl(String url) {
        setStatus("Đang tải playlist…");
        io.execute(() -> {
            try {
                String text = downloadText(url);
                List<Channel> parsed = M3uParser.parse(text);
                if (parsed.isEmpty()) throw new Exception("Playlist không có kênh");
                String suggested = hostName(url);
                runOnUiThread(() -> promptSavePlaylist(text, suggested, url, parsed));
            } catch (Exception e) {
                runOnUiThread(() -> setStatus("Không tải được playlist: " + e.getMessage()));
            }
        });
    }

    private void promptSavePlaylist(String text, String suggestedName, String source, List<Channel> parsed) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(suggestedName);
        input.setSelectAllOnFocus(true);
        input.setPadding(24, 8, 24, 8);

        new AlertDialog.Builder(this)
                .setTitle("Đặt tên playlist")
                .setMessage("Lưu lại để lần sau mở app không cần chọn lại.")
                .setView(input)
                .setPositiveButton("LƯU", (dialog, which) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) name = "Playlist TV";
                    try {
                        playlistStore.save(name, text, source);
                        applyParsedPlaylist(parsed, name);
                        setStatus("Đã lưu “" + name + "” · " + parsed.size() + " kênh");
                        showChannelDrawer();
                    } catch (Exception e) {
                        setStatus("Lỗi lưu playlist: " + e.getMessage());
                    }
                })
                .setNegativeButton("CHỈ MỞ", (dialog, which) -> {
                    applyParsedPlaylist(parsed, suggestedName);
                    setStatus("Đã mở " + parsed.size() + " kênh · chưa lưu");
                    showChannelDrawer();
                })
                .show();
    }

    private void showSavedLists() {
        List<PlaylistStore.Entry> saved = playlistStore.list();
        if (saved.isEmpty()) {
            setStatus("Chưa có playlist nào được lưu");
            return;
        }
        String[] names = new String[saved.size()];
        for (int i = 0; i < saved.size(); i++) names[i] = saved.get(i).name;

        new AlertDialog.Builder(this)
                .setTitle("Playlist đã lưu")
                .setItems(names, (dialog, which) -> loadSavedPlaylist(saved.get(which)))
                .setNegativeButton("ĐÓNG", null)
                .show();
    }

    private void autoLoadLastPlaylist() {
        PlaylistStore.Entry last = playlistStore.last();
        if (last != null) loadSavedPlaylist(last);
    }

    private void loadSavedPlaylist(PlaylistStore.Entry entry) {
        setStatus("Đang mở “" + entry.name + "”…");
        io.execute(() -> {
            try {
                String text = playlistStore.read(entry);
                List<Channel> parsed = M3uParser.parse(text);
                runOnUiThread(() -> {
                    playlistStore.setLast(entry.name);
                    applyParsedPlaylist(parsed, entry.name);
                    setStatus("Đã nạp “" + entry.name + "” · " + parsed.size() + " kênh");
                });
            } catch (Exception e) {
                runOnUiThread(() -> setStatus("Không mở được list đã lưu: " + e.getMessage()));
            }
        });
    }

    private void applyParsedPlaylist(List<Channel> parsed, String displayName) {
        channels.clear();
        channels.addAll(parsed);
        selectedPosition = 0;
        adapter.notifyDataSetChanged();
        currentListText.setText(displayName + " · " + parsed.size() + " kênh");
        drawerTitle.setText("DANH SÁCH KÊNH  ·  " + parsed.size());
    }

    private String hostName(String url) {
        try {
            String h = new URL(url).getHost();
            return (h == null || h.isEmpty()) ? "Playlist TV" : h;
        } catch (Exception e) {
            return "Playlist TV";
        }
    }

    private String downloadText(String urlString) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlString).openConnection();
        c.setConnectTimeout(12000);
        c.setReadTimeout(20000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "SolYan-IPTV/0.2.9 MiTV3-60");
        c.connect();
        int code = c.getResponseCode();
        if (code < 200 || code >= 300) throw new Exception("HTTP " + code);

        BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line).append('\n');
        br.close();
        c.disconnect();
        return sb.toString();
    }

    private void playChannel(Channel ch) {
        activeDecoder = "";
        droppedSinceReport = 0;
        droppedSinceQualityCheck = 0;
        recentRebufferCount = 0;
        lastReadyAt = 0L;
        smart720Active = (videoMode == VideoMode.ADAPTIVE_1080);
        uiHandler.removeCallbacks(recover1080Runnable);
        applyVideoMode();
        updateModeLabel();
        setStatus("Mở: " + ch.name + " · " + modeText());

        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent(ch.headers.containsKey("User-Agent") ? ch.headers.get("User-Agent")
                        : "SolYan-IPTV/0.2.9 MiTV3-60")
                .setConnectTimeoutMs(12000)
                .setReadTimeoutMs(20000)
                .setAllowCrossProtocolRedirects(true);

        if (!ch.headers.isEmpty()) {
            HashMap<String, String> headers = new HashMap<>(ch.headers);
            headers.remove("User-Agent");
            http.setDefaultRequestProperties(headers);
        }

        DefaultMediaSourceFactory factory = new DefaultMediaSourceFactory(this)
                .setDataSourceFactory(http);

        player.setMediaSource(factory.createMediaSource(buildMediaItem(ch.url)));
        player.prepare();
        player.play();
    }

    private MediaItem buildMediaItem(String url) {
        MediaItem.Builder b = new MediaItem.Builder().setUri(Uri.parse(url));
        String lower = url.toLowerCase();
        if (lower.contains(".m3u8")) b.setMimeType(MimeTypes.APPLICATION_M3U8);
        else if (lower.contains(".mpd")) b.setMimeType(MimeTypes.APPLICATION_MPD);
        return b.build();
    }

    private void activateSmart720(String reason) {
        if (videoMode != VideoMode.ADAPTIVE_1080 || smart720Active) return;
        smart720Active = true;
        uiHandler.removeCallbacks(recover1080Runnable);
        applyVideoMode();
        updateModeLabel();
        showStatus("Tự hạ 720p · " + reason, 1800);
        schedule1080Recovery();
    }

    private void schedule1080Recovery() {
        uiHandler.removeCallbacks(recover1080Runnable);
        if (videoMode == VideoMode.ADAPTIVE_1080 && smart720Active) {
            uiHandler.postDelayed(recover1080Runnable, 60000);
        }
    }

    private String effectiveQualityText() {
        if (videoMode == VideoMode.ADAPTIVE_1080) return smart720Active ? "Smart 720" : "Smart 1080";
        return modeText();
    }

    private void setStatus(String text) {
        showStatus(text, 2600);
    }

    private void showStatus(String text, long durationMs) {
        runOnUiThread(() -> {
            uiHandler.removeCallbacks(hideStatusRunnable);
            statusText.setText(text);
            statusText.setVisibility(View.VISIBLE);
            if (durationMs > 0) uiHandler.postDelayed(hideStatusRunnable, durationMs);
        });
    }

    private void showTopBarTemporarily() {
        uiHandler.removeCallbacks(hideTopBarRunnable);
        topBar.setVisibility(View.VISIBLE);
        if (player != null && player.isPlaying()) {
            uiHandler.postDelayed(hideTopBarRunnable, 2600);
        }
    }

    private void hideTopBar() {
        if (channelDrawer != null && channelDrawer.getVisibility() == View.VISIBLE) return;
        topBar.setVisibility(View.GONE);
    }

    private void scheduleCleanPlaybackUi() {
        uiHandler.removeCallbacks(hideTopBarRunnable);
        uiHandler.removeCallbacks(hideStatusRunnable);
        uiHandler.postDelayed(hideTopBarRunnable, 1800);
        uiHandler.postDelayed(hideStatusRunnable, 1800);
    }

    private void enableImmersiveMode() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            int key = event.getKeyCode();

            if (key == KeyEvent.KEYCODE_DPAD_LEFT
                    && channelDrawer.getVisibility() != View.VISIBLE && !channels.isEmpty()) {
                showChannelDrawer();
                return true;
            }

            if ((key == KeyEvent.KEYCODE_MENU || key == KeyEvent.KEYCODE_GUIDE)
                    && channelDrawer.getVisibility() != View.VISIBLE) {
                showTopBarTemporarily();
                sourceButton.requestFocus();
                return true;
            }

            if (channelDrawer.getVisibility() != View.VISIBLE && !channels.isEmpty()) {
                if (key == KeyEvent.KEYCODE_DPAD_UP) {
                    zapChannel(-1);
                    return true;
                }
                if (key == KeyEvent.KEYCODE_DPAD_DOWN) {
                    zapChannel(1);
                    return true;
                }
            }

            if ((key == KeyEvent.KEYCODE_DPAD_RIGHT || key == KeyEvent.KEYCODE_BACK)
                    && channelDrawer.getVisibility() == View.VISIBLE) {
                hideChannelDrawer();
                return true;
            }

            if (key == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE && player != null) {
                if (player.isPlaying()) player.pause(); else player.play();
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private void zapChannel(int direction) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastChannelZapAt < 350) return;
        lastChannelZapAt = now;

        int count = channels.size();
        if (count == 0) return;

        selectedPosition = (selectedPosition + direction + count) % count;
        adapter.notifyDataSetChanged();
        Channel ch = channels.get(selectedPosition);
        showStatus((direction > 0 ? "Kênh sau: " : "Kênh trước: ") + ch.name, 1400);
        playChannel(ch);
    }

    @Override protected void onStop() {
        super.onStop();
        if (player != null) player.pause();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        uiHandler.removeCallbacksAndMessages(null);
        io.shutdownNow();
        imageIo.shutdownNow();
        if (player != null) {
            player.release();
            player = null;
        }
    }

    private final class ChannelAdapter extends ArrayAdapter<Channel> {
        private final LayoutInflater inflater = LayoutInflater.from(MainActivity.this);

        ChannelAdapter() {
            super(MainActivity.this, 0, channels);
        }

        @Override public View getView(int position, View convertView, ViewGroup parent) {
            ViewHolder h;
            if (convertView == null) {
                convertView = inflater.inflate(R.layout.channel_list_item, parent, false);
                h = new ViewHolder();
                h.row = convertView.findViewById(R.id.channelRow);
                h.logo = convertView.findViewById(R.id.channelLogo);
                h.name = convertView.findViewById(R.id.channelName);
                h.group = convertView.findViewById(R.id.channelGroup);
                convertView.setTag(h);
            } else {
                h = (ViewHolder) convertView.getTag();
            }

            Channel ch = getItem(position);
            if (ch == null) return convertView;

            h.name.setText(ch.name);
            h.group.setText(ch.group == null || ch.group.trim().isEmpty() ? "Kênh TV" : ch.group);

            boolean selected = position == selectedPosition;
            h.row.setBackgroundResource(selected ? R.drawable.channel_row_bg_selected : R.drawable.channel_row_bg);
            h.row.setScaleX(selected ? 1.025f : 1f);
            h.row.setScaleY(selected ? 1.025f : 1f);
            h.row.setAlpha(selected ? 1f : 0.96f);

            bindLogo(h.logo, ch);
            return convertView;
        }
    }

    private void bindLogo(ImageView imageView, Channel ch) {
        String logo = ch.logoUrl == null ? "" : ch.logoUrl.trim();
        imageView.setTag(logo);
        imageView.setImageResource(R.drawable.solyan_iptv_logo);
        if (logo.isEmpty()) return;
        if (channelDrawer == null || channelDrawer.getVisibility() != View.VISIBLE) return;

        Bitmap cached = logoCache.get(logo);
        if (cached != null) {
            imageView.setImageBitmap(cached);
            return;
        }

        imageIo.execute(() -> {
            Bitmap bmp = downloadBitmap(logo, ch.headers);
            if (bmp != null) {
                logoCache.put(logo, bmp);
                runOnUiThread(() -> {
                    Object tag = imageView.getTag();
                    if (tag != null && logo.equals(tag.toString())) imageView.setImageBitmap(bmp);
                });
            }
        });
    }

    private Bitmap downloadBitmap(String urlString, Map<String, String> headers) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(urlString).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(12000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", headers != null && headers.containsKey("User-Agent")
                    ? headers.get("User-Agent") : "SolYan-IPTV/0.2.9 MiTV3-60");
            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    if (!"User-Agent".equalsIgnoreCase(e.getKey())) c.setRequestProperty(e.getKey(), e.getValue());
                }
            }
            c.connect();
            if (c.getResponseCode() < 200 || c.getResponseCode() >= 300) return null;
            return BitmapFactory.decodeStream(c.getInputStream());
        } catch (Exception ignored) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static final class ViewHolder {
        View row;
        ImageView logo;
        TextView name;
        TextView group;
    }
}
