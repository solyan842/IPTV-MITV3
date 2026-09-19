package com.solyan.iptv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.KeyEvent;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
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
import java.io.ByteArrayOutputStream;\nimport java.io.File;\nimport java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String PREFS = "solyan_iptv";
    private static final String KEY_LAST_URL = "last_url";
    private static final int REQ_OPEN_M3U = 3001;

    private enum VideoMode { MITV3_HW_1080, AVC_1080, AUTO }

    private EditText urlInput;
    private TextView statusText;
    private TextView currentListText;
    private ListView channelList;
    private PlayerView playerView;
    private Button modeButton;
    private ExoPlayer player;
    private DefaultTrackSelector trackSelector;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final List<Channel> channels = new ArrayList<>();
    private ArrayAdapter<Channel> adapter;
    private VideoMode videoMode = VideoMode.MITV3_HW_1080;
    private String activeDecoder = "";
    private int droppedSinceReport = 0;
    private PlaylistStore playlistStore;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);

        urlInput = findViewById(R.id.urlInput);
        statusText = findViewById(R.id.statusText);
        currentListText = findViewById(R.id.currentListText);
        channelList = findViewById(R.id.channelList);
        playerView = findViewById(R.id.playerView);
        modeButton = findViewById(R.id.safeButton);

        Button loadButton = findViewById(R.id.loadButton);
        Button openFileButton = findViewById(R.id.openFileButton);
        Button savedListsButton = findViewById(R.id.savedListsButton);

        playlistStore = new PlaylistStore(this);

        adapter = new ArrayAdapter<Channel>(this, android.R.layout.simple_list_item_1, channels) {
            @Override public View getView(int position, View convertView, android.view.ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                TextView t = v.findViewById(android.R.id.text1);
                t.setTextColor(getResources().getColor(R.color.text));
                t.setTextSize(15f);
                t.setMinHeight(58);
                t.setGravity(android.view.Gravity.CENTER_VERTICAL);
                t.setBackgroundResource(R.drawable.focus_bg);
                t.setSingleLine(false);
                return v;
            }
        };
        channelList.setAdapter(adapter);

        urlInput.setText(getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_LAST_URL, ""));

        buildPlayer();
        applyVideoMode();
        modeButton.setText("MiTV3 HW 1080");
        setStatus(CodecProbe.summary());

        loadButton.setOnClickListener(v -> openTypedUrl());
        openFileButton.setOnClickListener(v -> openFilePicker());
        savedListsButton.setOnClickListener(v -> showSavedLists());

        modeButton.setOnClickListener(v -> {
            if (videoMode == VideoMode.MITV3_HW_1080) videoMode = VideoMode.AVC_1080;
            else if (videoMode == VideoMode.AVC_1080) videoMode = VideoMode.AUTO;
            else videoMode = VideoMode.MITV3_HW_1080;
            applyVideoMode();
            updateModeLabel();
        });

        channelList.setOnItemClickListener((p, v, pos, id) -> playChannel(channels.get(pos)));

        autoLoadLastPlaylist();
    }

    private void buildPlayer() {
        DefaultRenderersFactory renderers = new DefaultRenderersFactory(this)
                .setMediaCodecSelector(HardwareCodecSelector.INSTANCE)
                .setEnableDecoderFallback(true);

        trackSelector = new DefaultTrackSelector(this);

        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(10000, 28000, 1500, 3500)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build();

        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent("SolYan-IPTV/0.2.3 MiTV3-60")
                .setConnectTimeoutMs(10000)
                .setReadTimeoutMs(15000)
                .setAllowCrossProtocolRedirects(true);

        DefaultMediaSourceFactory factory = new DefaultMediaSourceFactory(this)
                .setDataSourceFactory(http);

        player = new ExoPlayer.Builder(this, renderers)
                .setTrackSelector(trackSelector)
                .setLoadControl(loadControl)
                .setMediaSourceFactory(factory)
                .build();

        playerView.setPlayer(player);
        playerView.setUseController(true);
        playerView.setControllerAutoShow(false);
        playerView.setControllerShowTimeoutMs(2500);

        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_BUFFERING) setStatus("Đang buffer…" + decoderSuffix());
                else if (state == Player.STATE_READY) setStatus("Đang phát · " + modeText() + decoderSuffix());
                else if (state == Player.STATE_ENDED) setStatus("Stream đã kết thúc");
            }

            @Override public void onPlayerError(PlaybackException error) {
                setStatus("Lỗi phát: " + error.getErrorCodeName() + decoderSuffix());
            }
        });

        player.addAnalyticsListener(new AnalyticsListener() {
            @Override public void onVideoDecoderInitialized(
                    EventTime eventTime, String decoderName,
                    long initializedTimestampMs, long initializationDurationMs) {
                activeDecoder = decoderName == null ? "" : decoderName;
                boolean sw = HardwareCodecSelector.isSoftwareCodec(activeDecoder);
                setStatus((sw ? "⚠ SOFTWARE decoder: " : "HW decoder: ") + activeDecoder);
            }

            @Override public void onDroppedVideoFrames(EventTime eventTime, int droppedFrames, long elapsedMs) {
                droppedSinceReport += droppedFrames;
                if (droppedSinceReport >= 15) {
                    setStatus("Dropped frames: " + droppedSinceReport + " · " + activeDecoder);
                    droppedSinceReport = 0;
                }
            }
        });
    }

    private void applyVideoMode() {
        if (trackSelector == null) return;
        DefaultTrackSelector.Parameters.Builder b = trackSelector.buildUponParameters();
        b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
         .setExceedVideoConstraintsIfNecessary(true);

        if (videoMode == VideoMode.MITV3_HW_1080) {
            b.setMaxVideoSize(1920, 1080)
             .setMaxVideoFrameRate(60)
             .setMaxVideoBitrate(12000000)
             .setPreferredVideoMimeTypes(MimeTypes.VIDEO_H265, MimeTypes.VIDEO_H264);
        } else if (videoMode == VideoMode.AVC_1080) {
            b.setMaxVideoSize(1920, 1080)
             .setMaxVideoFrameRate(60)
             .setMaxVideoBitrate(10000000)
             .setPreferredVideoMimeTypes(MimeTypes.VIDEO_H264);
        } else {
            b.clearVideoSizeConstraints()
             .setMaxVideoFrameRate(Integer.MAX_VALUE)
             .setMaxVideoBitrate(Integer.MAX_VALUE)
             .setPreferredVideoMimeTypes();
        }
        trackSelector.setParameters(b);
    }

    private void updateModeLabel() {
        if (videoMode == VideoMode.MITV3_HW_1080) {
            modeButton.setText("MiTV3 HW 1080");
            setStatus("1080p ≤60fps · HEVC→AVC · ưu tiên hardware decoder");
        } else if (videoMode == VideoMode.AVC_1080) {
            modeButton.setText("AVC 1080");
            setStatus("1080p ≤60fps · H.264/AVC ưu tiên");
        } else {
            modeButton.setText("AUTO");
            setStatus("AUTO · hardware decoder vẫn ưu tiên");
        }
    }

    private String modeText() {
        if (videoMode == VideoMode.MITV3_HW_1080) return "MiTV3 HW 1080";
        if (videoMode == VideoMode.AVC_1080) return "AVC 1080";
        return "AUTO";
    }

    private String decoderSuffix() {
        return activeDecoder.isEmpty() ? "" : " · " + activeDecoder;
    }

    private void openFilePicker() {
        UsbFileBrowser.show(this, new UsbFileBrowser.Callback() {
            @Override public void onFileSelected(File file) {
                openLocalPlaylistFile(file);
            }

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
        String suggestedName = file.getName();
        String lower = suggestedName.toLowerCase();
        if (lower.endsWith(".m3u8")) suggestedName = suggestedName.substring(0, suggestedName.length() - 5);
        else if (lower.endsWith(".m3u") || lower.endsWith(".ndl")) suggestedName = suggestedName.substring(0, suggestedName.length() - 4);
        else if (lower.endsWith(".dl")) suggestedName = suggestedName.substring(0, suggestedName.length() - 3);

        final String defaultName = suggestedName.isEmpty() ? "Playlist TV" : suggestedName;
        setStatus("Đang đọc USB: " + file.getAbsolutePath());

        io.execute(() -> {
            try {
                String text = readFileText(file);
                List<Channel> parsed = M3uParser.parse(text);
                if (parsed.isEmpty()) throw new Exception("Không tìm thấy kênh trong file");
                runOnUiThread(() -> promptSavePlaylist(text, defaultName, file.getAbsolutePath(), parsed));
            } catch (Exception e) {
                runOnUiThread(() -> setStatus("Không mở được file USB: " + e.getMessage()));
            }
        });
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
        if (requestCode != REQ_OPEN_M3U || resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }

        Uri uri = data.getData();
        String suggestedName = fileDisplayName(uri);
        if (suggestedName.toLowerCase().endsWith(".m3u8")) suggestedName = suggestedName.substring(0, suggestedName.length() - 5);
        else if (suggestedName.toLowerCase().endsWith(".m3u")) suggestedName = suggestedName.substring(0, suggestedName.length() - 4);

        final String defaultName = suggestedName.isEmpty() ? "Playlist TV" : suggestedName;
        setStatus("Đang đọc file M3U…");

        io.execute(() -> {
            try {
                String text = readUriText(uri);
                List<Channel> parsed = M3uParser.parse(text);
                if (parsed.isEmpty()) throw new Exception("Không tìm thấy kênh trong file");
                runOnUiThread(() -> promptSavePlaylist(text, defaultName, "FILE", parsed));
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

    private void openTypedUrl() {
        String url = urlInput.getText().toString().trim();
        if (url.isEmpty()) {
            setStatus("Hãy nhập URL M3U/M3U8");
            return;
        }

        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_LAST_URL, url).apply();

        if (M3uParser.looksLikePlaylistUrl(url)) {
            loadPlaylistUrl(url);
        } else {
            playChannel(new Channel("Direct stream", url, "", Collections.emptyMap()));
        }
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
        input.setPadding(24, 10, 24, 10);

        new AlertDialog.Builder(this)
                .setTitle("Đặt tên playlist")
                .setMessage("List sẽ được lưu trong app. Lần sau không cần mở file/URL lại.")
                .setView(input)
                .setPositiveButton("LƯU", (dialog, which) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) name = "Playlist TV";
                    try {
                        playlistStore.save(name, text, source);
                        applyParsedPlaylist(parsed, name);
                        setStatus("Đã lưu “" + name + "” · " + parsed.size() + " kênh");
                    } catch (Exception e) {
                        setStatus("Lỗi lưu playlist: " + e.getMessage());
                    }
                })
                .setNegativeButton("CHỈ MỞ", (dialog, which) -> {
                    applyParsedPlaylist(parsed, suggestedName);
                    setStatus("Đã mở " + parsed.size() + " kênh · chưa lưu");
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
        adapter.notifyDataSetChanged();
        currentListText.setText("Playlist: " + displayName + " · " + parsed.size() + " kênh");
        if (!parsed.isEmpty()) channelList.requestFocus();
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
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "SolYan-IPTV/0.2.3 MiTV3-60");
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
        setStatus("Mở: " + ch.name + " · " + modeText());

        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent(ch.headers.containsKey("User-Agent") ? ch.headers.get("User-Agent")
                        : "SolYan-IPTV/0.2.3 MiTV3-60")
                .setConnectTimeoutMs(10000)
                .setReadTimeoutMs(15000)
                .setAllowCrossProtocolRedirects(true);

        if (!ch.headers.isEmpty()) {
            java.util.HashMap<String, String> headers = new java.util.HashMap<>(ch.headers);
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

    private void setStatus(String text) {
        runOnUiThread(() -> statusText.setText(text));
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN &&
                event.getKeyCode() == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE && player != null) {
            if (player.isPlaying()) player.pause(); else player.play();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override protected void onStop() {
        super.onStop();
        if (player != null) player.pause();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
        if (player != null) {
            player.release();
            player = null;
        }
    }
}
