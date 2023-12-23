package com.cclive.cc;

import android.annotation.SuppressLint;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.fragment.app.FragmentTransaction;

import com.hjq.permissions.OnPermissionCallback;
import com.hjq.permissions.Permission;
import com.hjq.permissions.XXPermissions;
import com.nostra13.universalimageloader.cache.disc.naming.Md5FileNameGenerator;
import com.nostra13.universalimageloader.core.ImageLoader;
import com.nostra13.universalimageloader.core.ImageLoaderConfiguration;
import com.nostra13.universalimageloader.core.assist.QueueProcessingType;

import com.cclive.cc.utils.StatusBarUtil;
import org.dync.ijkplayerlib.widget.media.AndroidMediaController;
import org.dync.ijkplayerlib.widget.media.IRenderView;
import org.dync.ijkplayerlib.widget.media.IjkVideoView;
import org.dync.ijkplayerlib.widget.util.PlayerController;
import org.dync.ijkplayerlib.widget.util.Settings;
import org.dync.ijkplayerlib.widget.util.Utils;
import org.dync.ijkplayerlib.widget.util.WindowManagerUtil;
import org.dync.subtitleconverter.SubtitleView;

import java.util.List;
import java.util.Locale;

import butterknife.BindView;
import butterknife.ButterKnife;
import tv.danmaku.ijk.media.player.IMediaPlayer;
import tv.danmaku.ijk.media.player.IjkMediaPlayer;

import static org.dync.ijkplayerlib.widget.util.PlayerController.formatedDurationMilli;
import static org.dync.ijkplayerlib.widget.util.PlayerController.formatedSize;
import static org.dync.ijkplayerlib.widget.util.PlayerController.formatedSpeed;

public class VideoActivity extends BaseActivity implements IMediaPlayer.OnRecordListener {
    private static final String TAG = "ijkJava";
    private String mVideoPath;
    private Uri mVideoUri;

    private AndroidMediaController mMediaController;
    private PlayerController mPlayerController;

    private boolean mBackPressed;
    private String mVideoCoverUrl;


    @BindView(R.id.video_view)
    IjkVideoView mVideoView;
    @BindView(R.id.subtitleView)
    SubtitleView subtitleView;
    @BindView(R.id.video_cover)
    ImageView videoCover;
    @BindView(R.id.app_video_replay_text)
    TextView appVideoReplayText;
    @BindView(R.id.app_video_replay_icon)
    ImageView appVideoReplayIcon;
    @BindView(R.id.app_video_replay_btn)
    LinearLayout appVideoReplay;
    @BindView(R.id.app_video_status_text)
    TextView appVideoStatusText;
    @BindView(R.id.app_video_retry_icon)
    ImageView appVideoRetryIcon;
    @BindView(R.id.app_video_retry)
    LinearLayout appVideoRetry;
    @BindView(R.id.app_video_netTie_icon)
    TextView appVideoNetTieIcon;
    @BindView(R.id.app_video_netTie)
    LinearLayout appVideoNetTie;
    @BindView(R.id.app_video_freeTie_icon)
    TextView appVideoFreeTieIcon;
    @BindView(R.id.app_video_freeTie)
    LinearLayout appVideoFreeTie;
    @BindView(R.id.app_video_speed)
    TextView appVideoSpeed;
    @BindView(R.id.app_video_loading)
    LinearLayout appVideoLoading;
    @BindView(R.id.app_video_volume_icon)
    ImageView appVideoVolumeIcon;
    @BindView(R.id.app_video_volume)
    TextView appVideoVolume;
    @BindView(R.id.app_video_volume_box)
    LinearLayout appVideoVolumeBox;
    @BindView(R.id.app_video_brightness_icon)
    ImageView appVideoBrightnessIcon;
    @BindView(R.id.app_video_brightness)
    TextView appVideoBrightness;
    @BindView(R.id.app_video_brightness_box)
    LinearLayout appVideoBrightnessBox;
    @BindView(R.id.app_video_fastForward)
    TextView appVideoFastForward;
    @BindView(R.id.app_video_fastForward_target)
    TextView appVideoFastForwardTarget;
    @BindView(R.id.app_video_fastForward_all)
    TextView appVideoFastForwardAll;
    @BindView(R.id.app_video_fastForward_box)
    LinearLayout appVideoFastForwardBox;
    @BindView(R.id.app_video_center_box)
    FrameLayout appVideoCenterBox;
    @BindView(R.id.play_icon)
    ImageView playIcon;
    @BindView(R.id.tv_current_time)
    TextView tvCurrentTime;
    @BindView(R.id.seekbar)
    SeekBar seekbar;
    @BindView(R.id.tv_total_time)
    TextView tvTotalTime;
    @BindView(R.id.img_change_screen)
    ImageView imgChangeScreen;
    @BindView(R.id.ll_bottom)
    LinearLayout llBottom;
    @BindView(R.id.bottom_progress)
    ProgressBar bottomProgress;
    @BindView(R.id.rl_video_view_layout)
    RelativeLayout rlVideoViewLayout;
    @BindView(R.id.btn_start_record)
    Button btnStartRecord;
//  @BindView(R.id.btn_stop_record)
//  Button btnStopRecord;
    @BindView(R.id.btn_snapshot)
    Button btnSnapshot;
//  @BindView(R.id.btn_exo_player)
//  Button btnExoPlayer;
    @BindView(R.id.sp_speed)
    Spinner spSpeed;
    @BindView(R.id.btn_window_player)
    Button btnWindowPlayer;
    @BindView(R.id.btn_app_player)
    Button btnAppPlayer;
    @BindView(R.id.horizontalScrollView)
    HorizontalScrollView horizontalScrollView;
    @BindView(R.id.fps)
    TextView fps;
    @BindView(R.id.v_cache)
    TextView vCache;
    @BindView(R.id.a_cache)
    TextView aCache;
    @BindView(R.id.seek_load_cost)
    TextView seekLoadCost;
    @BindView(R.id.tcp_speed)
    TextView tcpSpeed;
    @BindView(R.id.bit_rate)
    TextView bitRate;
    @BindView(R.id.iv_preview)
    ImageView ivPreview;
    @BindView(R.id.ll_video_info)
    LinearLayout llVideoInfo;
    @BindView(R.id.fl_clip_list)
    FrameLayout flClipList;
    @BindView(R.id.app_video_box)
    RelativeLayout appVideoBox;

    public static Intent newIntent(Context context, String videoPath, String videoTitle) {
        Intent intent = new Intent(context, VideoActivity.class);
        intent.putExtra("videoPath", videoPath);
        intent.putExtra("videoTitle", videoTitle);
        return intent;
    }

    public static void intentTo(Context context, String videoPath, String videoTitle) {
        context.startActivity(newIntent(context, videoPath, videoTitle));
    }

    @SuppressWarnings("deprecation")
    @SuppressLint({"ObsoleteSdkInt", "SourceLockedOrientationActivity"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_video); // >> IjkVideoView.IjkVideoView(context, attrs)
        ButterKnife.bind(this);

        mContext = this;

        //mVideoPath = getIntent().getStringExtra("videoPath");
        mVideoPath = getDefaultUri();

        Intent intent = getIntent();
        String intentAction = intent.getAction();
        if (!TextUtils.isEmpty(intentAction)) {
            assert intentAction != null;
            if (intentAction.equals(Intent.ACTION_VIEW)) {
                mVideoPath = intent.getDataString();
            } else if (intentAction.equals(Intent.ACTION_SEND)) {
                mVideoUri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
                    String scheme = mVideoUri.getScheme();
                    if (TextUtils.isEmpty(scheme)) {
                        Log.e(TAG, "Null unknown scheme");
                        finish();
                        return;
                    }
                    assert scheme != null;
                    if (scheme.equals(ContentResolver.SCHEME_ANDROID_RESOURCE)) {
                        mVideoPath = mVideoUri.getPath();
                    } else if (scheme.equals(ContentResolver.SCHEME_CONTENT)) {
                        Log.e(TAG, "Can not resolve content below Android-ICS");
                        finish();
                        return;
                    } else {
                        Log.e(TAG, "Unknown scheme " + scheme);
                        finish();
                        return;
                    }
                }
            }
        }

        initVideoControl();
        initPlayer();
        initVideoListener();
        //initFragment();
        initClipGridFragment();
        initListener();
        StatusBarUtil.setColor(this, getResources().getColor(R.color.colorPrimary));

        if (getResources().getConfiguration().orientation != Configuration.ORIENTATION_PORTRAIT) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        }

        initImageLoader(this); // getApplicationContext());
    }

    public static void initImageLoader(Context context) {
        // This configuration tuning is custom. You can tune every option, you may tune some of them,
        // or you can create default configuration via ImageLoaderConfiguration.createDefault(this)
        ImageLoaderConfiguration.Builder config = new ImageLoaderConfiguration.Builder(context);
        config.threadPriority(Thread.NORM_PRIORITY - 2);
        config.denyCacheImageMultipleSizesInMemory();
        config.diskCacheFileNameGenerator(new Md5FileNameGenerator());
        config.diskCacheSize(50 * 1024 * 1024); // 50 MB
        config.tasksProcessingOrder(QueueProcessingType.LIFO);
        //config.writeDebugLogs(); // Remove for release version

        // Initialize ImageLoader with configuration.
        ImageLoader.getInstance().init(config.build());
    }

    @Override
    public void onFinished() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (mBtnStartRecord != null) {
                    mBtnStartRecord.setEnabled(true);
                }
            }
        });
    }

    private View mBtnStartRecord = null;

    @Override
    public void onProgress(float percentage) {
        // TODO: 将 recording progress percentage 在某个 UI 元素上体现出来
    }

    @SuppressLint("NonConstantResourceId")
    public void onClick(View view) {
        switch (view.getId()) {
            /*
            case R.id.btn_ijk_player:
                mPlayerController.switchPlayer(Settings.PV_PLAYER__IjkMediaPlayer);
                break;
            case R.id.btn_exo_player:
                mPlayerController.switchPlayer(Settings.PV_PLAYER__IjkExoMediaPlayer);
                break;
            case R.id.btn_stop_record:
                //mPlayerController.setPlayerRotation(90);
                mPlayerController.stopRecord();
                break;
            */
            case R.id.btn_start_record:
                //mPlayerController.toggleAspectRatio();
                mPlayerController.startRecord(this, 3, false);
                if (mBtnStartRecord == null) {
                    mBtnStartRecord = findViewById(R.id.btn_start_record);
                }
                mBtnStartRecord.setEnabled(false);
                break;
            case R.id.btn_snapshot:
                mPlayerController.snapshot(null);
                break;
            case R.id.btn_window_player:
                XXPermissions.with(this).permission(Permission.SYSTEM_ALERT_WINDOW)
                                        .request( getOnPermissionCallback() );
                break;
            case R.id.btn_app_player:
                WindowManagerUtil.createSmallApp(mActivity, mVideoView.getMediaPlayer());
                mVideoView.setRenderView(null);
                WindowManagerUtil.setAppCallBack(new WindowManagerUtil.AppCallBack() {
                    @Override
                    public void removeSmallApp(IMediaPlayer mediaPlayer) {
                        WindowManagerUtil.removeSmallApp(mActivity);
                        //mVideoView.setMediaPlayer(mediaPlayer);
                        mVideoView.resetRenders();
                    }
                });
                break;
        }
    }

    private OnPermissionCallback getOnPermissionCallback() {
        return new OnPermissionCallback() {
            @Override
            public void onGranted(@NonNull List<String> permissions, boolean all) {
                WindowManagerUtil.createSmallWindow(mContext, mVideoView.getMediaPlayer());
                mVideoView.setRenderView(null);
                WindowManagerUtil.setWindowCallBack(new WindowManagerUtil.WindowCallBack() {
                    @Override
                    public void removeSmallWindow(IMediaPlayer mediaPlayer) {
                        WindowManagerUtil.removeSmallWindow(mContext);
                        //mVideoView.setMediaPlayer(mediaPlayer);
                        mVideoView.resetRenders();
                    }
                });
            }

            @Override
            public void onDenied(@NonNull List<String> permissions, boolean never) {
                //Toast.makeText(mContext, "需要取得权限以使用悬浮窗", Toast.LENGTH_SHORT).show();
                XXPermissions.startPermissionActivity(VideoActivity.this, permissions);
            }
        };
    }

    private void initVideoListener() {
        mVideoView.setOnPreparedListener(new IMediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(IMediaPlayer iMediaPlayer) {
                appVideoReplay.setVisibility(View.GONE);
                appVideoRetry.setVisibility(View.GONE);
                hideVideoLoading();
                // exoPlayer 如果是直播流返回1
                seekbar.setEnabled(mVideoView.getDuration() > 1);
                playIcon.setEnabled(true);

                if (Utils.isWifiConnected(mActivity)
                        || mPlayerController.isLocalDataSource(mVideoUri)
                        || PlayerController.WIFI_TIP_DIALOG_SHOWED)
                {
                    updatePlayBtnBg(false);
                }
                // else {
                //     mPlayerController.showWifiDialog();
                // }

                mVideoView.startVideoInfo();
                if (mVideoView.hasVideoTrackInfo()) {
                    videoCover.setImageDrawable(new ColorDrawable(0));
                }
                // else {
                //     if (!TextUtils.isEmpty(mVideoCoverUrl)) {
                //         GlideUtil.showImg(mContext, mVideoCoverUrl, videoCover);
                //     }
                // }

                mPlayerController
                        .setGestureEnabled(true)
                        .setAutoControlPanel(true); // 视频加载后才自动隐藏操作面板
                mPlayerController.setSpeed(1.0f);
            }
        });
        mVideoView.setVideoInfoListener(new IjkVideoView.VideoInfoListener() {
            @Override
            public void updateVideoInfo(IMediaPlayer mMediaPlayer) {
                showVideoInfo(mMediaPlayer);
            }
        });
        mVideoView.setOnCompletionListener(new IMediaPlayer.OnCompletionListener() {
            @Override
            public void onCompletion(IMediaPlayer iMediaPlayer) {
                mVideoView.release(false);
                mVideoView.stopVideoInfo();
                appVideoReplay.setVisibility(View.VISIBLE);
                appVideoRetry.setVisibility(View.GONE);
                playIcon.setEnabled(false);
                initVideoControl();
                updatePlayBtnBgState(true);
                WindowManagerUtil.removeSmallWindow(mContext);
                WindowManagerUtil.removeSmallApp(mActivity);
            }
        });
        mVideoView.setOnErrorListener(new IMediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(IMediaPlayer iMediaPlayer, int framework_err, int impl_err) {
                hideVideoLoading();

                if (mPlayerController != null) {
                    mPlayerController.setGestureEnabled(false).setAutoControlPanel(false);
                }
                mVideoView.stopVideoInfo();
                appVideoReplay.setVisibility(View.GONE);
                appVideoRetry.setVisibility(View.VISIBLE);
                playIcon.setEnabled(false);
                initVideoControl();
                updatePlayBtnBgState(true);
                WindowManagerUtil.removeSmallWindow(mContext);
                WindowManagerUtil.removeSmallApp(mActivity);
                return true;
            }
        });
        mVideoView.setOnInfoListener(new IMediaPlayer.OnInfoListener() {
            @Override
            public boolean onInfo(IMediaPlayer iMediaPlayer, int what, int extra) {
                Log.d(TAG, "onInfo: what= " + what + ", extra= " + extra);
                switch (what) {
                    case IMediaPlayer.MEDIA_INFO_STARTED_AS_NEXT: // 播放下一条
                        Log.d(TAG, "MEDIA_INFO_STARTED_AS_NEXT:");
                        break;
                    case IMediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START: // 视频开始整备中
                        Log.d(TAG, "MEDIA_INFO_VIDEO_RENDERING_START:");
                        break;
                    case IMediaPlayer.MEDIA_INFO_AUDIO_RENDERING_START: // 音频开始整备中
                        Log.d(TAG, "MEDIA_INFO_AUDIO_RENDERING_START:");
                        break;
                    case IMediaPlayer.MEDIA_INFO_COMPONENT_OPEN:
                        break;
                    case IMediaPlayer.MEDIA_INFO_BUFFERING_START: // 视频缓冲开始
                        Log.d(TAG, "MEDIA_INFO_BUFFERING_START:");
                        showVideoLoading();
                        break;
                    case IMediaPlayer.MEDIA_INFO_BUFFERING_END: // 视频缓冲结束
                        Log.d(TAG, "MEDIA_INFO_BUFFERING_END:");
                        hideVideoLoading();
                        break;
                    case IMediaPlayer.MEDIA_INFO_VIDEO_TRACK_LAGGING: // 视频日志跟踪
                        Log.d(TAG, "MEDIA_INFO_VIDEO_TRACK_LAGGING:");
                        break;
                    case IMediaPlayer.MEDIA_INFO_NOT_SEEKABLE: // 不可设置播放位置，直播方面
                        Log.d(TAG, "MEDIA_INFO_NOT_SEEKABLE:");
                        break;
                    /*
                    case IMediaPlayer.MEDIA_INFO_NETWORK_BANDWIDTH: // 网络带宽
                        Log.d(TAG, "MEDIA_INFO_NETWORK_BANDWIDTH: " + extra);
                        break;
                    case IMediaPlayer.MEDIA_INFO_BAD_INTERLEAVING:
                        Log.d(TAG, "MEDIA_INFO_BAD_INTERLEAVING:");
                        break;
                    case IMediaPlayer.MEDIA_INFO_METADATA_UPDATE: // 视频数据更新
                        Log.d(TAG, "MEDIA_INFO_METADATA_UPDATE: " + extra);
                        break;
                    case IMediaPlayer.MEDIA_INFO_TIMED_TEXT_ERROR:
                        Log.d(TAG, "MEDIA_INFO_TIMED_TEXT_ERROR:");
                        break;
                    case IMediaPlayer.MEDIA_INFO_UNSUPPORTED_SUBTITLE: // 不支持字幕
                        Log.d(TAG, "MEDIA_INFO_UNSUPPORTED_SUBTITLE:");
                        break;
                    case IMediaPlayer.MEDIA_INFO_SUBTITLE_TIMED_OUT: // 字幕超时
                        Log.d(TAG, "MEDIA_INFO_SUBTITLE_TIMED_OUT:");
                        break;
                    case IMediaPlayer.MEDIA_INFO_VIDEO_ROTATION_CHANGED:
                        Log.d(TAG, "MEDIA_INFO_VIDEO_ROTATION_CHANGED:");
                        break;
                    case IMediaPlayer.MEDIA_INFO_MEDIA_ACCURATE_SEEK_COMPLETE:
                        Log.d(TAG, "MEDIA_INFO_MEDIA_ACCURATE_SEEK_COMPLETE:");
                        break;
                    case IMediaPlayer.MEDIA_INFO_UNKNOWN: // 未知信息
                        Log.d(TAG, "MEDIA_INFO_UNKNOWN or MEDIA_ERROR_UNKNOWN:");
                        break;
                    case IMediaPlayer.MEDIA_ERROR_SERVER_DIED: // 服务挂掉
                        Log.d(TAG, "MEDIA_ERROR_SERVER_DIED:");
                        break;
                    case IMediaPlayer.MEDIA_ERROR_NOT_VALID_FOR_PROGRESSIVE_PLAYBACK: // 数据错误没有有效的回收
                        Log.d(TAG, "MEDIA_ERROR_NOT_VALID_FOR_PROGRESSIVE_PLAYBACK:");
                        break;
                    case IMediaPlayer.MEDIA_ERROR_IO: // IO 错误
                        Log.d(TAG, "MEDIA_ERROR_IO :");
                        break;
                    case IMediaPlayer.MEDIA_ERROR_UNSUPPORTED: // 数据不支持
                        Log.d(TAG, "MEDIA_ERROR_UNSUPPORTED :");
                        break;
                    case IMediaPlayer.MEDIA_ERROR_TIMED_OUT: // 数据超时
                        Log.d(TAG, "MEDIA_ERROR_TIMED_OUT :");
                        break;
                    */
                }
                return true;
            }
        });
    }

    private void initPlayer() {
        /*
        ActionBar actionBar = getSupportActionBar();
        mMediaController = new AndroidMediaController(this, false);
        mMediaController.setSupportActionBar(actionBar);
        mVideoView.setMediaController(mMediaController);
        */
        final Settings settings = new Settings(this);
        settings.setEnableSurfaceView(false);
        settings.setEnableTextureView(true);

        mPlayerController = null;
        appVideoReplay.setVisibility(View.GONE);
        appVideoRetry.setVisibility(View.GONE);

        mPlayerController = new PlayerController(this, mVideoView)
                .setVideoParentLayout(findViewById(R.id.rl_video_view_layout)) // 建议第一个调用
                .setVideoController((SeekBar) findViewById(R.id.seekbar))
                .setVolumeController()
                .setBrightnessController()
                .setVideoParentRatio(IRenderView.AR_16_9_FIT_PARENT)
                .setVideoRatio(IRenderView.AR_16_9_FIT_PARENT)
                .setPortrait(true) // TODO: landscape ?
                .setKeepScreenOn(true)
                .setNetWorkTypeTie(true)
                .setNetWorkListener(new PlayerController.OnNetWorkListener() {
                    @Override
                    public void onChanged() {
                        if (mVideoView.getCurrentState() == IjkVideoView.STATE_IDLE) {
                            appVideoReplay.setVisibility(View.VISIBLE);
                            appVideoRetry.setVisibility(View.GONE);
                            playIcon.setEnabled(false);
                            updatePlayBtnBgState(true);
                        } else if (mVideoView.getCurrentState() == IjkVideoView.STATE_PAUSED) {
                            updatePlayBtnBg(false);
                        } else {
                            updatePlayBtnBg(false);
                        }
                    }
                })
                .setAutoControlListener(llBottom) // 触摸以下控件可以取消 "自动隐藏播放工具条" 的线程
                .setOnConfigurationChangedListener(new PlayerController.OnConfigurationChangedListener() {
                    @Override
                    public void onChanged(int requestedOrientation) {
                        if (requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT || requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) {
                            Utils.showSystemUI(mContext);
                            Utils.showStatusBar(mContext);
                        } else if (requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE || requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) {
                            Utils.hideSystemUI(mContext);
                            Utils.hideStatusBar(mContext);
                        }
                    }
                })
                .setPanelControl(new PlayerController.PanelControlListener() {
                    @Override
                    public void operatorPanel(boolean isShowControlPanel) {
                        if (isShowControlPanel) {
                            llBottom.setVisibility(View.VISIBLE);
                            bottomProgress.setVisibility(View.GONE);
                        } else {
                            llBottom.setVisibility(View.GONE);
                            bottomProgress.setVisibility(View.VISIBLE);
                        }
                    }
                })
                .setPlayStateListener(new PlayerController.PlayStateListener() {
                    @Override
                    public void playState(int state) {
                        switch (state) {
                            case IjkVideoView.STATE_PLAYING:
                                updatePlayBtnBg(true);
                                break;
                            case IjkVideoView.STATE_PAUSED:
                                updatePlayBtnBg(false);
                                break;
                        }
                    }
                })
                .setSyncProgressListener(new PlayerController.SyncProgressListener() {
                    @Override
                    public void syncTime(long position, long duration) {
                        tvCurrentTime.setText(mPlayerController.generateTime(position));
                        tvTotalTime.setText(mPlayerController.generateTime(duration));
                        if (subtitleView != null) {
                            subtitleView.seekTo(position);
                        }
                    }

                    @Override
                    public void syncProgress(int progress, int secondaryProgress) {
                        bottomProgress.setProgress(progress);
                        bottomProgress.setSecondaryProgress(secondaryProgress);
                    }
                })
                .setGestureListener(new PlayerController.GestureListener() {
                    @Override
                    public void onProgressSlide(long newPosition, long duration, int showDelta) {
                        if (showDelta != 0) {
                            appVideoFastForwardBox.setVisibility(View.VISIBLE);
                            appVideoBrightnessBox.setVisibility(View.GONE);
                            appVideoVolumeBox.setVisibility(View.GONE);
                            appVideoFastForwardTarget.setVisibility(View.VISIBLE);
                            appVideoFastForwardAll.setVisibility(View.VISIBLE);
                            appVideoFastForwardTarget.setText(mPlayerController.generateTime(newPosition) + "/");
                            appVideoFastForwardAll.setText(mPlayerController.generateTime(duration));

                            String text = showDelta > 0 ? ("+" + showDelta) : "" + showDelta;
                            appVideoFastForward.setVisibility(View.VISIBLE);
                            appVideoFastForward.setText(String.format("%ss", text));
                        }
                    }

                    @Override
                    public void onVolumeSlide(int volume) {
                        appVideoFastForwardBox.setVisibility(View.GONE);
                        appVideoBrightnessBox.setVisibility(View.GONE);
                        appVideoVolumeBox.setVisibility(View.VISIBLE);
                        appVideoVolume.setVisibility(View.VISIBLE);
                        appVideoVolume.setText(volume + "%");
                    }

                    @Override
                    public void onBrightnessSlide(float brightness) {
                        appVideoFastForwardBox.setVisibility(View.GONE);
                        appVideoBrightnessBox.setVisibility(View.VISIBLE);
                        appVideoVolumeBox.setVisibility(View.GONE);
                        appVideoBrightness.setVisibility(View.VISIBLE);
                        appVideoBrightness.setText((int) (brightness * 100) + "%");
                    }

                    @Override
                    public void endGesture() {
                        appVideoFastForwardBox.setVisibility(View.GONE);
                        appVideoBrightnessBox.setVisibility(View.GONE);
                        appVideoVolumeBox.setVisibility(View.GONE);
                    }
                });

        //prefer mVideoPath
        /*
        Settings settings = new Settings(this);
        settings.setPlayer(Settings.PV_PLAYER__IjkMediaPlayer);
        if (mVideoPath != null)
            mVideoView.setVideoPath(mVideoPath);
        else if (mVideoUri != null)
            mVideoView.setVideoURI(mVideoUri);
        else {
            Log.e(TAG, "Null Data Source\n");
            finish();
            return;
        }
        mVideoView.start();
        */

        onDestroyVideo();
        if (mVideoPath != null) {
            showVideoLoading();
            mVideoView.setVideoPath(mVideoPath);
            // 需要在 mVideoView.setRender() 方法之后调用
            //mVideoView.setVideoRadius(50);
            if (!Utils.isWifiConnected(mActivity)
                    && !mPlayerController.isLocalDataSource(mVideoUri)
                    && !PlayerController.WIFI_TIP_DIALOG_SHOWED)
            {
                mPlayerController.showWifiDialog();
            } else {
                mVideoView.start();
            }
        }
    }

    private void initClipGridFragment() {
        ClipGridFragment cgf = new ClipGridFragment();
        FragmentTransaction fragmentTransaction = getSupportFragmentManager().beginTransaction();
        fragmentTransaction.add(R.id.fl_clip_list, cgf);
        fragmentTransaction.commit();
    }

    public void changeVideo(String videoUri) {
        onDestroyVideo();
        mVideoPath = videoUri;
        Log.d(TAG, "change to video via mVideoPath: " + mVideoPath);
        if (mVideoPath != null) {
            showVideoLoading();
            mVideoView.setVideoPath(mVideoPath);
            mVideoView.start();
        }
    }

    /*
    private void initFragment() {
        SampleMediaListFragment videoUrlFragment = SampleMediaListFragment.newInstance();
        FragmentTransaction fragmentTransaction = getSupportFragmentManager().beginTransaction();
        fragmentTransaction.add(R.id.fl_clip_list, videoUrlFragment);
        fragmentTransaction.commit();

        videoUrlFragment.setOnItemClickListener(new SampleMediaListFragment.OnItemClickListener() {
            @Override
            public void OnItemClick(Context context, String videoPath, String videoTitle) {
                onDestroyVideo();
                mVideoPath = videoPath;
                Log.d(TAG, "OnItemClick: mVideoPath: " + mVideoPath);
                if (mVideoPath != null) {
                    showVideoLoading();
                    mVideoView.setVideoPath(mVideoPath);
                    mVideoView.start();
                }
            }
        });
    }
    */

    private void initListener() {
        playIcon.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (mVideoView.getCurrentState() == IjkVideoView.STATE_PLAYING) {
                    updatePlayBtnBg(true);
                } else if (mVideoView.getCurrentState() == IjkVideoView.STATE_PAUSED) {
                    updatePlayBtnBg(false);
                } else {
                    mVideoView.clickStart();
                }
            }
        });
        imgChangeScreen.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mPlayerController != null) {
                    if (mPlayerController.isPortrait()) {
                        updateFullScreenBg(true);
                    } else {
                        updateFullScreenBg(false);
                    }
                    mPlayerController.toggleScreenOrientation();
                }
            }
        });
        appVideoReplayIcon.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                initPlayer();
            }
        });
        appVideoRetryIcon.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                initPlayer();
            }
        });

        /*
        Spinner sp_gesture = (Spinner) findViewById(R.id.sp_gesture);
        final String[] gesture = {"视频手势操作", "开启", "关闭"};
        ArrayAdapter<String> gestureAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, gesture);
        gestureAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

        sp_gesture.setAdapter(gestureAdapter);
        sp_gesture.setSelection(0, true);
        sp_gesture.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                if (pos == 0) {
                    return;
                } else if (pos == 1) {
                    mPlayerController
                            .setGestureEnabled(false)
                            .setAutoControlPanel(false);
                    mVideoView.setGesture(true, true, true);
                } else if (pos == 2) {
                    mPlayerController
                            .setGestureEnabled(true)
                            .setAutoControlPanel(true);
                    mVideoView.setGesture(false, false, false);
                    mVideoView.resetGesture();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // Another interface callback
            }
        });
        */

        Spinner sp_speed = (Spinner) findViewById(R.id.sp_speed);
        final String[] speeds = {"倍 速", "0.25", "0.5", "0.75", "1", "1.25", "1.5", "1.75", "2"};
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, speeds);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

        sp_speed.setAdapter(adapter);
        sp_speed.setSelection(0, true);
        sp_speed.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                if (pos == 0) {
                    return;
                }
                try {
                    float parseFloat = Float.parseFloat(speeds[pos]);
                    mVideoView.setSpeed(parseFloat);
                } catch (NumberFormatException e) {
                    e.printStackTrace();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // Another interface callback
            }
        });

        /*
        Spinner sp_subtitle = (Spinner) findViewById(R.id.sp_subtitle);
        final String[] subtitles = {
                "字幕",
                "ass",
                "srt",
                "stl",
                "xml"
        };
        final String[] subtitleList = {
                "字幕",
                "standards/ASS/Oceans.Eight.2018.1080p.BluRay.x264-SPARKS.简体.ass",
                "standards/SRT/哆啦A梦：伴我同行.1080P.x264.Hi10P.flac.chs.srt",
                "standards/STL/Aquí no hay quien viva 1.STL",
                "standards/XML/Debate0_03-03-08.dfxp.xml"
        };
        ArrayAdapter<String> subtitleAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, subtitles);
        subtitleAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

        sp_subtitle.setAdapter(subtitleAdapter);
        sp_subtitle.setSelection(0, true);
        sp_subtitle.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, final int pos, long id) {
                ThreadUtil.runInThread(new Runnable() {
                    @Override
                    public void run() {
                        if (pos == 0) {
                            return;
                        }
                        String subtitle = subtitleList[pos];
                        final TimedTextObject tto;
                        TimedTextFileFormat ttff = null;
                        try {
                            InputStream is = getAssets().open(subtitle);
                            switch (subtitles[pos]) {
                                case "ass":
                                    ttff = new FormatASS();
                                    break;
                                case "srt":
                                    ttff = new FormatSRT();
                                    break;
                                case "stl":
                                    ttff = new FormatSTL();
                                    break;
                                case "xml":
                                    ttff = new FormatTTML();
                                    break;
                                default:

                                    break;
                            }

                            tto = ttff != null ? ttff.parseFile("test", is) : null;
                            //IOClass.writeFileTxt("test.srt", tto.toSRT());

                            ThreadUtil.runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    Toast.makeText(mContext, "加载字幕成功", Toast.LENGTH_SHORT).show();
                                    if (subtitleView != null) {
                                        subtitleView.setData(tto);
                                        subtitleView.setLanguage(SubtitleView.LANGUAGE_TYPE_CHINA);
                                    }
                                }
                            });
                        } catch (IOException e) {
                            e.printStackTrace();
                        } catch (FatalParsingException e) {
                            e.printStackTrace();
                        }
                    }
                });
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // Another interface callback
            }
        });
        */
    }

    private void showVideoLoading() {
        if (appVideoLoading != null) {
            appVideoLoading.setVisibility(View.VISIBLE);
            appVideoSpeed.setVisibility(View.VISIBLE);
            appVideoSpeed.setText("");
        }
    }

    private void hideVideoLoading() {
        if (appVideoLoading != null) {
            appVideoLoading.setVisibility(View.GONE);
            appVideoSpeed.setVisibility(View.GONE);
            appVideoSpeed.setText("");
        }
    }

    private void initVideoControl() {
        //playIcon.setEnabled(false);
        seekbar.setEnabled(false);
        seekbar.setProgress(0);
        seekbar.setSecondaryProgress(0);
    }

    private void showVideoInfo(IMediaPlayer mMediaPlayer) {
        if (mMediaPlayer != null && mMediaPlayer instanceof IjkMediaPlayer) {
            IjkMediaPlayer mp = (IjkMediaPlayer) mMediaPlayer;

            float fpsOutput = mp.getVideoOutputFramesPerSecond();
            float fpsDecode = mp.getVideoDecodeFramesPerSecond();
            long videoCachedDuration = mp.getVideoCachedDuration();
            long audioCachedDuration = mp.getAudioCachedDuration();
            long videoCachedBytes = mp.getVideoCachedBytes();
            long audioCachedBytes = mp.getAudioCachedBytes();
            long tcpSpeeds = mp.getTcpSpeed();
            long bitRates = mp.getBitRate();
            long seekLoadDuration = mp.getSeekLoadDuration();

            mPlayerController.setVideoInfo(fps, String.format(Locale.US, " %.2f / %.2f", fpsDecode, fpsOutput));
            mPlayerController.setVideoInfo(vCache, String.format(Locale.US, " %s, %s", formatedDurationMilli(videoCachedDuration), formatedSize(videoCachedBytes)));
            mPlayerController.setVideoInfo(aCache, String.format(Locale.US, " %s, %s", formatedDurationMilli(audioCachedDuration), formatedSize(audioCachedBytes)));
            mPlayerController.setVideoInfo(seekLoadCost, String.format(Locale.US, " %d ms", seekLoadDuration));
            mPlayerController.setVideoInfo(tcpSpeed, String.format(Locale.US, " %s", formatedSpeed(tcpSpeeds)));
            mPlayerController.setVideoInfo(bitRate, String.format(Locale.US, " %.2f kbs", bitRates / 1000f));

            if (tcpSpeeds == -1) {
                return;
            }
            if (appVideoSpeed != null) {
                String formatSize = formatedSpeed(tcpSpeeds);
                appVideoSpeed.setText(formatSize);
            }
        }
        /*
        else if (mMediaPlayer != null && mMediaPlayer instanceof IjkExoMediaPlayer) {
            IjkExoMediaPlayer mp = (IjkExoMediaPlayer) mMediaPlayer;

            long tcpSpeeds = mp.getTotalRxBytes(mActivity);
            mPlayerController.setVideoInfo(bitRate, String.format(Locale.US, "%.2f kbs", tcpSpeeds / 1000f));
            if (appVideoSpeed != null) {
                String formatSize = formatedSpeed(tcpSpeeds);
                appVideoSpeed.setText(formatSize);
            }
        }
        */
    }

    /**
     * 更新播放按钮的背景图片，正在播放
     */
    private void updatePlayBtnBg(boolean isPlay) {
        try {
            int resid;
            if (isPlay) {
                // 暂停
                resid = R.drawable.player_click_play_selector;
                mVideoView.pause();
            } else {
                // 播放
                resid = R.drawable.player_click_pause_selector;
                mVideoView.start();
            }
            playIcon.setImageResource(resid);
        } catch (Exception e) {

        }
    }

    /**
     * 更新播放按钮的背景图片，正在播放
     */
    private void updatePlayBtnBgState(boolean isPlay) {
        try {
            int resid;
            if (isPlay) {
                // 暂停
                resid = R.drawable.player_click_play_selector;
            } else {
                // 播放
                resid = R.drawable.player_click_pause_selector;
            }
            playIcon.setImageResource(resid);
        } catch (Exception e) {

        }
    }

    /**
     * 更新全屏按钮的背景图片
     */
    private void updateFullScreenBg(boolean isFullSrceen) {
        try {
            int resid;
            if (isFullSrceen) {
                // 全屏
                resid = R.drawable.simple_player_icon_fullscreen_shrink;
            } else {
                // 非全屏
                resid = R.drawable.simple_player_icon_fullscreen_stretch;
            }
            imgChangeScreen.setBackgroundResource(resid);
        } catch (Exception e) {

        }

    }

    @Override
    public void onBackPressed() {
        mBackPressed = true;

        super.onBackPressed();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!mVideoView.isBackgroundPlayEnabled()) {
            if (!Utils.isWifiConnected(mActivity) && !mPlayerController.isLocalDataSource(mVideoUri) && !PlayerController.WIFI_TIP_DIALOG_SHOWED) {
                //mPlayerController.showWifiDialog();
            } else {
                updatePlayBtnBg(false);
            }
        }
    }

    @Override
    protected void onStop() {
        super.onStop();

        if (mBackPressed || !mVideoView.isBackgroundPlayEnabled()) {
            //mVideoView.stopPlayback();
            //mVideoView.release(true);
            //mVideoView.stopBackgroundPlay();
            updatePlayBtnBg(true);
        } else {
            mVideoView.enterBackground();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        onDestroyVideo();
    }

    private void onDestroyVideo() {
        if (appVideoReplay != null) {
            appVideoReplay.setVisibility(View.GONE);
        }
        if (appVideoRetry != null) {
            appVideoRetry.setVisibility(View.GONE);
        }
        if (mPlayerController != null) {
            mPlayerController.onDestroy();
        }
        if (mVideoView != null) {
            mVideoView.stopPlayback();
            mVideoView.release(true);
            mVideoView.stopBackgroundPlay();
            mVideoView.stopVideoInfo();
        }
        WindowManagerUtil.removeSmallWindow(mContext);
        WindowManagerUtil.removeSmallApp(mActivity);
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (mPlayerController != null) {
            mPlayerController.onConfigurationChanged();
        }

    }

    public static String getDefaultUri() {
        return URI_LIST[4][1];
    }

    final static String[][] URI_LIST = {
            {"0", "http://192.168.2.6/vod/3s.mp4"},
            {"1", "http://mozicode.com/garfield.mp4"},
            {"2", "rtmp://192.168.2.6:2023/vod/o3.mp4"},
            {"3", "rtmp://mozicode.com:2023/live/home"},
            {"4", "rtmp://mozicode.com:2023/vod/garfield.mp4"},
            {"5", "rtmp://mozicode.com:2023/vod/8s.mp4"},
            {"6", "rtmp://mozicode.com:2023/vod/20091212.mp4"},
            {"7", "http://devimages.apple.com.edgekey.net/streaming/examples/bipbop_4x3/gear1/prog_index.m3u8"},
            {"双打0721-1", "http://mozicode.com/20230729-133932.mp4"},
    };
}
