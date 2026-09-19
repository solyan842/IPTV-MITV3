package com.solyan.iptv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Build;
import android.graphics.PixelFormat;
import android.provider.OpenableColumns;
import android.view.KeyEvent;
import android.view.Display;
import android.view.WindowManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.SurfaceView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.Timeline;
import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.DefaultRenderersFactory;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.analytics.AnalyticsListener;
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory;
import com.google.android.exoplayer2.source.MediaSource.MediaPeriodId;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.trackselection.ExoTrackSelection;
import com.google.android.exoplayer2.trackselection.FixedTrackSelection;
import com.google.android.exoplayer2.ui.PlayerView;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource;
import com.google.android.exoplayer2.upstream.BandwidthMeter;
import com.google.android.exoplayer2.util.MimeTypes;
import com.squareup.picasso.Picasso;

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
    private static final String KEY_LAST_CHANNEL_URL = "last_channel_url";
    private static final String KEY_LAST_CHANNEL_NAME = "last_channel_name";
    private static final int REQ_OPEN_M3U = 3001;

    private enum VideoMode { ADAPTIVE_1080, MITV3_HW_1080, AUTO }

    private TextView statusText;
    private LinearLayout topBar;
    private TextView currentListText;
    private TextView drawerTitle;
    private LinearLayout channelDrawer;
    private ListView channelList;
    private PlayerView playerView;
    private SurfaceView nativeSurface;
    private Button modeButton;
    private Button sourceButton;
    private Button channelsButton;

    private ExoPlayer player;
    private MediaPlayer nativePlayer;
    private boolean nativeActive = false;
    private int nativeGeneration = 0;
    private Channel nativeFallbackChannel;
    private DefaultTrackSelector trackSelector;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
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
    private int recentRebufferCount = 0;
    private long lastReadyAt = 0L;
    private int droppedSinceQualityCheck = 0;
    private int originalDisplayModeId = 0;
    private float originalPreferredRefreshRate = 0f;
    private boolean displayHas50Hz = false;
    private final Runnable hideDrawerRunnable = () -> {
        if (channelDrawer != null && channelDrawer.getVisibility() == View.VISIBLE) hideChannelDrawer();
    };
    private PlaylistStore playlistStore;

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
        nativeSurface = findViewById(R.id.nativeSurface);
        nativeSurface.getHolder().setFormat(PixelFormat.OPAQUE);
        nativeSurface.setZOrderMediaOverlay(false);
        nativeSurface.setWillNotDraw(true);
        modeButton = findViewById(R.id.safeButton);
        sourceButton = findViewById(R.id.sourceButton);
        channelsButton = findViewById(R.id.channelsButton);

        playlistStore = new PlaylistStore(this);
        adapter = new ChannelAdapter();
        channelList.setAdapter(adapter);

        displayHas50Hz = hasRefreshRateNear(50f);
        applyPerfectSessionRefresh();
        buildPlayer();
        applyVideoMode();
        updateModeLabel();
        setStatus(CodecProbe.summary());

        sourceButton.setOnClickListener(v -> { showTopBarTemporarily(); showSourceMenu(); });
        channelsButton.setOnClickListener(v -> { showTopBarTemporarily(); toggleChannelDrawer(); });
        modeButton.setOnClickListener(v -> {
            showTopBarTemporarily();
            showStatus(displayHas50Hz ? "PERFECT LOCK · 50Hz" : "PERFECT LOCK · refresh mặc định", 1800);
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
        topBar.setVisibility(View.VISIBLE);
        sourceButton.requestFocus();
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
        scheduleDrawerAutoHide();
    }

    private void hideChannelDrawer() {
        uiHandler.removeCallbacks(hideDrawerRunnable);
        cancelVisibleLogoRequests();
        channelDrawer.setVisibility(View.GONE);
        if (isAnyPlayerPlaying()) {
            requestPlaybackFocus();
            scheduleCleanPlaybackUi();
        } else {
            topBar.setVisibility(View.VISIBLE);
            sourceButton.requestFocus();
        }
    }

    private void buildPlayer() {
        DefaultRenderersFactory renderers = new DefaultRenderersFactory(this)
                .setMediaCodecSelector(HardwareCodecSelector.INSTANCE)
                .setEnableDecoderFallback(true)
                .setEnableAudioOffload(false)
                .setEnableAudioTrackPlaybackParams(false)
                .setEnableAudioFloatOutput(false)
                .setAllowedVideoJoiningTimeMs(5000);

        ExoTrackSelection.Factory fixedSelectionFactory =
                new ExoTrackSelection.Factory() {
                    @Override
                    public ExoTrackSelection[] createTrackSelections(
                            ExoTrackSelection.Definition[] definitions,
                            BandwidthMeter bandwidthMeter,
                            MediaPeriodId mediaPeriodId,
                            Timeline timeline) {
                        ExoTrackSelection[] selections = new ExoTrackSelection[definitions.length];
                        for (int i = 0; i < definitions.length; i++) {
                            ExoTrackSelection.Definition d = definitions[i];
                            if (d == null || d.tracks.length == 0) continue;
                            int best = chooseBestFixedTrack(d);
                            selections[i] = new FixedTrackSelection(d.group, best, d.type);
                        }
                        return selections;
                    }
                };

        trackSelector = new DefaultTrackSelector(this, fixedSelectionFactory);
        trackSelector.setParameters(
                trackSelector.buildUponParameters()
                        .setTunnelingEnabled(false)
        );

        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(30000, 60000, 5000, 8000)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build();

        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent("SolYan-IPTV/0.4.0 MiTV3-60")
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

        player.setPlaybackSpeed(1.0f);
        playerView.setPlayer(player);
        playerView.setUseController(false);

        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_BUFFERING) {
                    long now = android.os.SystemClock.elapsedRealtime();
                    if (lastReadyAt > 0 && now - lastReadyAt < 30000) {
                        recentRebufferCount++;
                    }
                    showStatus("Đang buffer…", 1200);
                } else if (state == Player.STATE_READY) {
                    lastReadyAt = android.os.SystemClock.elapsedRealtime();
                    showStatus("Đang phát · " + effectiveQualityText(), 1400);
                    if (!nativeActive && channelDrawer.getVisibility() != View.VISIBLE) {
                        requestPlaybackFocus();
                        scheduleCleanPlaybackUi();
                    }
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
                if (droppedSinceQualityCheck >= 120) {
                    droppedSinceQualityCheck = 0;
                }
                if (droppedSinceReport >= 120) droppedSinceReport = 0;
            }
        });
    }

    private int chooseBestFixedTrack(ExoTrackSelection.Definition definition) {
        int bestTrack = definition.tracks[0];
        long bestScore = Long.MIN_VALUE;

        for (int track : definition.tracks) {
            Format f = definition.group.getFormat(track);
            long score = 0L;

            int pixels = (f.width > 0 && f.height > 0) ? f.width * f.height : 0;
            int bitrate = f.bitrate > 0 ? f.bitrate : 0;
            float fps = f.frameRate > 0 ? f.frameRate : 0f;

            // Prefer 720p/50-style video, then bitrate. For audio, bitrate/channel info wins.
            if (pixels > 0) {
                score += ((long) pixels) * 1000000L;
                if (fps > 0f && fps <= 50.5f) score += 500000000000L;
                score += bitrate;
            } else {
                score += ((long) bitrate) * 1000L;
                if (f.channelCount > 0) score += f.channelCount * 100L;
                if (f.sampleRate > 0) score += f.sampleRate;
            }

            if (score > bestScore) {
                bestScore = score;
                bestTrack = track;
            }
        }
        return bestTrack;
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
            b.setMaxVideoSize(1280, 720)
             .setMaxVideoFrameRate(50)
             .setMaxVideoBitrate(6500000)
             .setForceHighestSupportedBitrate(true)
             .setPreferredVideoMimeTypes();
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
        if (videoMode == VideoMode.ADAPTIVE_1080) modeButton.setText("PERFECT LOCK");
        else if (videoMode == VideoMode.MITV3_HW_1080) modeButton.setText("1080 HW");
        else modeButton.setText("AUTO");
    }

    private String modeText() {
        if (videoMode == VideoMode.ADAPTIVE_1080) return "Perfect Lock 720";
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
                    restoreLastChannelAndAutoplay();
                    setStatus("Đã nạp “" + entry.name + "” · " + parsed.size() + " kênh");
                    showChannelDrawer();
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
        c.setRequestProperty("User-Agent", "SolYan-IPTV/0.4.0 MiTV3-60");
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
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_LAST_CHANNEL_URL, ch.url)
                .putString(KEY_LAST_CHANNEL_NAME, ch.name)
                .apply();

        activeDecoder = "";
        droppedSinceReport = 0;
        droppedSinceQualityCheck = 0;
        recentRebufferCount = 0;
        lastReadyAt = 0L;

        // MiTV3 / Android 5.x: keep ExoPlayer completely idle on the native path.
        if (Build.VERSION.SDK_INT <= 22) {
            startNativeChannel(ch);
        } else {
            applyVideoMode();
            updateModeLabel();
            startExoChannel(ch);
        }
    }

    private void startNativeChannel(Channel ch) {
        releaseNativePlayer();
        final int generation = ++nativeGeneration;
        nativeFallbackChannel = ch;
        nativeActive = true;

        if (player != null) {
            player.stop();
            player.clearMediaItems();
        }
        playerView.setVisibility(View.GONE);
        nativeSurface.setVisibility(View.VISIBLE);
        requestPlaybackFocus();
        uiHandler.removeCallbacks(hideTopBarRunnable);
        uiHandler.removeCallbacks(hideStatusRunnable);
        statusText.setVisibility(View.GONE);
        if (channelDrawer.getVisibility() != View.VISIBLE) topBar.setVisibility(View.GONE);

        try {
            MediaPlayer mp = new MediaPlayer();
            nativePlayer = mp;
            mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
            mp.setScreenOnWhilePlaying(true);
            mp.setDisplay(nativeSurface.getHolder());

            HashMap<String, String> headers = new HashMap<>(ch.headers);
            if (!headers.containsKey("User-Agent")) {
                headers.put("User-Agent", "SolYan-IPTV/0.4.0 MiTV3-60");
            }
            mp.setDataSource(this, Uri.parse(ch.url), headers);

            mp.setOnPreparedListener(p -> {
                if (p != nativePlayer || generation != nativeGeneration) return;
                try {
                    p.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT);
                    p.start();
                    if (channelDrawer.getVisibility() != View.VISIBLE) {
                        requestPlaybackFocus();
                        scheduleNativeCleanPlaybackUi();
                    }
                } catch (Exception e) {
                    fallbackToExo(ch, "Native start lỗi");
                }
            });

            mp.setOnCompletionListener(p -> {
                if (p != nativePlayer || generation != nativeGeneration) return;
                showStatus("Stream đã kết thúc", 3000);
                showTopBarTemporarily();
            });

            mp.setOnInfoListener((p, what, extra) -> {
                if (p != nativePlayer || generation != nativeGeneration) return true;
                if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) {
                    // Keep the native pipeline untouched; do not change quality/refresh.
                    return true;
                }
                if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END) {
                    scheduleNativeCleanPlaybackUi();
                    return true;
                }
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_TRACK_LAGGING) {
                    // Vendor decoder reports temporary lag. Do not trigger reselect/fallback.
                    return true;
                }
                return false;
            });

            mp.setOnBufferingUpdateListener((p, percent) -> {
                // Intentionally no UI updates while native video is playing.
            });

            mp.setOnErrorListener((p, what, extra) -> {
                if (p != nativePlayer || generation != nativeGeneration) return true;
                fallbackToExo(ch, "Native không tương thích");
                return true;
            });

            mp.prepareAsync();
        } catch (Exception e) {
            fallbackToExo(ch, "Native không mở được");
        }
    }

    private void fallbackToExo(Channel ch, String reason) {
        runOnUiThread(() -> {
            releaseNativePlayer();
            nativeActive = false;
            nativeSurface.setVisibility(View.GONE);
            playerView.setVisibility(View.VISIBLE);
            showStatus(reason + " · chuyển Perfect Lock", 1800);
            startExoChannel(ch);
        });
    }

    private void startExoChannel(Channel ch) {
        nativeActive = false;
        nativeSurface.setVisibility(View.GONE);
        playerView.setVisibility(View.VISIBLE);
        requestPlaybackFocus();
        setStatus("Mở: " + ch.name + " · " + modeText());

        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent(ch.headers.containsKey("User-Agent") ? ch.headers.get("User-Agent")
                        : "SolYan-IPTV/0.4.0 MiTV3-60")
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

    private void releaseNativePlayer() {
        nativeGeneration++;
        MediaPlayer p = nativePlayer;
        nativePlayer = null;
        if (p != null) {
            try { p.setOnPreparedListener(null); } catch (Exception ignored) {}
            try { p.setOnInfoListener(null); } catch (Exception ignored) {}
            try { p.setOnBufferingUpdateListener(null); } catch (Exception ignored) {}
            try { p.setOnErrorListener(null); } catch (Exception ignored) {}
            try { p.setOnCompletionListener(null); } catch (Exception ignored) {}
            try { p.stop(); } catch (Exception ignored) {}
            try { p.reset(); } catch (Exception ignored) {}
            try { p.release(); } catch (Exception ignored) {}
        }
    }

    private MediaItem buildMediaItem(String url) {
        MediaItem.Builder b = new MediaItem.Builder().setUri(Uri.parse(url));
        String lower = url.toLowerCase();
        if (lower.contains(".m3u8")) b.setMimeType(MimeTypes.APPLICATION_M3U8);
        else if (lower.contains(".mpd")) b.setMimeType(MimeTypes.APPLICATION_MPD);
        return b.build();
    }

    private String effectiveQualityText() {
        return modeText();
    }
    private boolean hasRefreshRateNear(float targetHz) {
        if (Build.VERSION.SDK_INT < 21) return false;
        try {
            Display display = getWindowManager().getDefaultDisplay();
            if (Build.VERSION.SDK_INT >= 23) {
                Display.Mode current = display.getMode();
                for (Display.Mode mode : display.getSupportedModes()) {
                    if (mode.getPhysicalWidth() == current.getPhysicalWidth()
                            && mode.getPhysicalHeight() == current.getPhysicalHeight()
                            && Math.abs(mode.getRefreshRate() - targetHz) <= 1.5f) {
                        return true;
                    }
                }
            }
            float[] rates = display.getSupportedRefreshRates();
            if (rates != null) {
                for (float rate : rates) {
                    if (Math.abs(rate - targetHz) <= 1.5f) return true;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private void applyPerfectSessionRefresh() {
        if (Build.VERSION.SDK_INT < 21 || !displayHas50Hz) return;
        try {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            if (originalPreferredRefreshRate == 0f) originalPreferredRefreshRate = lp.preferredRefreshRate;

            Display display = getWindowManager().getDefaultDisplay();

            if (Build.VERSION.SDK_INT >= 23) {
                Display.Mode current = display.getMode();
                if (originalDisplayModeId == 0) originalDisplayModeId = current.getModeId();

                Display.Mode best = null;
                float bestScore = Float.MAX_VALUE;
                for (Display.Mode mode : display.getSupportedModes()) {
                    if (mode.getPhysicalWidth() != current.getPhysicalWidth()
                            || mode.getPhysicalHeight() != current.getPhysicalHeight()) continue;
                    float score = Math.abs(mode.getRefreshRate() - 50f);
                    if (score < bestScore) {
                        bestScore = score;
                        best = mode;
                    }
                }
                if (best != null && bestScore <= 1.5f) {
                    lp.preferredDisplayModeId = best.getModeId();
                    lp.preferredRefreshRate = best.getRefreshRate();
                    getWindow().setAttributes(lp);
                    return;
                }
            }

            float[] rates = display.getSupportedRefreshRates();
            if (rates != null) {
                float bestRate = 0f;
                float bestScore = Float.MAX_VALUE;
                for (float rate : rates) {
                    float score = Math.abs(rate - 50f);
                    if (score < bestScore) {
                        bestScore = score;
                        bestRate = rate;
                    }
                }
                if (bestRate > 0f && bestScore <= 1.5f) {
                    lp.preferredRefreshRate = bestRate;
                    getWindow().setAttributes(lp);
                }
            }
        } catch (Throwable ignored) {}
    }

    private void restoreDisplayMode() {
        if (Build.VERSION.SDK_INT < 21) return;
        try {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            if (Build.VERSION.SDK_INT >= 23 && originalDisplayModeId != 0) {
                lp.preferredDisplayModeId = originalDisplayModeId;
            }
            lp.preferredRefreshRate = originalPreferredRefreshRate;
            getWindow().setAttributes(lp);
        } catch (Throwable ignored) {}
    }

    private void restoreLastChannelAndAutoplay() {
        if (channels.isEmpty()) return;
        String lastUrl = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_LAST_CHANNEL_URL, "");
        String lastName = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_LAST_CHANNEL_NAME, "");

        int match = 0;
        if (!lastUrl.isEmpty()) {
            for (int i = 0; i < channels.size(); i++) {
                if (lastUrl.equals(channels.get(i).url)) {
                    match = i;
                    break;
                }
            }
        } else if (!lastName.isEmpty()) {
            for (int i = 0; i < channels.size(); i++) {
                if (lastName.equalsIgnoreCase(channels.get(i).name)) {
                    match = i;
                    break;
                }
            }
        }

        selectedPosition = Math.max(0, Math.min(match, channels.size() - 1));
        adapter.notifyDataSetChanged();
        playChannel(channels.get(selectedPosition));
    }

    private boolean isAnyPlayerPlaying() {
        if (nativeActive && nativePlayer != null) {
            try { return nativePlayer.isPlaying(); } catch (Exception ignored) {}
        }
        return player != null && player.isPlaying();
    }

    private void requestPlaybackFocus() {
        if (nativeActive) nativeSurface.requestFocus();
        else playerView.requestFocus();
    }

    private void scheduleDrawerAutoHide() {
        uiHandler.removeCallbacks(hideDrawerRunnable);
        uiHandler.postDelayed(hideDrawerRunnable, 3000);
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
        if (isAnyPlayerPlaying()) {
            uiHandler.postDelayed(hideTopBarRunnable, 2600);
        }
    }

    private void hideTopBar() {
        if (channelDrawer != null && channelDrawer.getVisibility() == View.VISIBLE) return;
        View focused = getCurrentFocus();
        if (focused == sourceButton || focused == channelsButton || focused == modeButton) return;
        if (!isAnyPlayerPlaying()) return;
        topBar.setVisibility(View.GONE);
    }

    private void scheduleCleanPlaybackUi() {
        uiHandler.removeCallbacks(hideTopBarRunnable);
        uiHandler.removeCallbacks(hideStatusRunnable);
        uiHandler.postDelayed(hideTopBarRunnable, 1800);
        uiHandler.postDelayed(hideStatusRunnable, 1800);
    }

    private void scheduleNativeCleanPlaybackUi() {
        uiHandler.removeCallbacks(hideTopBarRunnable);
        uiHandler.removeCallbacks(hideStatusRunnable);
        statusText.setVisibility(View.GONE);
        if (channelDrawer == null || channelDrawer.getVisibility() != View.VISIBLE) {
            topBar.setVisibility(View.GONE);
        }
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
            View focused = getCurrentFocus();
            boolean drawerOpen = channelDrawer.getVisibility() == View.VISIBLE;
            boolean topFocused = focused == sourceButton || focused == channelsButton || focused == modeButton;

            if (drawerOpen) {
                scheduleDrawerAutoHide();
                if (key == KeyEvent.KEYCODE_DPAD_RIGHT || key == KeyEvent.KEYCODE_BACK) {
                    hideChannelDrawer();
                    return true;
                }
                return super.dispatchKeyEvent(event);
            }

            if (topFocused) {
                if (key == KeyEvent.KEYCODE_DPAD_LEFT) {
                    if (focused == modeButton) channelsButton.requestFocus();
                    else if (focused == channelsButton) sourceButton.requestFocus();
                    return true;
                }
                if (key == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    if (focused == sourceButton) channelsButton.requestFocus();
                    else if (focused == channelsButton) modeButton.requestFocus();
                    return true;
                }
                if (key == KeyEvent.KEYCODE_DPAD_DOWN && isAnyPlayerPlaying()) {
                    requestPlaybackFocus();
                    scheduleCleanPlaybackUi();
                    return true;
                }
                return super.dispatchKeyEvent(event);
            }

            if ((key == KeyEvent.KEYCODE_MENU || key == KeyEvent.KEYCODE_GUIDE || key == KeyEvent.KEYCODE_DPAD_RIGHT)
                    && !topFocused) {
                showTopBarTemporarily();
                sourceButton.requestFocus();
                return true;
            }

            if (key == KeyEvent.KEYCODE_DPAD_LEFT && !channels.isEmpty()) {
                showChannelDrawer();
                return true;
            }

            if (isAnyPlayerPlaying() && !channels.isEmpty()) {
                if (key == KeyEvent.KEYCODE_DPAD_UP) {
                    zapChannel(-1);
                    return true;
                }
                if (key == KeyEvent.KEYCODE_DPAD_DOWN) {
                    zapChannel(1);
                    return true;
                }
            }

            if (key == KeyEvent.KEYCODE_DPAD_UP && !isAnyPlayerPlaying()) {
                topBar.setVisibility(View.VISIBLE);
                sourceButton.requestFocus();
                return true;
            }

            if (key == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
                if (nativeActive && nativePlayer != null) {
                    try {
                        if (nativePlayer.isPlaying()) nativePlayer.pause(); else nativePlayer.start();
                    } catch (Exception ignored) {}
                    return true;
                }
                if (player != null) {
                    if (player.isPlaying()) player.pause(); else player.play();
                    return true;
                }
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
        if (channelDrawer.getVisibility() == View.VISIBLE) {
            adapter.notifyDataSetChanged();
        }
        Channel ch = channels.get(selectedPosition);
        if (!nativeActive) {
            showStatus((direction > 0 ? "Kênh sau: " : "Kênh trước: ") + ch.name, 1000);
        }
        playChannel(ch);
    }

    @Override protected void onStop() {
        super.onStop();
        if (nativeActive && nativePlayer != null) {
            try { if (nativePlayer.isPlaying()) nativePlayer.pause(); } catch (Exception ignored) {}
        }
        if (player != null) player.pause();
    }

    @Override protected void onDestroy() {
        restoreDisplayMode();
        super.onDestroy();
        uiHandler.removeCallbacksAndMessages(null);
        io.shutdownNow();
        releaseNativePlayer();
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

    private void cancelVisibleLogoRequests() {
        if (channelList == null) return;
        int childCount = channelList.getChildCount();
        for (int i = 0; i < childCount; i++) {
            View row = channelList.getChildAt(i);
            if (row == null) continue;
            ImageView logo = row.findViewById(R.id.channelLogo);
            if (logo != null) Picasso.get().cancelRequest(logo);
        }
    }

    private void bindLogo(ImageView imageView, Channel ch) {
        String logo = ch.logoUrl == null ? "" : ch.logoUrl.trim();
        imageView.setTag(logo);
        imageView.setImageResource(R.drawable.solyan_iptv_logo);
        if (logo.isEmpty()) return;

        Picasso.get()
                .load(logo)
                .placeholder(R.drawable.solyan_iptv_logo)
                .error(R.drawable.solyan_iptv_logo)
                .fit()
                .centerInside()
                .into(imageView);
    }

    private static final class ViewHolder {
        View row;
        ImageView logo;
        TextView name;
        TextView group;
    }
}
