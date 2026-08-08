package com.jellyfin.tvbox;

import android.app.Activity;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.MediaController;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.VideoView;

/**
 * Video player with local proxy for HTTPS compatibility and
 * quality switching for slow networks.
 */
public class PlayerActivity extends Activity {

    private VideoView videoView;
    private ProgressBar bufferingBar;
    private TextView errorText;
    private TextView playerTitle;
    private Button qualityButton;
    private JellyfinClient client;
    private StreamingProxy proxy;
    private Handler handler = new Handler();
    private boolean started = false;
    private boolean lowQuality = false; // false = 原画, true = 流畅
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
        qualityButton = (Button) findViewById(R.id.qualityButton);

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
            }, 5000);
        }

        if (client == null || itemId == null) {
            errorText.setText("播放参数错误");
            errorText.setVisibility(View.VISIBLE);
            return;
        }

        // Quality button: toggle between 原画 and 流畅
        qualityButton.setVisibility(View.VISIBLE);
        qualityButton.setText("原画");
        qualityButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleQuality();
            }
        });

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
        qualityButton.setText(lowQuality ? "流畅" : "原画");

        // Stop current playback
        if (videoView != null) {
            videoView.stopPlayback();
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