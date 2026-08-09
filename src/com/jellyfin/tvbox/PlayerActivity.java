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
 * quality switching (INFO key on TV remote) for slow networks.
 */
public class PlayerActivity extends Activity {

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
    private boolean lowQuality = true; // 默认流畅模式，可按MENU键切原画
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
        hintText.setText("默认流畅模式 · 按MENU键切换原画");
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

        // Build local URL; add ?q=low for transcoded stream
        String localUrl = proxy.getLocalUrl(itemId);
        if (lowQuality) {
            localUrl = localUrl + "?q=low";
        }
        startPlayback(localUrl, title);
    }

    private void toggleQuality() {
        lowQuality = !lowQuality;
        showQualityToast(lowQuality ? "已切换：流畅模式" : "已切换：原画模式");

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
        // MENU key — toggle between 流畅/原画. INFO key (i) kept as fallback.
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