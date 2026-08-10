package com.jellyfin.tvbox;

import android.app.Activity;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.view.KeyEvent;
import android.view.View;
import android.widget.MediaController;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.VideoView;

/**
 * Video player with local proxy for HTTPS compatibility and
 * quality switching (MENU key on TV remote) among three modes:
 * 流畅 (480p) → 中等 (720p) → 原画 (direct) → 流畅 → ...
 */
public class PlayerActivity extends Activity {

    private static final int QUALITY_FLUID = 0;   // 480p transcode
    private static final int QUALITY_MEDIUM = 1;  // 720p transcode
    private static final int QUALITY_ORIGINAL = 2; // direct stream

    private VideoView videoView;
    private ProgressBar bufferingBar;
    private TextView errorText;
    private TextView playerTitle;
    private TextView qualityToast;
    private TextView hintText;
    private JellyfinClient client;
    private StreamingProxy proxy;
    private Handler handler = new Handler();
    private boolean started = false;
    private int quality = QUALITY_FLUID; // 默认流畅模式
    private String itemId;
    private String title;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_player);

        videoView = (VideoView) findViewById(R.id.videoView);
        bufferingBar = (ProgressBar) findViewById(R.id.bufferingBar);
        errorText = (TextView) findViewById(R.id.errorText);
        playerTitle = (TextView) findViewById(R.id.playerTitle);
        qualityToast = (TextView) findViewById(R.id.qualityToast);
        hintText = (TextView) findViewById(R.id.hintText);

        client = AppState.getClient(this);
        itemId = getIntent().getStringExtra("itemId");
        title = getIntent().getStringExtra("title");

        if (title != null) {
            playerTitle.setText(title);
            playerTitle.setVisibility(View.VISIBLE);
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    playerTitle.setVisibility(View.GONE);
                }
            }, 4000);
        }

        if (client == null || itemId == null) {
            errorText.setText("播放参数错误");
            errorText.setVisibility(View.VISIBLE);
            return;
        }

        // Show a short hint about quality switching
        hintText.setText("默认流畅模式 · 按MENU键切换画质");
        hintText.setVisibility(View.VISIBLE);
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                hintText.setVisibility(View.GONE);
            }
        }, 6000);

        // Start proxy and play
        startProxyAndPlay();
    }

    private void startProxyAndPlay() {
        // Start local proxy for HTTPS compatibility
        proxy = new StreamingProxy(client);
        proxy.start();

        // Build local URL:
        // - Original mode: direct stream via proxy (works with MP4 files)
        // - Fluid/Medium mode: HLS playlist via proxy (transcoded TS segments)
        //   Android 4's MediaPlayer supports HLS natively, avoiding the
        //   fMP4 parse issue and the TS error 1/-1004.
        String localUrl;
        if (quality == QUALITY_ORIGINAL) {
            localUrl = proxy.getLocalUrl(itemId);
        } else {
            String q = (quality == QUALITY_FLUID) ? "low" : "medium";
            localUrl = proxy.getHlsLocalUrl(itemId, q);
        }
        startPlayback(localUrl, title);
    }

    private String qualityName(int q) {
        switch (q) {
            case QUALITY_MEDIUM: return "中等模式 (720P)";
            case QUALITY_ORIGINAL: return "原画模式";
            default: return "流畅模式 (540P)";
        }
    }

    private void toggleQuality() {
        // Cycle: 流畅 → 中等 → 原画 → 流畅 → ...
        quality++;
        if (quality > QUALITY_ORIGINAL) {
            quality = QUALITY_FLUID;
        }
        showQualityToast("已切换：" + qualityName(quality));

        // Stop current playback
        if (videoView != null) {
            try { videoView.stopPlayback(); } catch (Exception ignored) {}
        }
        if (proxy != null) {
            proxy.stop();
        }
        started = false;
        bufferingBar.setVisibility(View.VISIBLE);
        errorText.setVisibility(View.GONE);

        // Restart with new quality
        handler.post(new Runnable() {
            @Override
            public void run() {
                startProxyAndPlay();
            }
        });
    }

    private void showQualityToast(String msg) {
        qualityToast.setText(msg);
        qualityToast.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideToastRunnable);
        handler.postDelayed(hideToastRunnable, 2000);
    }

    private Runnable hideToastRunnable = new Runnable() {
        @Override
        public void run() {
            qualityToast.setVisibility(View.GONE);
        }
    };

    private void startPlayback(String url, final String title) {
        try {
            Uri uri = Uri.parse(url);
            videoView.setVideoURI(uri);

            // MediaController for play/pause/seek
            MediaController mc = new MediaController(this);
            mc.setAnchorView(videoView);
            mc.setMediaPlayer(videoView);
            videoView.setMediaController(mc);

            videoView.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer mp) {
                    bufferingBar.setVisibility(View.GONE);
                    mp.start();
                    started = true;
                }
            });

            videoView.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer mp, int what, int extra) {
                    bufferingBar.setVisibility(View.GONE);
                    errorText.setText("播放出错 (code: " + what + "/" + extra + ")");
                    errorText.setVisibility(View.VISIBLE);
                    return true;
                }
            });

            videoView.setOnInfoListener(new MediaPlayer.OnInfoListener() {
                @Override
                public boolean onInfo(MediaPlayer mp, int what, int extra) {
                    if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) {
                        bufferingBar.setVisibility(View.VISIBLE);
                    } else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END) {
                        bufferingBar.setVisibility(View.GONE);
                    }
                    return false;
                }
            });

            videoView.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer mp) {
                    finish();
                }
            });

        } catch (Exception e) {
            bufferingBar.setVisibility(View.GONE);
            errorText.setText("播放失败: " + e.getMessage());
            errorText.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // MENU key — cycle: 流畅(480P) → 中等(720P) → 原画 → 流畅 → ...
        if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_INFO) {
            toggleQuality();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
            if (videoView.isPlaying()) {
                videoView.pause();
            } else {
                videoView.start();
            }
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_MEDIA_STOP) {
            videoView.stopPlayback();
            finish();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (videoView != null && videoView.isPlaying()) {
            videoView.pause();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (videoView != null && started && !videoView.isPlaying()) {
            videoView.start();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (videoView != null) {
            videoView.stopPlayback();
        }
        if (proxy != null) {
            proxy.stop();
        }
    }
}