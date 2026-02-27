package com.kk.taurus.txplayer;

import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;

import com.kk.taurus.playerbase.config.AppContextAttach;
import com.kk.taurus.playerbase.config.PlayerConfig;
import com.kk.taurus.playerbase.config.PlayerLibrary;
import com.kk.taurus.playerbase.entity.DataSource;
import com.kk.taurus.playerbase.entity.DecoderPlan;
import com.kk.taurus.playerbase.event.BundlePool;
import com.kk.taurus.playerbase.event.EventKey;
import com.kk.taurus.playerbase.event.OnErrorEventListener;
import com.kk.taurus.playerbase.event.OnPlayerEventListener;
import com.kk.taurus.playerbase.log.PLog;
import com.kk.taurus.playerbase.player.BaseInternalPlayer;
import com.tencent.rtmp.ITXVodPlayListener;
import com.tencent.rtmp.TXLiveConstants;
import com.tencent.rtmp.TXVodPlayConfig;
import com.tencent.rtmp.TXVodPlayer;

import java.util.HashMap;

/**
 * Created by VeroZ
 */
public class TxPlayer extends BaseInternalPlayer {
    private final String TAG = "TxPlayer";

    public static final int PLAN_ID = 400;

    private final Context mAppContext;
    private TXVodPlayer mMediaPlayer;

    private int mTargetState = Integer.MAX_VALUE;

    private int startSeekPos;

    private int progressMs = 0;
    private int durationMs = 0;

    public static void init(Context context) {
        PlayerConfig.addDecoderPlan(new DecoderPlan(
                PLAN_ID,
                TxPlayer.class.getName(),
                "txplayer"));
        PlayerConfig.setDefaultPlanId(PLAN_ID);
        PlayerLibrary.init(context);
    }

    public TxPlayer() {
        // init player
        mAppContext = AppContextAttach.getApplicationContext();
        mMediaPlayer = new TXVodPlayer(mAppContext);
    }

    @Override
    public void setDataSource(DataSource data) {
        if (data != null) {
            openVideo(data);
        }
    }

    private void openVideo(DataSource dataSource) {
        try {
            if (mMediaPlayer == null) {
                mMediaPlayer = new TXVodPlayer(mAppContext);
            } else {
                stop();
                reset();
                resetListener();
            }
            mTargetState = Integer.MAX_VALUE;
            mMediaPlayer.setVodListener(mVodPlayListener);
            updateStatus(STATE_INITIALIZED);

            if (dataSource.getTimedTextSource() != null) {
                PLog.e(TAG, "txplayer not support timed text !");
            }

            // 设置为非自动播放
            mMediaPlayer.setAutoPlay(false);

            String data = dataSource.getData();
            String assetsPath = dataSource.getAssetsPath();
            HashMap<String, String> headers = dataSource.getExtra();
            if (data != null) {
                if (headers != null) {
                    setPlayerHeaders(headers);
                }
                // setAutoPlay 设置为 false，不会立刻开始播放，只会开始加载视频
                mMediaPlayer.startPlay(data);
            } else if (!TextUtils.isEmpty(assetsPath)) {
                Log.e(TAG, "txplayer not support assets play, you can use raw play.");
            }

            //set looping indicator for TxPlayer
            mMediaPlayer.setLoop(isLooping());

            Bundle bundle = BundlePool.obtain();
            bundle.putSerializable(EventKey.SERIALIZABLE_DATA, dataSource);
            submitPlayerEvent(OnPlayerEventListener.PLAYER_EVENT_ON_DATA_SOURCE_SET, bundle);
        } catch (Exception e) {
            e.printStackTrace();
            updateStatus(STATE_ERROR);
            mTargetState = STATE_ERROR;
            submitErrorEvent(OnErrorEventListener.ERROR_EVENT_IO, null);
        }
    }

    private void setPlayerHeaders(HashMap<String, String> headers) {
        TXVodPlayConfig txVodPlayConfig = new TXVodPlayConfig();
        txVodPlayConfig.setHeaders(headers);
        mMediaPlayer.setConfig(txVodPlayConfig);
    }

    private boolean available() {
        return mMediaPlayer != null;
    }

    @Override
    public void start() {
        if (available() && (getState() == STATE_PREPARED
                || getState() == STATE_PAUSED
                || getState() == STATE_PLAYBACK_COMPLETE)) {
            mMediaPlayer.resume();
            updateStatus(STATE_STARTED);
            submitPlayerEvent(OnPlayerEventListener.PLAYER_EVENT_ON_START, null);
        }
        mTargetState = STATE_STARTED;
        PLog.d(TAG, "start...");
    }

    @Override
    public void start(int msc) {
        if (getState() == STATE_PREPARED && msc > 0) {
            start();
            seekTo(msc);
        } else {
            if (msc > 0) {
                startSeekPos = msc;
            }
            if (available()) {
                start();
            }
        }
    }

    @Override
    public void pause() {
        try {
            int state = getState();
            if (available()
                    && state != STATE_END
                    && state != STATE_ERROR
                    && state != STATE_IDLE
                    && state != STATE_INITIALIZED
                    && state != STATE_PAUSED
                    && state != STATE_STOPPED) {
                mMediaPlayer.pause();
                updateStatus(STATE_PAUSED);
                submitPlayerEvent(OnPlayerEventListener.PLAYER_EVENT_ON_PAUSE, null);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        mTargetState = STATE_PAUSED;
    }

    @Override
    public void resume() {
        try {
            if (available() && getState() == STATE_PAUSED) {
                mMediaPlayer.resume();
                updateStatus(STATE_STARTED);
                submitPlayerEvent(OnPlayerEventListener.PLAYER_EVENT_ON_RESUME, null);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        mTargetState = STATE_STARTED;
    }

    @Override
    public void seekTo(int msc) {
        if (available() &&
                (getState() == STATE_PREPARED
                        || getState() == STATE_STARTED
                        || getState() == STATE_PAUSED
                        || getState() == STATE_PLAYBACK_COMPLETE)) {
            float time = msc / 1000.0f;
            mMediaPlayer.seek(time);
            Bundle bundle = BundlePool.obtain();
            bundle.putInt(EventKey.INT_DATA, msc);
            submitPlayerEvent(OnPlayerEventListener.PLAYER_EVENT_ON_SEEK_TO, bundle);
        } else {
            // seekTo may not work during preparation, call it after prepared
            if (msc > 0) {
                startSeekPos = msc;
            }
        }
    }

    @Override
    public void stop() {
        if (available() &&
                (getState() == STATE_PREPARED
                        || getState() == STATE_STARTED
                        || getState() == STATE_PAUSED
                        || getState() == STATE_PLAYBACK_COMPLETE)) {
            mMediaPlayer.stopPlay(true);
            updateStatus(STATE_STOPPED);
            submitPlayerEvent(OnPlayerEventListener.PLAYER_EVENT_ON_STOP, null);
        }
        mTargetState = STATE_STOPPED;
    }

    @Override
    public void reset() {
        if (available()) {
            stop();
            updateStatus(STATE_IDLE);
            submitPlayerEvent(OnPlayerEventListener.PLAYER_EVENT_ON_RESET, null);
        }
        mTargetState = STATE_IDLE;
    }

    @Override
    public boolean isPlaying() {
        if (available() && getState() != STATE_ERROR) {
            return mMediaPlayer.isPlaying();
        }
        return false;
    }

    @Override
    public int getCurrentPosition() {
        if (available() && (getState() == STATE_PREPARED
                || getState() == STATE_STARTED
                || getState() == STATE_PAUSED
                || getState() == STATE_PLAYBACK_COMPLETE)) {
            return (int) progressMs;
        }
        return 0;
    }

    @Override
    public int getDuration() {
        if (available()
                && getState() != STATE_ERROR
                && getState() != STATE_INITIALIZED
                && getState() != STATE_IDLE) {
            return durationMs;
        }
        return 0;
    }

    @Override
    public int getVideoWidth() {
        if (available()) {
            return mMediaPlayer.getWidth();
        }
        return 0;
    }

    @Override
    public int getVideoHeight() {
        if (available()) {
            return mMediaPlayer.getHeight();
        }
        return 0;
    }

    @Override
    public void destroy() {
        if (available()) {
            stop();
            updateStatus(STATE_END);
            resetListener();
            submitPlayerEvent(OnPlayerEventListener.PLAYER_EVENT_ON_DESTROY, null);
        }
        progressMs = 0;
    }

    @Override
    public void setDisplay(SurfaceHolder surfaceHolder) {
        try {
            if (available()) {
                mMediaPlayer.setSurface(surfaceHolder.getSurface());
                submitPlayerEvent(OnPlayerEventListener.PLAYER_EVENT_ON_SURFACE_HOLDER_UPDATE, null);
            }
        } catch (Exception e) {
            Bundle bundle = BundlePool.obtain();
            bundle.putString("errorMessage", e.getMessage());
            bundle.putString("causeMessage", e.getCause() != null ? e.getCause().getMessage() : "");
            submitErrorEvent(OnErrorEventListener.ERROR_EVENT_RENDER, bundle);
        }
    }

    @Override
    public void setSurface(Surface surface) {
        try {
            if (available()) {
                mMediaPlayer.setSurface(surface);
                submitPlayerEvent(OnPlayerEventListener.PLAYER_EVENT_ON_SURFACE_UPDATE, null);
            }
        } catch (Exception e) {
            Bundle bundle = BundlePool.obtain();
            bundle.putString("errorMessage", e.getMessage());
            bundle.putString("causeMessage", e.getCause() != null ? e.getCause().getMessage() : "");
            submitErrorEvent(OnErrorEventListener.ERROR_EVENT_RENDER, bundle);
        }
    }

    @Override
    public void setVolume(float leftVolume, float rightVolume) {
        if (available()) {
            mMediaPlayer.setAudioPlayoutVolume((int) (((leftVolume * 100) + (rightVolume * 100)) / 2));
        }
    }

    @Override
    public void setSpeed(float speed) {
        if (available()) {
            mMediaPlayer.setRate(speed);
        }
    }

    @Override
    public void setLooping(boolean looping) {
        super.setLooping(looping);
        mMediaPlayer.setLoop(looping);
    }

    @Override
    public int getAudioSessionId() {
        return 0;
    }

    private void resetListener() {
        if (mMediaPlayer == null)
            return;
        mMediaPlayer.setVodListener(null);
    }

    private int mVideoWidth;
    private int mVideoHeight;

    ITXVodPlayListener mVodPlayListener = new ITXVodPlayListener() {
        @Override
        public void onPlayEvent(TXVodPlayer txVodPlayer, int event, Bundle param) {
            if (event != TXLiveConstants.PLAY_EVT_PLAY_PROGRESS) {
                String playEventLog = "receive event: " + event + ", " + param.getString(TXLiveConstants.EVT_DESCRIPTION);
                Log.d(TAG, playEventLog);
            }

            // 处理播放事件
            switch (event) {
                case TXLiveConstants.PLAY_EVT_VOD_PLAY_PREPARED: // 准备完成事件
                    PLog.d(TAG, "onPrepared...");
                    updateStatus(STATE_PREPARED);

                    mVideoWidth = mMediaPlayer.getWidth();
                    mVideoHeight = mMediaPlayer.getHeight();

                    Bundle bundleResolution = BundlePool.obtain();
                    bundleResolution.putInt(EventKey.INT_ARG1, mVideoWidth);
                    bundleResolution.putInt(EventKey.INT_ARG2, mVideoHeight);

                    submitPlayerEvent(OnPlayerEventListener.PLAYER_EVENT_ON_PREPARED, bundleResolution);

                    int seekToPosition = startSeekPos / 1000;  // mSeekWhenPrepared may be changed after seekTo() call
                    if (seekToPosition > 0 && mMediaPlayer.getDuration() > 0) {
                        mMediaPlayer.seek(seekToPosition);
                        startSeekPos = 0;
                    }

                    // We don't know the video size yet, but should start anyway.
                    // The video size might be reported to us later.
                    PLog.d(TAG, "mTargetState = " + mTargetState);
                    if (mTargetState == STATE_STARTED) {
                        start();
                    } else if (mTargetState == STATE_PAUSED) {
                        pause();
                    } else if (mTargetState == STATE_STOPPED || mTargetState == STATE_IDLE) {
                        reset();
                    }
                    break;

                case TXLiveConstants.PLAY_EVT_PLAY_BEGIN: // 播放开始事件
                    PLog.d(TAG, "onRenderingStart...");
                    startSeekPos = 0;
                    submitPlayerEvent(OnPlayerEventListener.PLAYER_EVENT_ON_VIDEO_RENDER_START, null);
                    break;

                case TXLiveConstants.PLAY_EVT_PLAY_PROGRESS: // 播放进度事件
                    int progress = param.getInt(TXLiveConstants.EVT_PLAY_PROGRESS_MS);
                    int duration = param.getInt(TXLiveConstants.EVT_PLAY_DURATION_MS);

                    progressMs = progress;
                    durationMs = duration;

                    float bufferedPosition = mMediaPlayer.getBufferDuration();
                    submitBufferingUpdate((int) (bufferedPosition * 1000f / durationMs * 100f), null);
                    break;

                case TXLiveConstants.PLAY_EVT_PLAY_END: // 播放结束事件
                    updateStatus(STATE_PLAYBACK_COMPLETE);
                    mTargetState = STATE_PLAYBACK_COMPLETE;
                    submitPlayerEvent(OnPlayerEventListener.PLAYER_EVENT_ON_PLAY_COMPLETE, null);
                    if (!isLooping()) {
                        stop();
                    }
                    break;

                case TXLiveConstants.PLAY_EVT_PLAY_LOADING: // 开始缓冲
                    submitPlayerEvent(OnPlayerEventListener.PLAYER_EVENT_ON_BUFFERING_START, null);
                    break;

                case TXLiveConstants.PLAY_EVT_VOD_LOADING_END: // 缓冲结束
                    submitPlayerEvent(OnPlayerEventListener.PLAYER_EVENT_ON_BUFFERING_END, null);
                    break;

                case /*TXLiveConstants.PLAY_EVT_SEEK_COMPLETE*/ 2019: // 寻道完成事件
                    submitPlayerEvent(OnPlayerEventListener.PLAYER_EVENT_ON_SEEK_COMPLETE, null);
                    break;

                case TXLiveConstants.PLAY_EVT_RCV_FIRST_I_FRAME: // 收到首帧
                    break;

                case TXLiveConstants.PLAY_EVT_RCV_FIRST_AUDIO_FRAME: // 音频首帧渲染开始
                    submitPlayerEvent(OnPlayerEventListener.PLAYER_EVENT_ON_AUDIO_RENDER_START, null);
                    break;

                case TXLiveConstants.PLAY_EVT_CHANGE_RESOLUTION: // 分辨率改变
                    int width = param.getInt(TXLiveConstants.EVT_PARAM1, mVideoWidth);
                    int height = param.getInt(TXLiveConstants.EVT_PARAM2, mVideoHeight);

                    if (width != mVideoWidth || height != mVideoHeight) {
                        mVideoWidth = width;
                        mVideoHeight = height;

                        Bundle bundle = BundlePool.obtain();
                        bundle.putInt(EventKey.INT_ARG1, mVideoWidth);
                        bundle.putInt(EventKey.INT_ARG2, mVideoHeight);
                        submitPlayerEvent(OnPlayerEventListener.PLAYER_EVENT_ON_VIDEO_SIZE_CHANGE, bundle);
                    }
                    break;

                case TXLiveConstants.PLAY_EVT_CHANGE_ROTATION: // 视频旋转改变
                    // 提交视频旋转改变事件
                    int rotation = param.getInt(TXLiveConstants.EVT_PARAM1, 0);
                    Bundle bundle = BundlePool.obtain();
                    bundle.putInt(EventKey.INT_DATA, rotation);
                    submitPlayerEvent(OnPlayerEventListener.PLAYER_EVENT_ON_VIDEO_ROTATION_CHANGED, bundle);
                    break;

                case TXLiveConstants.PLAY_EVT_START_VIDEO_DECODER: // 开始视频解码
                    submitPlayerEvent(OnPlayerEventListener.PLAYER_EVENT_ON_VIDEO_RENDER_START, null);
                    break;

                case TXLiveConstants.PLAY_EVT_GET_PLAYINFO_SUCC: // 获取播放信息成功
                    // 播放信息获取成功，可以在这里处理相关逻辑
                    PLog.d(TAG, "Play info retrieved successfully");
                    break;

                case TXLiveConstants.PLAY_EVT_STREAM_SWITCH_SUCC: // 流切换成功
                    // 流切换成功事件，可以在这里处理相关逻辑
                    PLog.d(TAG, "Stream switch successful");
                    break;

                case TXLiveConstants.PLAY_EVT_GET_METADATA: // 获取元数据
                    PLog.d(TAG, "Metadata retrieved");
                    break;

                default:
                    PLog.d(TAG, "Unhandled play event: " + event);
                    break;
            }

            // 处理错误事件
            switch (event) {
                case TXLiveConstants.PLAY_ERR_NET_DISCONNECT: // 网络断开
                    submitErrorEvent(OnErrorEventListener.ERROR_EVENT_IO, null);
                    break;

                case TXLiveConstants.PLAY_ERR_FILE_NOT_FOUND: // 文件未找到
                    submitErrorEvent(OnErrorEventListener.ERROR_EVENT_IO, null);
                    break;

                case TXLiveConstants.PLAY_ERR_HLS_KEY: // HLS密钥错误
                    submitErrorEvent(OnErrorEventListener.ERROR_EVENT_MALFORMED, null);
                    break;

                case TXLiveConstants.PLAY_ERR_GET_RTMP_ACC_URL_FAIL: // 获取RTMP地址失败
                    submitErrorEvent(OnErrorEventListener.ERROR_EVENT_REMOTE, null);
                    break;

                case TXLiveConstants.PLAY_ERR_HEVC_DECODE_FAIL: // HEVC解码失败
                    submitErrorEvent(OnErrorEventListener.ERROR_EVENT_UNSUPPORTED, null);
                    break;

                case TXLiveConstants.PLAY_ERR_GET_PLAYINFO_FAIL: // 获取播放信息失败
                    submitErrorEvent(OnErrorEventListener.ERROR_EVENT_IO, null);
                    break;

                case TXLiveConstants.PLAY_ERR_STREAM_SWITCH_FAIL: // 流切换失败
                    submitErrorEvent(OnErrorEventListener.ERROR_EVENT_REMOTE, null);
                    break;

                default:
                    // 其他未分类错误
                    submitErrorEvent(OnErrorEventListener.ERROR_EVENT_COMMON, null);
                    break;
            }

            // 处理警告事件
            switch (event) {
                case TXLiveConstants.PLAY_WARNING_RECONNECT:
                    // 网络重连警告
                    PLog.w(TAG, "Network reconnect warning");
                    break;

                case TXLiveConstants.PLAY_WARNING_HW_ACCELERATION_FAIL:
                    // 硬件加速失败警告
                    PLog.w(TAG, "Hardware acceleration failed");
                    break;
            }
        }

        @Override
        public void onNetStatus(TXVodPlayer txVodPlayer, Bundle bundle) {

        }
    };
}
