package com.bixiaoqu.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.ColorDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;

/**
 * 播放页（系统解码 + 全屏 + 双指缩放 + 相关推荐）：
 * · DASH 双流：画面轨 VideoView + 音轨 MediaPlayer（1080P/4K）
 * · 合流兼容：单文件 mp4 直连本地代理（DASH 失败或兼容模式时兜底）
 * 注：早期版本的 VLC 软解引擎已移除（libvlc.so 占 19MB，为控制体积删除）
 * 手势：双击暂停/继续 · 长按 2x · 单击控制条 · 双指缩放画面
 * 全屏：横屏 + 隐藏系统栏 + 控制条浮于画面底部 + 推荐区收起
 * 底部：相关推荐（related 接口，失败自动降级热门）
 * 缓存 MP3：下载音轨 → LAME 320k → 音乐/BiliAudio
 */
public class PlayActivity extends Activity {

    TxVideoView video;
    SurfaceView vlcSurface;
    FrameLike holder;
    FrameLike overlay;
    LinearLayout ctrlBar;
    LinearLayout topBar, upRow, infoWrap, interactBar, actRowRef, titleColRef;
    TextView titleTop, watchText, upNameTop, statText;
    Act actLikeTop, actCmtTop, actCoinTop, actFavTop, actShareTop;
    Act actLikeBot, actCmtBot, actCoinBot, actFavBot, actShareBot;
    Button followBtnTop, followBtnBot;
    ImageView upAvatarTop, upAvatarBot;
    ImageView playIcon, nextBtn, fullIcon;
    DmSwitch dmSwitch;
    android.app.Dialog dmStyleDlg;   // 【v4.8】弹幕样式面板
    TextView timeText, dmHint, pickBtn, speedBtn;
    ImageView moreBtn;
    LinearLayout recWrap;
    ListView recList;
    LinearLayout root;
    TextView tabComment;
    TextView tipText, speedTag, zoomTag, bvidText;
    ImageView backArrow;
    TextView hud;
    SeekBar seek;
    Button fullBtn;   // 已废弃，保留字段避免遗漏引用
    TextView qualityBtn;
    /** 【v4.9】解码能力按钮：显示本视频的编码清单入口 */
    TextView codecBtn;
    /** 【v4.9】本视频的解码能力清单（BiliApi.codecAbility 的结果） */
    JSONArray codecAbilityArr = null;


    // 高清双流：视频轨走 VideoView，音频轨走独立 MediaPlayer（DASH 是音视频分离的）
    MediaPlayer audioPlayer;
    volatile boolean dashMode = false;
    volatile boolean dashFailed = false;
    /** 【v4.6】音频轨已 prepare 完毕、正等画面就绪后再出声（避免声音抢跑） */
    volatile boolean audioPrepared = false;
    /** 【v4.6】音频是否已经真正起播过（未起播时不能被 togglePlay 抢跑） */
    volatile boolean audioStarted = false;
    /** 【v4.6】画面 onPrepared 时应让音频对齐到的起点（续播/seek 异步时 getCurrentPosition 还不可靠） */
    int pendingAudioStartPos = -1;
    JSONObject playDataCache;
    long pageDurMs = 0;
    long curCid = 0;
    String curBv = "";
    long lastAudioSync = 0;
    int resumePos = 0;
    /**
     * 【v4.17.1】画面被摘下前是否处于暂停态。
     * 正常情况下分屏/全屏切换已经不搬动画面容器，不会走这条路径；
     * 这里只作为兜底：万一系统仍销毁了 Surface，重建后不要擅自开始播放。
     */
    boolean surfaceLostPaused = false;
    /** 【v4.18.0】本次播放的代际号：换片/换清晰度时递增，代理据此掐断旧连接 */
    int playGen = 1;
    /** 【v4.18.0】正在切清晰度：下一次起播要沿用当前画面进度，并保持原播放/暂停状态 */
    boolean qualitySwitchPending = false;
    /** 切清晰度前是否处于暂停态：切完不要自己播起来 */
    boolean switchPausePending = false;
    /** 【v4.18.0】起播计时：从点开到 prepared 用了多久（写入日志，便于核对 2 秒目标） */
    long playStartMs = 0;
    /** 【v4.8】本次 play() 加载层文案，用完自动恢复 */
    String playLoadMsg = "加载中喵~";
    /** 【v4.9.4】延迟上屏的加载框任务（画面先到就撤掉它，全程不闪） */
    Runnable loadAnimPending;
    /** 【v4.9.6】评论抽屉：底部升起、视频仍可见，不再整页糊满 */
    CommentSheet commentSheet;
    FrameLike shellFrame;

    // ---- 视频信息 / 互动状态 ----
    long aid = 0;
    long ownerMid = 0;
    String ownerFace = "";
    /** 【v4.9.1】本地缓存文件地址（content://…）。非空时直接播本地，不走网络。 */
    String localUri = "";
    String videoTitle = "";
    String videoCover = "";
    String upName = "";
    String pubDate = "";
    int statLike = 0, statReply = 0, statCoin = 0, statFav = 0, statShare = 0;
    long statView = 0, statDm = 0;
    boolean liked = false, faved = false;
    int coins = 0, followedState = 0;
    JSONArray pagesArr = null;
    int pageIndex = 0;
    String favFolderId = "";
    boolean metaLoaded = false;

    // 【v4.1 提速】列表页预取的 cid / 宽高，跳过启动时串行的 pages() 请求
    long prefetchedCid = 0;
    float prefetchedAspect = 0f;
    String prefetchedTitle = "";
    long prefetchedDur = 0;

    // 左边上下滑 = 亮度，右边上下滑 = 音量
    android.media.AudioManager audioMgr;
    int maxVolume = 15;
    int startVolume = 0;
    float startBrightness = 0.5f;
    float downX = 0f, downY = 0f;
    boolean vertDrag = false;
    boolean vertVolume = false;
    /** 【v4.9】竖屏翻页模式下，横向拖动代替纵向调亮度/音量 */
    boolean horzDrag = false;

    final ArrayList<VideoItem> recRows = new ArrayList<>();
    VideoAdapter recAdapter;

    MediaPlayer player;                 // 播放引擎（VideoView onPrepared 捕获）

    LocalProxy proxy;
    GestureDetector gesture;
    ScaleGestureDetector scaleDet;

    // 【v3.9】合流播放失败重试计数（换线路重新取地址），切视频时归零
    int muxRetry = 0;
    static final int MAX_MUX_RETRY = 2;

    // 【v4.0】加载界面：圆角方块闭眼小电视 + 「正在加载喵~」+ Dynamo 署名
    android.view.animation.Animation loadDanceAnim;
    android.widget.ImageView loadMascot;
    TextView loadCaption;
    TextView loadSign;
    LinearLayout loadBox;
    boolean loadAnimShowing = false;

    final Handler ui = new Handler(Looper.getMainLooper());
    String bvid;
    volatile boolean boosting = false;
    volatile boolean cached = false;
    boolean ctrlVisible = true;

    boolean fullscreen = false;
    boolean twoFinger = false;
    float zoom = 1f, tx = 0f, ty = 0f;
    int videoHeight;

    // 竖屏视频「铺满整屏」沉浸模式：竖屏持机时让画面按视频比例放大铺满，不留黑边
    boolean portraitFill = false;
    TextView exitFillBtn;

    // ------------------------------------------------------------ 【v4.19.0】B 站竖屏沉浸式界面
    LinearLayout vRail;        // 右侧竖排互动：头像(+关注) / 赞 / 币 / 藏 / 评 / 转
    LinearLayout vBottom;      // 底部：标题 + UP 主 + 工具行
    SeekBar vSeek;             // 最底部细进度条
    TextView vTitleTxt, vUpTxt, vTime, vFollowBadge;
    ImageView vAvatar, vPlay, vFull, vTopMore;
    TextView vPick, vSpeed, vDmHint;
    DmSwitch vDmSwitch;
    Act actLikeRail, actCmtRail, actCoinRail, actFavRail, actShareRail;
    boolean vUiVisible = true;      // 沉浸式控件显隐（单击画面切换）
    boolean dmSuspended = false;    // 滑动/翻页期间暂停弹幕绘制，省 GPU 与主线程

    // ------------------------------------------------------------ 竖屏上下滑翻页（抖音式）
    /**
     * 【v4.9】竖屏沉浸模式下，上滑看下一个视频、下滑看上一个视频（参考抖音）。
     *
     * 【结构】
     *   holder
     *    ├ bgCover      背景封面（压暗）
     *    ├ swipeLayer   翻页容器（仅竖屏铺满时可见）
     *    │   ├ slotNext  相邻视频槽（封面 + 标题）—— z 序在下
     *    │   └ slotCur   当前视频槽（画面 + 弹幕）—— z 序在上
     *    └ overlay      控制层（触摸入口，永远在最上）
     *
     * 【跟手原理】
     *   手指纵向位移 dy 直接写进 slotCur.setTranslationY(dy)；
     *   slotNext.setTranslationY(dy ± 屏高) 让它从相邻位置跟进来。
     *   两槽始终相距恰好一个屏高 → 视觉上是连续翻页。
     *
     * 【为什么画面必须用 TextureView】
     *   系统 VideoView 内部是 SurfaceView（独立图层），父容器位移时画面不跟随，
     *   根本没法做跟手效果。所以 v4.9 换成 TxVideoView（TextureView 封装）。
     */
    FrameLike swipeLayer;          // 竖屏翻页容器
    FrameLike slotCur, slotNext, slotPrev;   // 当前槽 / 下一槽 / 上一槽
    ImageView slotNextCover;       // 下一槽封面（视频未就绪时先显示封面，避免黑屏）
    TextView slotNextTitle;        // 下一槽标题
    ImageView slotPrevCover;       // 上一槽封面
    TextView slotPrevTitle;        // 上一槽标题
    boolean swipeMode = false;      // 是否处于竖屏滑动模式
    float swipeDy = 0f;            // 当前跟手位移（像素，向上为负）
    boolean swiping = false;       // 是否正在滑动（区别于点击）
    boolean swipeAnimating = false;// 翻页动画进行中（期间不响应新手势）
    /** 【v4.9】在途的翻页动画，退出竖屏/销毁页面时要取消，否则回调会继续操作界面 */
    android.animation.ValueAnimator swipeAnim = null;
    int swipeTarget = 0;           // +1 下一个 / -1 上一个 / 0 不切
    long swipeDownTime = 0;
    float swipeVel = 0f;           // 抬手瞬间的纵向速度（px/s）
    ArrayList<VideoItem> swipeFeed = new ArrayList<>();  // 竖屏可滑动的视频队列
    int swipeIndex = -1;           // 当前在 swipeFeed 中的位置
    String swipePrefetchBv = "";   // 预取中的下一条 bvid（用于丢弃过期结果）
    long swipeFeedStamp = 0;       // 队列加载时间戳（过期则重建）
    boolean swipeFeedLoading = false;
    /** 【v4.9】页面已销毁标记：动画/线程回调据此短路，避免在死页面上起播放器 */
    boolean swipeDestroyed = false;
    /** 【v4.9.1】画面自检是否已挂（每个视频只挂一次） */
    boolean frameWatchArmed = false;
    /** 【v4.9】本次切换的海报是否已处理过（防止 onPrepared 重复触发多次淡出） */
    boolean swipePosterHidden = false;
    /** 【v4.13.12】首帧在动画期间就绪时，推迟到动画归位后再淡出海报，避免露黑屏 */
    boolean posterPendingAfterAnim = false;
    /** 【v4.9】正在淡出海报的槽数量（全部淡完才复位两槽） */
    int swipeFadePending = 0;
    /** 竖屏翻页队列的最小长度（不够就继续补） */
    static final int SWIPE_FEED_MIN = 12;

    // 弹幕
    DanmakuView dmView;
    boolean dmOn = true;
    long dmCid = 0;
    String dmBvid = "";
    Button dmToggleBtn;   // 已被自绘 DmSwitch 取代
    LinearLayout dmPanel;
    EditText dmInput;

    // 竖屏视频横屏优化
    ImageView bgCover;
    ImageView flipCover;              // 切视频翻书动画的封面遮罩层
    boolean flipping = false;         // 翻书动画进行中，防止重入
    LinearLayout portraitInfo;
    TextView pTitle, pUp;
    float videoAspect = 16f / 9f;
    boolean portraitVideo = false;
    String coverUrl = "", vTitle = "", vUp = "";

    final Runnable tick = new Runnable() {
        @Override public void run() {
            try {
                if (dashMode) {
                    int len = pageDurMs > 0 ? (int) pageDurMs : video.getDuration();
                    if (len > 0) { seek.setMax(len); if (vSeek != null) vSeek.setMax(len); }
                    int pos = video.getCurrentPosition();
                    if (pos >= 0) { seek.setProgress(pos); if (vSeek != null) vSeek.setProgress(pos); }
                    setTimeText(pos, len);
                    if (pos > 0) syncAudio(pos);
                } else if (video != null && video.isPlaying()) {
                    int len = video.getDuration() > 0 ? video.getDuration() : (int) pageDurMs;
                    if (len > 0) { seek.setMax(len); if (vSeek != null) vSeek.setMax(len); }
                    int pos = video.getCurrentPosition();
                    seek.setProgress(pos);
                    if (vSeek != null) vSeek.setProgress(pos);
                    setTimeText(pos, len);
                }
            } catch (Exception ignore) { }
            // 每 5 秒落一次播放进度，用于下次续播
            long nowSave = System.currentTimeMillis();
            if (nowSave - lastPosSave > 5000) {
                lastPosSave = nowSave;
                savePosNow();
            }
            ui.postDelayed(this, 500);
        }
    };

    /** 高清双流音画漂移校正：超过 0.5 秒就把音频拉回画面位置 */
    void syncAudio(int vPos) {
        if (audioPlayer == null || !audioStarted) return;
        long now = System.currentTimeMillis();
        if (now - lastAudioSync < 2500) return;
        lastAudioSync = now;
        try {
            int aPos = audioPlayer.getCurrentPosition();
            if (Math.abs(aPos - vPos) > 500) audioPlayer.seekTo(vPos);
        } catch (Throwable ignore) { }
    }

    /** 弹幕专用高频进度同步（80ms），只读进度不碰 UI 状态 */
    final Runnable dmTick = new Runnable() {
        @Override public void run() {
            try {
                // 【v4.19.0】上下滑翻页期间不推进弹幕：弹幕是整屏文字重绘，
                // 和跟手位移动画抢合成时间，是滑动手感变涩的主要来源之一。
                if (dmView != null && dmOn && !dmSuspended) {
                    long p = -1;
                    if (video != null) {
                        p = video.getCurrentPosition();
                    }
                    if (p >= 0) dmView.setPosition(p);
                }
            } catch (Throwable ignore) { }
            ui.postDelayed(this, 80);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        bvid = getIntent() != null ? getIntent().getStringExtra("bvid") : null;
        if (getIntent() != null) {
            localUri = getIntent().getStringExtra("localUri");
            if (localUri == null) localUri = "";
            prefetchedCid = getIntent().getLongExtra("cid", 0);
            prefetchedAspect = getIntent().getFloatExtra("aspect", 0f);
            prefetchedTitle = getIntent().getStringExtra("ptitle");
            prefetchedDur = getIntent().getLongExtra("pdur", 0);
            // 用列表页预取的宽高先行判断竖屏，让铺满更早生效，不等 meta 请求
            if (prefetchedAspect > 0f) {
                videoAspect = prefetchedAspect;
                portraitVideo = prefetchedAspect < 1f / 1.15f;
            }
        }
        try {
            proxy = new LocalProxy();
            proxy.start();
        } catch (Exception e) {
            proxy = null;
        }
        dmOn = Prefs.danmaku(this);
        rate = Prefs.speed(this);          // 记住上次倍速
        buildUi();
        if (speedBtn != null) speedBtn.setText(rateLabel(rate));
        ui.post(tick);
        ui.post(dmTick);
        if (bvid == null) bvid = "";
        if (!localUri.isEmpty()) {
            // 【v4.9.1】离线缓存：直接播本地 MP4，不做任何网络请求
            portraitVideo = false;
            ui.postDelayed(new Runnable() { @Override public void run() { playLocal(); } }, 120);
        } else if (!bvid.isEmpty()) {
            play(Prefs.decoder(this));
            loadRec();
        }
        // 【手机版】进入时若已经是横屏，直接进全屏观看
        try {
            if (getResources().getConfiguration().orientation
                    == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
                ui.postDelayed(new Runnable() { @Override public void run() {
                    if (!isFinishing() && !fullscreen) enterFullscreen();
                } }, 260);
            }
        } catch (Throwable ignore) { }
    }

    /**
     * 【v4.9.1】播放本地缓存文件。
     * 离线缓存以前是甩给外部播放器（Intent.ACTION_VIEW），多数手机没有默认播放器
     * 或者弹「无法打开」，表现为「点了没反应」。现在用应用自己的播放器直接播，
     * 并且不走 LocalProxy（本地文件不需要补 Referer/Cookie）。
     */
    void playLocal() {
        if (localUri.isEmpty()) return;
        scheduleLoadAnim("正在打开缓存…", 200);
        if (bvidText != null) bvidText.setText("离线缓存");
        if (tipText != null) tipText.setText("本地文件 · 不消耗流量");
        startHard(localUri, false);
    }

    int dp(float v) {
        return (int) (getResources().getDisplayMetrics().density * v);
    }

    /**
     * 【v4.9.1】跟随手机旋转自动切全屏。
     * 旧版只把界面横过来、播放器还是竖屏的小窗（用户说「感觉像个浏览器」）。
     * 现在横屏持机 = 直接进全屏播放；转回竖屏 = 退出全屏。
     */
    @Override
    public void onConfigurationChanged(android.content.res.Configuration cfg) {
        super.onConfigurationChanged(cfg);
        try {
            boolean land = cfg.orientation
                    == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
            // 【手机版】横屏即全屏观看，竖屏回详情页
            if (land) {
                if (!fullscreen) enterFullscreen();
            } else {
                if (fullscreen) exitFullscreen();
            }
        } catch (Throwable e) {
            CrashHandler.note("orient", "自动旋转处理失败：" + e);
        }
    }

    /** 【v4.9.1】点 UP 主头像 / 昵称 → 进 UP 主主页 */
    void goUpHome() {
        if (ownerMid <= 0) {
            showToastSafe("UP 主信息还没加载好，稍等再试");
            return;
        }
        try {
            android.content.Intent it = new android.content.Intent(this, UserActivity.class);
            it.putExtra("mid", String.valueOf(ownerMid));
            it.putExtra("name", upName == null ? "" : upName);
            it.putExtra("face", ownerFace == null ? "" : ownerFace);
            startActivity(it);
        } catch (Throwable e) {
            showToastSafe("打不开 UP 主主页");
        }
    }

    /**
     * 【v4.0】加载界面：圆角方块闭眼小电视（应用图标本体）+「正在加载喵~」+ Dynamo 署名。
     * 参考用户提供的播放页加载样式，但保留本软件精华：闭眼小电视 logo、粉色主题、喵~ 文案与作者署名。
     * 浅蓝渐变圆角方块自带底色，无需额外黑色底板；加载开始时显示，画面就绪后收起。
     */
    void buildLoadAnim(FrameLike overlay) {
        loadBox = new LinearLayout(this);
        loadBox.setOrientation(LinearLayout.VERTICAL);
        loadBox.setGravity(Gravity.CENTER);
        loadBox.setClickable(false);
        loadBox.setFocusable(false);
        // 极淡的暗色底板，保证在亮色视频首帧上也能看清（比原纯黑更轻）
        android.graphics.drawable.GradientDrawable plate = new android.graphics.drawable.GradientDrawable();
        plate.setColor(0x33000000);
        plate.setCornerRadius(dp(24));
        loadBox.setBackground(plate);
        loadBox.setPadding(dp(28), dp(24), dp(28), dp(20));

        // 主体：应用图标本身就是「浅蓝渐变圆角方块 + 闭眼小电视」，正是参考图的样子
        loadMascot = new ImageView(this);
        int mascotSize = dp(88);
        int mascotRes = getResources().getIdentifier("ic_launcher", "mipmap", getPackageName());
        if (mascotRes == 0) mascotRes = getResources().getIdentifier("logo", "drawable", getPackageName());
        if (mascotRes == 0) mascotRes = android.R.drawable.ic_media_play;
        loadMascot.setImageResource(mascotRes);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(mascotSize, mascotSize);
        mp.gravity = Gravity.CENTER;
        mp.bottomMargin = dp(14);
        loadMascot.setLayoutParams(mp);
        loadBox.addView(loadMascot);

        // 文案：正在加载喵~（粉色，保留软件主题色）
        loadCaption = new TextView(this);
        loadCaption.setText("加载中喵~");
        loadCaption.setTextColor(0xFFFB7299);
        loadCaption.setTextSize(15);
        loadCaption.setTypeface(null, android.graphics.Typeface.BOLD);
        loadCaption.setGravity(Gravity.CENTER);
        loadBox.addView(loadCaption);

        // 署名：Dynamo（保留软件精华）
        loadSign = new TextView(this);
        loadSign.setText("Dynamo");
        loadSign.setTextColor(0x99FFFFFF);
        loadSign.setTextSize(11);
        loadSign.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.topMargin = dp(6);
        loadSign.setLayoutParams(sp);
        loadBox.addView(loadSign);

        FrameLike.LayoutParams lp = new FrameLike.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        overlay.addView(loadBox, lp);

        // ---- 柔和跳舞动画：上下轻跳 + 左右摇摆 + 缩放呼吸，作用在 mascot 上 ----
        android.view.animation.AnimationSet dance =
                new android.view.animation.AnimationSet(true);
        android.view.animation.TranslateAnimation jump =
                new android.view.animation.TranslateAnimation(0, 0, dp(0), dp(-10));
        jump.setDuration(680);
        jump.setRepeatCount(android.view.animation.Animation.INFINITE);
        jump.setRepeatMode(android.view.animation.Animation.REVERSE);
        jump.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());

        android.view.animation.RotateAnimation wiggle = new android.view.animation.RotateAnimation(
                -8f, 8f, android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f,
                android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f);
        wiggle.setDuration(820);
        wiggle.setRepeatCount(android.view.animation.Animation.INFINITE);
        wiggle.setRepeatMode(android.view.animation.Animation.REVERSE);
        wiggle.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());

        android.view.animation.ScaleAnimation breathe = new android.view.animation.ScaleAnimation(
                1f, 0.94f, 1f, 0.94f,
                android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f,
                android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f);
        breathe.setDuration(680);
        breathe.setRepeatCount(android.view.animation.Animation.INFINITE);
        breathe.setRepeatMode(android.view.animation.Animation.REVERSE);

        dance.addAnimation(jump);
        dance.addAnimation(wiggle);
        dance.addAnimation(breathe);
        loadDanceAnim = dance;
        loadBox.setVisibility(View.GONE);
    }

    /**
     * 【v4.9.4】加载框「延迟上屏」。
     * 视频真正起播往往只要几百毫秒，若一进 play() 就把黑底药丸糊到画面中央，
     * 用户看到的就是「闪一下的正加载」。所以先挂一个延迟任务：
     * 期间画面就绪 → showLoadAnim(false) 直接把它撤掉，加载框一次都不出现。
     * 竖屏刷信息流本身有封面海报兜底，延迟给得更长（1200ms），正常网络下基本永不显示。
     */
    void scheduleLoadAnim(String msg, long delayMs) {
        cancelLoadAnimSchedule();
        final String m = (msg == null || msg.isEmpty()) ? "加载中喵~" : msg;
        loadAnimPending = new Runnable() { @Override public void run() {
            loadAnimPending = null;
            if (isFinishing()) return;
            showLoadAnim(true, m);
        } };
        ui.postDelayed(loadAnimPending, Math.max(0L, delayMs));
    }

    void cancelLoadAnimSchedule() {
        if (loadAnimPending != null) {
            ui.removeCallbacks(loadAnimPending);
            loadAnimPending = null;
        }
    }

    /** 显示/隐藏加载动画。show=true 同时把文案设成 msg（默认「加载中喵~」）。 */
    void showLoadAnim(boolean show, String msg) {
        if (loadBox == null) return;
        if (!show) cancelLoadAnimSchedule();   // 【v4.9.4】画面已就绪，挂起的加载框一并作废
        loadAnimShowing = show;
        if (show) {
            loadCaption.setText(msg == null ? "加载中喵~" : msg);
            loadBox.setVisibility(View.VISIBLE);
            loadBox.clearAnimation();
            if (loadDanceAnim != null) loadBox.startAnimation(loadDanceAnim);
        } else {
            loadBox.clearAnimation();
            loadBox.setVisibility(View.GONE);
        }
        // 【v4.9】竖屏沉浸模式：去掉吉祥物图标与 Dynamo 署名，只保留文字。
        // 竖屏是全屏沉浸观看，画面中央压一个大图标非常突兀，抖音等竖屏应用
        // 也都是「纯文字 + 小转圈」；这里按需求直接只留文字，更干净。
        applyVerticalLoadStyle();
    }

    /**
     * 【v4.9】竖屏加载样式：隐藏图标与署名，只留文案。
     * 横屏 / 普通详情页保持原有的「跳舞小电视 + 正在加载喵~ + Dynamo」完整样式。
     */
    void applyVerticalLoadStyle() {
        if (loadBox == null) return;
        boolean vertical = portraitFill || portraitVideo;
        try {
            if (loadMascot != null) {
                loadMascot.setVisibility(vertical ? View.GONE : View.VISIBLE);
                loadMascot.clearAnimation();
            }
            if (loadSign != null) {
                loadSign.setVisibility(vertical ? View.GONE : View.VISIBLE);
            }
            if (loadCaption != null) {
                // 文字加大加亮，竖屏下才是唯一视觉焦点
                loadCaption.setTextSize(vertical ? 17 : 15);
                loadCaption.setTextColor(vertical ? 0xFFFFFFFF : 0xFFFB7299);
            }
            // 竖屏下底板收紧，去掉图标后原来的大留白会显得很空
            loadBox.setPadding(dp(vertical ? 22 : 28), dp(vertical ? 16 : 24),
                    dp(vertical ? 22 : 28), dp(vertical ? 14 : 20));
            android.graphics.drawable.GradientDrawable plate =
                    new android.graphics.drawable.GradientDrawable();
            plate.setColor(vertical ? 0x4D000000 : 0x33000000);
            plate.setCornerRadius(dp(vertical ? 18 : 24));
            loadBox.setBackground(plate);
        } catch (Throwable ignore) { }
    }

    /** 圆角纯色背景（半径直接给 px） */
    android.graphics.drawable.GradientDrawable roundRect(int color, int radPx, int unusedH) {
        android.graphics.drawable.GradientDrawable g =
                new android.graphics.drawable.GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radPx);
        return g;
    }

    /** 细进度条：2dp 半透明轨道 + 粉色进度 + 小圆点，整条压在半透明背景上不抢画面 */
    void styleSeek(SeekBar s) {
        int track = dp(2);
        int vh = dp(20);
        android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();
        bg.setColor(0x30FFFFFF);
        bg.setCornerRadius(track / 2f);

        android.graphics.drawable.GradientDrawable pg =
                new android.graphics.drawable.GradientDrawable();
        pg.setColor(0xE6FB7299);
        pg.setCornerRadius(track / 2f);

        android.graphics.drawable.ClipDrawable clip =
                new android.graphics.drawable.ClipDrawable(pg, Gravity.LEFT,
                        android.graphics.drawable.ClipDrawable.HORIZONTAL);
        android.graphics.drawable.LayerDrawable ld =
                new android.graphics.drawable.LayerDrawable(
                        new android.graphics.drawable.Drawable[]{bg, clip});
        ld.setId(0, android.R.id.background);
        ld.setId(1, android.R.id.progress);
        int inset = (vh - track) / 2;
        ld.setLayerInset(0, 0, inset, 0, inset);
        ld.setLayerInset(1, 0, inset, 0, inset);
        s.setProgressDrawable(ld);

        android.graphics.drawable.GradientDrawable th =
                new android.graphics.drawable.GradientDrawable();
        th.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        th.setColor(0xF2FFFFFF);
        th.setSize(dp(9), dp(9));
        s.setThumb(th);
        s.setThumbOffset(0);
        s.setSplitTrack(false);
        s.setPadding(0, 0, 0, 0);
        s.setMinimumHeight(vh);
        s.setBackground(null);
    }

    /** 毫秒 → mm:ss */
    static String mmss(int ms) {
        if (ms < 0) ms = 0;
        int total = ms / 1000;
        int m = total / 60, sec = total % 60;
        if (m >= 60) return (m / 60) + ":" + String.format("%02d:%02d", m % 60, sec);
        return String.format("%02d:%02d", m, sec);
    }

    // ------------------------------------------------------------ 【v4.19.0】B 站竖屏沉浸式界面

    /**
     * 竖屏播放页按 B 站竖屏样式重排（原来是把横屏那套「顶部五个图标 + 底部工具条」照搬过来）：
     *
     *   右侧竖排：头像（带 + 关注徽标）/ 点赞 / 投币 / 收藏 / 评论 / 分享
     *   底部：标题、UP 主，再一行 播放 · 时间 · 弹幕开关 · 弹幕输入框 · 选集 · 倍速 · 全屏
     *   最底：一条 2dp 细进度条（贴着屏幕底边）
     *   顶部：只留返回 + 更多，标题让位给画面
     *
     * 【为什么挂在 overlay 上】翻页时 slotCur 整体位移，而控件必须钉在屏幕上不动，
     * 这正是 B 站/抖音的做法：画面跟手走，控件原地不动。
     */
    void buildImmersiveUi() {
        // ---------------- 右侧竖排互动 ----------------
        vRail = new LinearLayout(this);
        vRail.setOrientation(LinearLayout.VERTICAL);
        vRail.setGravity(Gravity.CENTER_HORIZONTAL);
        vRail.setVisibility(View.GONE);

        // 头像 + 「+」关注徽标（徽标压在头像下沿）
        FrameLike avBox = new FrameLike(this);
        vRail.addView(avBox, new LinearLayout.LayoutParams(dp(66), dp(64)));

        vAvatar = new ImageView(this);
        vAvatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        android.graphics.drawable.GradientDrawable ring =
                new android.graphics.drawable.GradientDrawable();
        ring.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        ring.setColor(0xFF2A2E36);
        ring.setStroke(dp(2), 0xFFFFFFFF);
        vAvatar.setBackground(ring);
        vAvatar.setClipToOutline(true);
        FrameLike.LayoutParams avp = new FrameLike.LayoutParams(
                dp(50), dp(50), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        avp.topMargin = dp(3);
        avBox.addView(vAvatar, avp);

        vFollowBadge = new TextView(this);
        vFollowBadge.setText("+");
        vFollowBadge.setTextColor(Color.WHITE);
        vFollowBadge.setTextSize(13);
        vFollowBadge.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        vFollowBadge.setGravity(Gravity.CENTER);
        vFollowBadge.setBackground(roundRect(0xFFFB7299, dp(11), dp(22)));
        FrameLike.LayoutParams fbp = new FrameLike.LayoutParams(
                dp(22), dp(22), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        avBox.addView(vFollowBadge, fbp);

        View.OnClickListener followClick = new View.OnClickListener() {
            @Override public void onClick(View v) { tapFollow(); }
        };
        vFollowBadge.setOnClickListener(followClick);
        vAvatar.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { goUpHome(); }
        });

        actLikeRail = makeAct("like", "like_on", 28, false);
        actCmtRail = makeAct("comment", "comment", 28, false);
        actCoinRail = makeAct("coin", "coin", 28, false);
        actFavRail = makeAct("star", "star_on", 28, false);
        actShareRail = makeAct("share", "share", 28, false);
        addRailAct(actLikeRail);
        addRailAct(actCmtRail);
        addRailAct(actCoinRail);
        addRailAct(actFavRail);
        addRailAct(actShareRail);

        actLikeRail.box.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { tapLike(); }
        });
        actLikeRail.box.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) { tripleLike(); return true; }
        });
        actCmtRail.box.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openComments(); }
        });
        actCoinRail.box.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { tapCoin(); }
        });
        actFavRail.box.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { tapFav(); }
        });
        actShareRail.box.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { tapShare(); }
        });

        FrameLike.LayoutParams railLp = new FrameLike.LayoutParams(
                dp(66), ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.RIGHT | Gravity.BOTTOM);
        railLp.rightMargin = dp(2);
        railLp.bottomMargin = dp(120);    // 抬到底部信息条上方
        overlay.addView(vRail, railLp);

        // ---------------- 底部：标题 / UP 主 / 工具行 ----------------
        vBottom = new LinearLayout(this);
        vBottom.setOrientation(LinearLayout.VERTICAL);
        vBottom.setPadding(dp(14), dp(8), dp(14), dp(4));
        vBottom.setBackground(new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xA6000000, 0x00000000}));
        vBottom.setVisibility(View.GONE);

        vTitleTxt = new TextView(this);
        vTitleTxt.setTextColor(Color.WHITE);
        vTitleTxt.setTextSize(15);
        vTitleTxt.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        vTitleTxt.setMaxLines(2);
        vTitleTxt.setEllipsize(android.text.TextUtils.TruncateAt.END);
        vTitleTxt.setLineSpacing(dp(3), 1f);
        vTitleTxt.setShadowLayer(dp(4), 0, dp(1), 0xB3000000);
        vBottom.addView(vTitleTxt, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        vUpTxt = new TextView(this);
        vUpTxt.setTextColor(0xFFE3E6EA);
        vUpTxt.setTextSize(12);
        vUpTxt.setSingleLine(true);
        vUpTxt.setEllipsize(android.text.TextUtils.TruncateAt.END);
        vUpTxt.setShadowLayer(dp(3), 0, dp(1), 0x99000000);
        LinearLayout.LayoutParams vulp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vulp.topMargin = dp(5);
        vBottom.addView(vUpTxt, vulp);
        vUpTxt.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { goUpHome(); }
        });

        LinearLayout vTools = new LinearLayout(this);
        vTools.setOrientation(LinearLayout.HORIZONTAL);
        vTools.setGravity(Gravity.CENTER_VERTICAL);

        vPlay = Ic.view(this, "pause", Color.WHITE, 22);
        vPlay.setPadding(dp(3), dp(3), dp(3), dp(3));
        vTools.addView(vPlay, new LinearLayout.LayoutParams(dp(30), dp(30)));
        vPlay.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { togglePlay(); }
        });

        vTime = new TextView(this);
        vTime.setText("00:00");
        vTime.setTextColor(0xE6FFFFFF);
        vTime.setTextSize(10);
        vTime.setTypeface(android.graphics.Typeface.MONOSPACE);
        LinearLayout.LayoutParams vtlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vtlp.leftMargin = dp(2);
        vTools.addView(vTime, vtlp);

        vDmSwitch = new DmSwitch(this);
        LinearLayout.LayoutParams dsw = new LinearLayout.LayoutParams(dp(32), dp(28));
        dsw.leftMargin = dp(6);
        vTools.addView(vDmSwitch, dsw);
        vDmSwitch.setClickable(true);
        vDmSwitch.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleDanmaku(); }
        });
        vDmSwitch.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) { showDmStylePanel(); return true; }
        });

        vDmHint = new TextView(this);
        vDmHint.setText("发个友善的弹幕见证当下");
        vDmHint.setTextColor(0xCCFFFFFF);
        vDmHint.setTextSize(11);
        vDmHint.setSingleLine(true);
        vDmHint.setEllipsize(android.text.TextUtils.TruncateAt.END);
        vDmHint.setGravity(Gravity.CENTER_VERTICAL);
        vDmHint.setPadding(dp(12), 0, dp(12), 0);
        vDmHint.setBackground(roundRect(0x33FFFFFF, dp(15), dp(30)));
        LinearLayout.LayoutParams dhp = new LinearLayout.LayoutParams(0, dp(30), 1f);
        dhp.leftMargin = dp(6);
        dhp.rightMargin = dp(6);
        vTools.addView(vDmHint, dhp);
        vDmHint.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showDmPanel(); }
        });

        vPick = textTool("选集", new View.OnClickListener() {
            @Override public void onClick(View v) { showPages(); }
        });
        vPick.setTextSize(11);
        vTools.addView(vPick);

        vSpeed = textTool("倍速", new View.OnClickListener() {
            @Override public void onClick(View v) { showSpeed(); }
        });
        vSpeed.setTextSize(11);
        vTools.addView(vSpeed);

        vFull = Ic.view(this, "full", Color.WHITE, 20);
        vFull.setPadding(dp(4), dp(4), dp(4), dp(4));
        LinearLayout.LayoutParams vflp = new LinearLayout.LayoutParams(dp(32), dp(32));
        vflp.leftMargin = dp(2);
        vTools.addView(vFull, vflp);
        vFull.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleFullscreen(); }
        });

        LinearLayout.LayoutParams vtl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vtl.topMargin = dp(6);
        vBottom.addView(vTools, vtl);

        FrameLike.LayoutParams vbLp = new FrameLike.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        vbLp.bottomMargin = dp(14);
        overlay.addView(vBottom, vbLp);

        // ---------------- 底部细进度条 ----------------
        vSeek = new SeekBar(this);
        styleSeek(vSeek);
        FrameLike.LayoutParams vsk = new FrameLike.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(16), Gravity.BOTTOM);
        vsk.leftMargin = dp(10);
        vsk.rightMargin = dp(10);
        vsk.bottomMargin = dp(1);
        overlay.addView(vSeek, vsk);
        vSeek.setVisibility(View.GONE);
        vSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (!fromUser) return;
                doSeek(p);
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });

        // ---------------- 右上角「更多」 ----------------
        vTopMore = Ic.view(this, "more", Color.WHITE, 20);
        vTopMore.setPadding(dp(8), dp(8), dp(8), dp(8));
        android.graphics.drawable.GradientDrawable mbg =
                new android.graphics.drawable.GradientDrawable();
        mbg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        mbg.setColor(0x59000000);
        vTopMore.setBackground(mbg);
        vTopMore.setVisibility(View.GONE);
        FrameLike.LayoutParams mlp = new FrameLike.LayoutParams(
                dp(36), dp(36), Gravity.TOP | Gravity.RIGHT);
        mlp.topMargin = dp(12);
        mlp.rightMargin = dp(10);
        overlay.addView(vTopMore, mlp);
        vTopMore.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showMoreMenu(); }
        });
    }

    /** 右侧互动项：固定 66dp 宽、垂直居中，文字加阴影保证亮画面上可读 */
    void addRailAct(Act a) {
        if (a == null) return;
        a.label.setTextSize(10.5f);
        a.label.setShadowLayer(dp(3), 0, dp(1), 0x99000000);
        a.icon.setPadding(dp(2), dp(2), dp(2), dp(2));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                dp(66), ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(9);
        vRail.addView(a.box, lp);
    }

    /** 沉浸式控件整组显隐（单击画面切换） */
    void setImmersiveUiVisible(boolean on) {
        vUiVisible = on;
        int vis = on ? View.VISIBLE : View.GONE;
        try {
            if (vRail != null) vRail.setVisibility(vis);
            if (vBottom != null) vBottom.setVisibility(vis);
            if (vSeek != null) vSeek.setVisibility(vis);
            if (vTopMore != null) vTopMore.setVisibility(vis);
            if (topBar != null) topBar.setVisibility(vis);
        } catch (Throwable ignore) { }
    }

    /** 进入/退出沉浸式界面时把整套控件一起开关，避免和横屏那套残影打架 */
    void showImmersiveUi(boolean on) {
        if (on) {
            setImmersiveUiVisible(true);
            if (ctrlBar != null) ctrlBar.setVisibility(View.GONE);
        } else {
            int vis = View.GONE;
            try {
                if (vRail != null) vRail.setVisibility(vis);
                if (vBottom != null) vBottom.setVisibility(vis);
                if (vSeek != null) vSeek.setVisibility(vis);
                if (vTopMore != null) vTopMore.setVisibility(vis);
                if (ctrlBar != null) ctrlBar.setVisibility(View.VISIBLE);
            } catch (Throwable ignore) { }
        }
    }

    void buildUi() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(MainActivity.C_BG);

        videoHeight = dp(230);
        holder = new FrameLike(this);
        holder.setBackgroundColor(Color.BLACK);

        // 竖屏视频全屏时的背景（封面铺满 + 压暗，替代大片黑边）
        bgCover = new ImageView(this);
        bgCover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        bgCover.setAlpha(0.32f);
        bgCover.setVisibility(View.GONE);
        holder.addView(bgCover, new FrameLike.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 【v4.9】画面承载换成 TextureView（TxVideoView 封装）。
        // 旧版 VideoView 内部是 SurfaceView，独立图层，父容器位移时画面不跟随，
        // 无法做抖音式「跟手上下滑」；TextureView 画在普通 View 层级里，可自由做变换。
        video = new TxVideoView(this);
        // 【v4.17.1】画面容器被摘下前收下真实进度与播放状态：
        // 只要还能保住 Surface 就不需要用到；一旦真的重建了 Surface，
        // 也能从这个断点接续，而不是从 0 秒重新播放。
        video.setOnSurfaceLostListener(new TxVideoView.OnSurfaceLost() {
            @Override public void onSurfaceLost(int pos, boolean playing) {
                try {
                    if (swipeDestroyed || isFinishing()) return;
                    if (pos > 800) resumePos = pos;
                    surfaceLostPaused = !playing;
                } catch (Throwable ignore) { }
            }
        });

        // 【v4.9 竖屏翻页】swipeLayer 是「双槽容器」：
        //   slotCur  = 当前视频（画面 + 弹幕），随手指上下位移
        //   slotNext = 相邻视频槽（封面 + 标题），跟手跟进
        // 两槽高度都等于屏高、上下相邻（相距恰好一个屏高）→ 视觉上是连续翻页，
        // 而非两张图硬切。slotNext 的 z 序在 slotCur 之上，切换完成时它正好
        // 变成新视频的「封面海报」，等新视频首帧渲染出来再淡出，全程不黑屏。
        //
        // 【高度必须显式给】holder 在普通详情页是 WRAP_CONTENT 高度，
        // 若这里写 MATCH_PARENT 会被解析成 0 高（WRAP_CONTENT 父容器没有确定尺寸），
        // 视频就整个消失了。所以按普通态给 videoHeight，竖屏铺满时再改成 MATCH_PARENT。
        //
        // 【swipeLayer 永远保持 VISIBLE】它是 slotCur 的父容器，一旦 GONE，
        // 里面的画面会跟着一起消失（退出竖屏铺满时画面直接空白）。
        // 需要隐藏的只有 slotNext（相邻槽），它单独控制显隐。
        swipeLayer = new FrameLike(this);
        swipeLayer.setClipChildren(true);
        holder.addView(swipeLayer, new FrameLike.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, videoHeight));

        // 当前槽（画面 + 弹幕）—— 先建，z 序在下
        slotCur = new FrameLike(this);
        swipeLayer.addView(slotCur, new FrameLike.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 相邻槽（封面 + 标题）—— 后建，z 序在上
        // 初始 GONE：非翻页状态下它不能挡住画面（它是黑底不透明的）
        // 【两个相邻槽】上滑看下一个（slotNext 在下方）、下滑看上一个（slotPrev 在上方），
        // 分成两个槽就不会出现「中途跳到另一边」的闪烁。
        slotNext = new FrameLike(this);
        slotNext.setVisibility(View.GONE);
        swipeLayer.addView(slotNext, new FrameLike.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        slotNextCover = new ImageView(this);
        slotNextCover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        slotNextCover.setBackgroundColor(Color.BLACK);
        slotNextCover.setVisibility(View.GONE);
        slotNext.addView(slotNextCover, new FrameLike.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        slotNextTitle = new TextView(this);
        slotNextTitle.setTextColor(Color.WHITE);
        slotNextTitle.setTextSize(13);
        slotNextTitle.setMaxLines(2);
        slotNextTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        slotNextTitle.setShadowLayer(dp(4), 0, dp(1), Color.BLACK);
        slotNextTitle.setVisibility(View.GONE);
        FrameLike.LayoutParams sntlp = new FrameLike.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        sntlp.leftMargin = dp(14);
        sntlp.rightMargin = dp(14);
        sntlp.bottomMargin = dp(120);
        slotNext.addView(slotNextTitle, sntlp);

        // 上一槽（下滑时从上方进入）
        slotPrev = new FrameLike(this);
        slotPrev.setVisibility(View.GONE);
        swipeLayer.addView(slotPrev, new FrameLike.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        slotPrevCover = new ImageView(this);
        slotPrevCover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        slotPrevCover.setBackgroundColor(Color.BLACK);
        slotPrevCover.setVisibility(View.GONE);
        slotPrev.addView(slotPrevCover, new FrameLike.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        slotPrevTitle = new TextView(this);
        slotPrevTitle.setTextColor(Color.WHITE);
        slotPrevTitle.setTextSize(13);
        slotPrevTitle.setMaxLines(2);
        slotPrevTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        slotPrevTitle.setShadowLayer(dp(4), 0, dp(1), Color.BLACK);
        slotPrevTitle.setVisibility(View.GONE);
        FrameLike.LayoutParams sptlp = new FrameLike.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        sptlp.leftMargin = dp(14);
        sptlp.rightMargin = dp(14);
        sptlp.bottomMargin = dp(120);
        slotPrev.addView(slotPrevTitle, sptlp);

        slotCur.addView(video, new FrameLike.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // VLC 软解引擎已移除（libvlc.so 占 19MB，为把 APK 压到 10MB 内删除），
        // 这里保留一个隐藏的 SurfaceView 占位，避免布局索引与既有尺寸计算改动。
        vlcSurface = new SurfaceView(this);
        vlcSurface.setVisibility(View.GONE);
        holder.addView(vlcSurface, new FrameLike.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, videoHeight));

        // 弹幕层（在画面之上、控制层之下；不拦截触摸）
        // 【v4.9】弹幕层移入 slotCur —— 翻页时它要和画面一起位移，否则弹幕会「浮」在原地。
        dmView = new DanmakuView(this);
        dmView.setClickable(false);
        dmView.setFocusable(false);
        // 【v4.8】应用用户保存的弹幕样式；【v4.10.6】含速度/区域/密度
        applyDmStyle();
        dmView.setVisibility(dmOn ? View.VISIBLE : View.GONE);
        slotCur.addView(dmView, new FrameLike.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 切视频「翻书」动画遮罩：盖在画面之上、控制层之下，做 3D 翻页
        flipCover = new ImageView(this);
        flipCover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        flipCover.setVisibility(View.GONE);
        flipCover.setClickable(false);
        flipCover.setBackgroundColor(Color.BLACK);
        holder.addView(flipCover, new FrameLike.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, videoHeight));

        // 手势/提示浮层（FrameLayout，全屏时控制条也挂到这里）
        overlay = new FrameLike(this);

        // ---------------- 顶部栏：返回 + 标题 + 互动图标（参照 B 站横屏播放器） ----------------
        topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.VERTICAL);
        topBar.setBackground(new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xCC000000, 0x66000000, 0x00000000}));

        LinearLayout rowA = new LinearLayout(this);
        rowA.setOrientation(LinearLayout.HORIZONTAL);
        rowA.setGravity(Gravity.CENTER_VERTICAL);
        rowA.setPadding(dp(6), dp(6), dp(6), dp(6));

        backArrow = Ic.view(this, "back", Color.WHITE, 22);
        backArrow.setPadding(dp(8), dp(8), dp(8), dp(8));
        rowA.addView(backArrow, new LinearLayout.LayoutParams(dp(38), dp(38)));
        backArrow.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { goBack(); }
        });

        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);

        titleTop = new TextView(this);
        titleTop.setText("加载中…");
        titleTop.setTextColor(Color.WHITE);
        titleTop.setTextSize(13);
        titleTop.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        titleTop.setSingleLine(true);
        titleTop.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titleTop.setShadowLayer(dp(4), 0, dp(1), 0x99000000);
        titleCol.addView(titleTop, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        watchText = new TextView(this);
        watchText.setText("");
        watchText.setTextColor(0xB3FFFFFF);
        watchText.setTextSize(10);
        watchText.setSingleLine(true);
        watchText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams wtp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wtp.topMargin = dp(2);
        titleCol.addView(watchText, wtp);

        LinearLayout.LayoutParams tcp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tcp.leftMargin = dp(2);
        tcp.rightMargin = dp(2);
        rowA.addView(titleCol, tcp);
        titleColRef = titleCol;

        LinearLayout actRow = new LinearLayout(this);
        actRow.setOrientation(LinearLayout.HORIZONTAL);
        actRow.setGravity(Gravity.CENTER_VERTICAL);
        actLikeTop = makeAct("like", "like_on", 22, true);
        actCmtTop = makeAct("comment", "comment", 22, true);
        actCoinTop = makeAct("coin", "coin", 22, true);
        actFavTop = makeAct("star", "star_on", 22, true);
        actShareTop = makeAct("share", "share", 22, true);
        addAct(actRow, actLikeTop);
        addAct(actRow, actCmtTop);
        addAct(actRow, actCoinTop);
        addAct(actRow, actFavTop);
        addAct(actRow, actShareTop);
        actLikeTop.box.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { tapLike(); }
        });
        actLikeTop.box.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) { tripleLike(); return true; }
        });
        actCmtTop.box.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openComments(); }
        });
        actCoinTop.box.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { tapCoin(); }
        });
        actFavTop.box.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { tapFav(); }
        });
        actShareTop.box.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { tapShare(); }
        });
        rowA.addView(actRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        actRowRef = actRow;
        topBar.addView(rowA, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // UP 主 + 关注（全屏时展示，竖屏用下方信息卡）
        upRow = new LinearLayout(this);
        upRow.setOrientation(LinearLayout.HORIZONTAL);
        upRow.setGravity(Gravity.CENTER_VERTICAL);
        upRow.setPadding(dp(14), 0, dp(14), dp(4));
        upRow.setVisibility(View.GONE);

        upAvatarTop = new ImageView(this);
        upAvatarTop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        android.graphics.drawable.GradientDrawable avBg =
                new android.graphics.drawable.GradientDrawable();
        avBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        avBg.setColor(ImgLoader.placeholderColor());
        upAvatarTop.setBackground(avBg);
        upAvatarTop.setClipToOutline(true);
        upRow.addView(upAvatarTop, new LinearLayout.LayoutParams(dp(26), dp(26)));

        followBtnTop = new Button(this);
        followBtnTop.setText("+ 关注");
        followBtnTop.setTextColor(Color.WHITE);
        followBtnTop.setTextSize(11);
        followBtnTop.setAllCaps(false);
        followBtnTop.setPadding(dp(14), 0, dp(14), 0);
        followBtnTop.setBackground(roundRect(0x33FFFFFF, dp(14), dp(28)));
        LinearLayout.LayoutParams fbt = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(28));
        fbt.leftMargin = dp(8);
        upRow.addView(followBtnTop, fbt);
        followBtnTop.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { tapFollow(); }
        });
        topBar.addView(upRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLike.LayoutParams topLp = new FrameLike.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP);
        overlay.addView(topBar, topLp);

        // 倍速提示：小胶囊 + 半透明，不挡画面
        speedTag = new TextView(this);
        speedTag.setText("2× 快进中");
        speedTag.setTextColor(Color.WHITE);
        speedTag.setTextSize(12);
        speedTag.setGravity(Gravity.CENTER);
        android.graphics.drawable.GradientDrawable sbg =
                new android.graphics.drawable.GradientDrawable();
        sbg.setColor(Color.argb(85, 0, 0, 0));
        sbg.setCornerRadius(20 * getResources().getDisplayMetrics().density);
        speedTag.setBackground(sbg);
        speedTag.setPadding(dp(12), dp(5), dp(12), dp(5));
        speedTag.setVisibility(View.INVISIBLE);
        overlay.addView(speedTag, new FrameLike.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));

        // 音量/亮度 HUD
        hud = new TextView(this);
        hud.setTextColor(Color.WHITE);
        hud.setTextSize(11);
        hud.setGravity(Gravity.CENTER);
        android.graphics.drawable.GradientDrawable hbg =
                new android.graphics.drawable.GradientDrawable();
        hbg.setColor(Color.argb(110, 0, 0, 0));
        hbg.setCornerRadius(12 * getResources().getDisplayMetrics().density);
        hud.setBackground(hbg);
        hud.setPadding(dp(12), dp(6), dp(12), dp(6));
        hud.setVisibility(View.GONE);
        overlay.addView(hud, new FrameLike.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));

        zoomTag = new TextView(this);
        zoomTag.setTextColor(Color.WHITE);
        zoomTag.setTextSize(14);
        zoomTag.setBackgroundColor(Color.argb(110, 0, 0, 0));
        zoomTag.setPadding(dp(10), dp(4), dp(10), dp(4));
        zoomTag.setVisibility(View.GONE);
        FrameLike.LayoutParams zp = new FrameLike.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        zp.topMargin = dp(16);
        overlay.addView(zoomTag, zp);

        // 竖屏视频铺满模式：右上角「退出铺满」按钮
        exitFillBtn = new TextView(this);
        exitFillBtn.setText("退出铺满");
        exitFillBtn.setTextColor(Color.WHITE);
        exitFillBtn.setTextSize(11);
        exitFillBtn.setGravity(Gravity.CENTER);
        android.graphics.drawable.GradientDrawable ebg =
                new android.graphics.drawable.GradientDrawable();
        ebg.setColor(Color.argb(150, 0, 0, 0));
        ebg.setCornerRadius(dp(13));
        exitFillBtn.setBackground(ebg);
        exitFillBtn.setPadding(dp(9), dp(5), dp(9), dp(5));
        exitFillBtn.setVisibility(View.GONE);
        FrameLike.LayoutParams efp = new FrameLike.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.RIGHT);
        efp.topMargin = dp(56);
        efp.rightMargin = dp(10);
        overlay.addView(exitFillBtn, efp);
        exitFillBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { exitPortraitFill(); }
        });

        // 竖屏视频全屏：左侧信息面板（填补左右留白）
        portraitInfo = new LinearLayout(this);
        portraitInfo.setOrientation(LinearLayout.VERTICAL);
        portraitInfo.setVisibility(View.GONE);
        portraitInfo.setPadding(dp(4), 0, dp(4), 0);

        pTitle = new TextView(this);
        pTitle.setTextColor(Color.WHITE);
        pTitle.setTextSize(15);
        pTitle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        pTitle.setMaxLines(5);
        pTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        pTitle.setLineSpacing(dp(3), 1f);
        pTitle.setShadowLayer(dp(4), 0, dp(1), Color.BLACK);
        portraitInfo.addView(pTitle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        pUp = new TextView(this);
        pUp.setTextColor(Color.parseColor("#cfd3da"));
        pUp.setTextSize(12);
        pUp.setMaxLines(1);
        pUp.setEllipsize(android.text.TextUtils.TruncateAt.END);
        pUp.setShadowLayer(dp(3), 0, dp(1), Color.BLACK);
        LinearLayout.LayoutParams pup = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pup.topMargin = dp(10);
        portraitInfo.addView(pUp, pup);

        FrameLike.LayoutParams pip = new FrameLike.LayoutParams(
                dp(190), ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.LEFT | Gravity.CENTER_VERTICAL);
        pip.leftMargin = dp(16);
        overlay.addView(portraitInfo, pip);

        // 发弹幕窗口（顶部滑出，避免和底部控制条、键盘打架）
        dmPanel = new LinearLayout(this);
        dmPanel.setOrientation(LinearLayout.HORIZONTAL);
        dmPanel.setGravity(Gravity.CENTER_VERTICAL);
        android.graphics.drawable.GradientDrawable dpbg =
                new android.graphics.drawable.GradientDrawable();
        dpbg.setColor(Color.argb(238, 31, 34, 41));
        dpbg.setCornerRadius(dp(12));
        dmPanel.setBackground(dpbg);
        dmPanel.setPadding(dp(8), dp(7), dp(8), dp(7));
        dmPanel.setVisibility(View.GONE);

        dmInput = new EditText(this);
        dmInput.setHint("发个友善的弹幕吧…");
        dmInput.setHintTextColor(Color.parseColor("#6f7581"));
        dmInput.setTextColor(Color.WHITE);
        dmInput.setTextSize(13);
        dmInput.setSingleLine(true);
        dmInput.setPadding(dp(10), dp(6), dp(10), dp(6));
        android.graphics.drawable.GradientDrawable ibg =
                new android.graphics.drawable.GradientDrawable();
        ibg.setColor(ImgLoader.placeholderColor());
        ibg.setCornerRadius(dp(9));
        dmInput.setBackground(ibg);
        dmPanel.addView(dmInput, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button dmSend = new Button(this);
        dmSend.setText("发送");
        dmSend.setTextColor(Color.WHITE);
        dmSend.setTextSize(12);
        dmSend.setAllCaps(false);
        dmSend.setBackgroundColor(MainActivity.C_ACC);
        LinearLayout.LayoutParams dsp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dsp.leftMargin = dp(7);
        dmPanel.addView(dmSend, dsp);
        dmSend.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { sendDanmaku(); }
        });

        Button dmClose = new Button(this);
        dmClose.setText("×");
        dmClose.setTextColor(MainActivity.C_SUB);
        dmClose.setTextSize(14);
        dmClose.setAllCaps(false);
        dmClose.setBackgroundColor(Color.TRANSPARENT);
        dmClose.setPadding(dp(8), 0, dp(8), 0);
        dmPanel.addView(dmClose);
        dmClose.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { hideDmPanel(); }
        });

        FrameLike.LayoutParams dmp = new FrameLike.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP);
        dmp.leftMargin = dp(12);
        dmp.rightMargin = dp(12);
        dmp.topMargin = dp(92);
        overlay.addView(dmPanel, dmp);

        tipText = new TextView(this);
        tipText.setTextColor(Color.WHITE);
        tipText.setTextSize(12);
        tipText.setGravity(Gravity.CENTER);
        tipText.setPadding(20, 10, 20, 10);
        FrameLike.LayoutParams ttp = new FrameLike.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        ttp.topMargin = dp(56);
        overlay.addView(tipText, ttp);

        // ---------------- 【v3.9】加载动画：跳舞的小电视 + 正在加载喵~ ----------------
        buildLoadAnim(overlay);

        holder.addView(overlay, new FrameLike.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, videoHeight));

        // 【v4.9】竖屏上下滑翻页层（抖音式）的双槽结构已在 buildUi 开头建好
        // （swipeLayer > slotNext + slotCur），此处不再重复创建。

        root.addView(holder, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---------------- 控制条（参照 B 站：时间行 / 细进度条 / 工具行） ----------------
        ctrlBar = new LinearLayout(this);
        ctrlBar.setOrientation(LinearLayout.VERTICAL);
        ctrlBar.setBackgroundColor(0xE60B0D10);
        ctrlBar.setPadding(dp(12), dp(7), dp(12), dp(5));

        // 第一行：时间 + 更多
        LinearLayout timeRow = new LinearLayout(this);
        timeRow.setOrientation(LinearLayout.HORIZONTAL);
        timeRow.setGravity(Gravity.CENTER_VERTICAL);

        timeText = new TextView(this);
        timeText.setText("00:00/00:00");
        timeText.setTextColor(Color.WHITE);
        timeText.setTextSize(10);
        timeText.setTypeface(android.graphics.Typeface.MONOSPACE);
        timeRow.addView(timeText, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        qualityBtn = textTool(qualityShort(Prefs.quality(this)), new View.OnClickListener() {
            @Override public void onClick(View v) { showQualityDialog(); }
        });
        qualityBtn.setTextColor(0xD9FFFFFF);
        timeRow.addView(qualityBtn);

        // 【v4.9 解码能力】本视频提供了哪些编码 / 哪些本机能解，点开看清单。
        // 这解决了「为什么我选了 4K 却只有 1080P」这类看不懂的问题 ——
        // 其实是本机解码器不支持那条高码率轨道。
        codecBtn = textTool("解码", new View.OnClickListener() {
            @Override public void onClick(View v) { showCodecDialog(); }
        });
        codecBtn.setTextColor(0xB3FFFFFF);
        codecBtn.setTextSize(11);
        LinearLayout.LayoutParams cbp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cbp.leftMargin = dp(6);
        timeRow.addView(codecBtn, cbp);
        codecBtn.setVisibility(View.GONE);

        moreBtn = Ic.view(this, "more", Color.WHITE, 18);
        moreBtn.setPadding(dp(6), dp(2), dp(2), dp(2));
        timeRow.addView(moreBtn, new LinearLayout.LayoutParams(dp(30), dp(24)));
        moreBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showMoreMenu(); }
        });
        ctrlBar.addView(timeRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 第二行：细进度条
        seek = new SeekBar(this);
        styleSeek(seek);
        LinearLayout.LayoutParams sk = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(18));
        sk.topMargin = dp(1);
        ctrlBar.addView(seek, sk);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (!fromUser) return;
                doSeek(p);
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });

        // 第三行：播放 / 下一集 / 弹幕 / 输入框 / 选集 / 倍速 / 清晰度 / 全屏
        LinearLayout tools = new LinearLayout(this);
        tools.setOrientation(LinearLayout.HORIZONTAL);
        tools.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams toolsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        toolsLp.topMargin = dp(4);

        playIcon = Ic.view(this, "pause", Color.WHITE, 24);
        playIcon.setPadding(dp(4), dp(4), dp(4), dp(4));
        tools.addView(playIcon, new LinearLayout.LayoutParams(dp(32), dp(32)));
        playIcon.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { togglePlay(); }
        });

        nextBtn = Ic.view(this, "next", Color.WHITE, 20);
        nextBtn.setPadding(dp(4), dp(4), dp(4), dp(4));
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(dp(30), dp(30));
        nlp.leftMargin = dp(2);
        tools.addView(nextBtn, nlp);
        nextBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { nextPage(); }
        });

        dmSwitch = new DmSwitch(this);
        LinearLayout.LayoutParams dmlp = new LinearLayout.LayoutParams(dp(34), dp(30));
        dmlp.leftMargin = dp(6);
        tools.addView(dmSwitch, dmlp);
        dmSwitch.setClickable(true);
        dmSwitch.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleDanmaku(); }
        });
        // 【v4.8】长按「弹」打开弹幕样式设置（颜色/透明度/字号）
        dmSwitch.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                showDmStylePanel();
                return true;
            }
        });

        dmHint = new TextView(this);
        dmHint.setText("发个友善的弹幕见证当下");
        dmHint.setTextColor(0x99FFFFFF);
        dmHint.setTextSize(11);
        dmHint.setSingleLine(true);
        dmHint.setEllipsize(android.text.TextUtils.TruncateAt.END);
        dmHint.setGravity(Gravity.CENTER_VERTICAL);
        dmHint.setPadding(dp(12), 0, dp(12), 0);
        dmHint.setBackground(roundRect(0x26FFFFFF, dp(15), dp(30)));
        LinearLayout.LayoutParams dhp = new LinearLayout.LayoutParams(0, dp(30), 1f);
        dhp.leftMargin = dp(7);
        dhp.rightMargin = dp(7);
        tools.addView(dmHint, dhp);
        dmHint.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showDmPanel(); }
        });

        pickBtn = textTool("选集", new View.OnClickListener() {
            @Override public void onClick(View v) { showPages(); }
        });
        tools.addView(pickBtn);

        speedBtn = textTool("倍速", new View.OnClickListener() {
            @Override public void onClick(View v) { showSpeed(); }
        });
        tools.addView(speedBtn);

        fullIcon = Ic.view(this, "full", Color.WHITE, 22);
        fullIcon.setPadding(dp(4), dp(4), dp(4), dp(4));
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(dp(34), dp(34));
        flp.leftMargin = dp(4);
        tools.addView(fullIcon, flp);
        fullIcon.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleFullscreen(); }
        });

        ctrlBar.addView(tools, toolsLp);

        root.addView(ctrlBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 【v4.19.0】B 站竖屏沉浸式界面（右侧互动栏 / 底部信息条 / 细进度条 / 右上更多）
        buildImmersiveUi();

        // ---------------- 视频信息 + 互动栏（竖屏，参照 B 站详情页） ----------------
        infoWrap = new LinearLayout(this);
        infoWrap.setOrientation(LinearLayout.VERTICAL);
        infoWrap.setBackgroundColor(MainActivity.C_BG);
        infoWrap.setPadding(dp(14), dp(10), dp(14), dp(4));

        // 【v4.16.1】简介 / 评论 分段（对应 B 站详情页右栏顶部）
        LinearLayout tabRow = new LinearLayout(this);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        tabRow.setPadding(dp(14), dp(6), dp(14), dp(2));
        TextView tabIntro = new TextView(this);
        tabIntro.setText("简介");
        tabIntro.setTextColor(MainActivity.C_ACC);
        tabIntro.setTextSize(13);
        tabIntro.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        tabRow.addView(tabIntro, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        tabComment = new TextView(this);
        tabComment.setText("评论 0");
        tabComment.setTextColor(MainActivity.C_SUB);
        tabComment.setTextSize(13);
        tabRow.addView(tabComment, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        infoWrap.addView(tabRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // UP 主 + 关注
        LinearLayout upLine = new LinearLayout(this);
        upLine.setOrientation(LinearLayout.HORIZONTAL);
        upLine.setGravity(Gravity.CENTER_VERTICAL);

        upAvatarBot = new ImageView(this);
        upAvatarBot.setScaleType(ImageView.ScaleType.CENTER_CROP);
        android.graphics.drawable.GradientDrawable ab2 =
                new android.graphics.drawable.GradientDrawable();
        ab2.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        ab2.setColor(0xFF2A2E36);
        upAvatarBot.setBackground(ab2);
        upAvatarBot.setClipToOutline(true);
        upLine.addView(upAvatarBot, new LinearLayout.LayoutParams(dp(34), dp(34)));

        TextView upCol = new TextView(this);
        upCol.setText("UP 主");
        upCol.setTextColor(Color.parseColor("#FB7299"));
        upCol.setTextSize(12);
        upCol.setSingleLine(true);
        upCol.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams ucp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        ucp.leftMargin = dp(9);
        upLine.addView(upCol, ucp);
        upNameTop = upCol;

        followBtnBot = new Button(this);
        followBtnBot.setText("+ 关注");
        followBtnBot.setTextColor(Color.WHITE);
        followBtnBot.setTextSize(11);
        followBtnBot.setAllCaps(false);
        followBtnBot.setPadding(dp(16), 0, dp(16), 0);
        followBtnBot.setBackground(roundRect(0xFFFB7299, dp(15), dp(30)));
        upLine.addView(followBtnBot, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(30)));
        followBtnBot.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { tapFollow(); }
        });
        infoWrap.addView(upLine, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 标题
        bvidText = new TextView(this);
        bvidText.setText("");
        bvidText.setTextColor(MainActivity.C_FG);
        bvidText.setTextSize(14);
        bvidText.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        bvidText.setMaxLines(2);
        bvidText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        bvidText.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams btp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btp.topMargin = dp(10);
        infoWrap.addView(bvidText, btp);

        // 统计：播放 / 评论 / 日期
        statText = new TextView(this);
        statText.setText("");
        statText.setTextColor(Color.parseColor("#8A909C"));
        statText.setTextSize(10);
        statText.setSingleLine(true);
        LinearLayout.LayoutParams stp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stp.topMargin = dp(6);
        infoWrap.addView(statText, stp);

        // 互动栏：赞 / 评论 / 投币 / 收藏 / 转发（图标 24dp 统一尺寸）
        interactBar = new LinearLayout(this);
        interactBar.setOrientation(LinearLayout.HORIZONTAL);
        interactBar.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams ibp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ibp.topMargin = dp(10);

        actLikeBot = makeAct("like", "like_on", 24, false);
        actCmtBot = makeAct("comment", "comment", 24, false);
        actCoinBot = makeAct("coin", "coin", 24, false);
        actFavBot = makeAct("star", "star_on", 24, false);
        actShareBot = makeAct("share", "share", 24, false);
        addActWide(interactBar, actLikeBot);
        addActWide(interactBar, actCmtBot);
        addActWide(interactBar, actCoinBot);
        addActWide(interactBar, actFavBot);
        addActWide(interactBar, actShareBot);
        actLikeBot.box.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { tapLike(); }
        });
        actLikeBot.box.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) { tripleLike(); return true; }
        });
        actCmtBot.box.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openComments(); }
        });
        actCoinBot.box.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { tapCoin(); }
        });
        actFavBot.box.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { tapFav(); }
        });
        actShareBot.box.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { tapShare(); }
        });
        infoWrap.addView(interactBar, ibp);

        root.addView(infoWrap, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 相关推荐（占满剩余空间）
        recWrap = new LinearLayout(this);
        recWrap.setOrientation(LinearLayout.VERTICAL);

        LinearLayout recHead = new LinearLayout(this);
        recHead.setOrientation(LinearLayout.HORIZONTAL);
        recHead.setGravity(Gravity.CENTER_VERTICAL);
        recHead.setPadding(dp(14), dp(9), dp(14), dp(4));

        TextView recTitle = new TextView(this);
        recTitle.setText("相关推荐");
        recTitle.setTextColor(MainActivity.C_FG);
        recTitle.setTextSize(13);
        recTitle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        recHead.addView(recTitle, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView recTip = new TextView(this);
        recTip.setText("点即切换");
        recTip.setTextColor(MainActivity.C_SUB);
        recTip.setTextSize(11);
        recHead.addView(recTip);

        recWrap.addView(recHead, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        recList = new ListView(this);
        recList.setDivider(new ColorDrawable(Color.TRANSPARENT));
        recList.setDividerHeight(dp(8));
        recList.setPadding(dp(10), 0, dp(10), dp(10));
        recList.setClipToPadding(false);
        recList.setCacheColorHint(Color.TRANSPARENT);
        recList.setSelector(new ColorDrawable(Color.TRANSPARENT));
        recList.setOverScrollMode(View.OVER_SCROLL_NEVER);
        recAdapter = new VideoAdapter(this, recRows);
        recList.setAdapter(recAdapter);
        recList.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override public void onItemClick(android.widget.AdapterView<?> p,
                                              View v, int i, long id) {
                if (i < 0 || i >= recRows.size()) return;
                VideoItem it = recRows.get(i);
                if (it.isPlaceholder()) return;
                switchVideo(it);
            }
        });
        recWrap.addView(recList, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(recWrap, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // 【v4.9.6】最外层加一个 FrameLike，评论抽屉从底部叠上来，
        // 上方的视频保持可见、继续播放，不再整页被评论糊满。
        shellFrame = new FrameLike(this);
        shellFrame.addView(root, new FrameLike.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(shellFrame);
        if (actRowRef != null) actRowRef.setVisibility(View.GONE);
        if (titleColRef != null) titleColRef.setVisibility(View.GONE);
        if (topBar != null) topBar.setBackground(null);
        setBackChip(true);
        if (upRow != null) upRow.setVisibility(View.GONE);

        // ---------------- 手势 ----------------
        gesture = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDoubleTap(MotionEvent e) {
                togglePlay();
                return true;
            }
            @Override public void onLongPress(MotionEvent e) {
                if (twoFinger) return;
                // 【v4.9.5 关键修复】竖屏刷信息流下禁止长按倍速。
                // 原因：快速上滑时手指从按下到滑走往往超过 500ms，GestureDetector 的
                // 长按计时器在 ACTION_DOWN 就已启动（不依赖后续 MOVE），一旦触发就是
                // 「2× 快进中」；更糟的是滑动分支会提前 return，ACTION_UP 的复位代码
                // 永远执行不到，于是整个视频卡在 2 倍速。
                if (swipeMode || swiping || swipeDy != 0f) return;
                boosting = true;
                speedTag.setVisibility(View.VISIBLE);
                setRate(2.0f);
            }
            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                // 【v4.19.0】竖屏沉浸式：单击只切右侧互动栏 / 底部信息条 / 顶部返回
                if (portraitFill) {
                    setImmersiveUiVisible(!vUiVisible);
                    return true;
                }
                ctrlVisible = !ctrlVisible;
                ctrlBar.setVisibility(ctrlVisible ? View.VISIBLE : View.GONE);
                if (topBar != null) {
                    topBar.setVisibility(ctrlVisible ? View.VISIBLE : View.GONE);
                }
                return true;
            }
        });

        scaleDet = new ScaleGestureDetector(this,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override public boolean onScale(ScaleGestureDetector d) {
                        float f = d.getScaleFactor();
                        float ns = zoom * f;
                        if (ns < 1f) ns = 1f;
                        if (ns > 4f) ns = 4f;
                        float k = ns / zoom;
                        float fx = d.getFocusX();
                        float fy = d.getFocusY();
                        // 以双指焦点为中心缩放（pivot 固定在左上角，用平移补偿）
                        tx = fx - k * (fx - tx);
                        ty = fy - k * (fy - ty);
                        zoom = ns;
                        applyZoom();
                        return true;
                    }
                });

        overlay.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent ev) {
                int act = ev.getActionMasked();
                // 多指：只走缩放手势，绝不触发单击/双击/长按
                if (ev.getPointerCount() > 1
                        || (twoFinger && act != MotionEvent.ACTION_UP
                            && act != MotionEvent.ACTION_CANCEL)) {
                    twoFinger = true;
                    if (boosting) {
                        boosting = false;
                        speedTag.setVisibility(View.INVISIBLE);
                        setRate(1.0f);
                    }
                    if (vertDrag) { vertDrag = false; hideHudSoon(); }
                    // 【v4.9 修复】多指打断翻页手势时必须复位滑动状态，
                    // 否则两槽会卡在偏移位置，下一次单指拖动从错误几何起手（画面跳变）。
                    if (swiping || swipeDy != 0f) {
                        swiping = false;
                        horzDrag = false;
                        swipeDy = 0f;
                        resetSwipeSlots();
                    }
                    scaleDet.onTouchEvent(ev);
                    return true;
                }
                if (twoFinger) {
                    twoFinger = false;
                    scaleDet.onTouchEvent(ev);
                    if (zoom <= 1.02f) {
                        zoom = 1f;
                        tx = 0f;
                        ty = 0f;
                        applyZoom();
                    }
                    return true;
                }

                // 单指上下滑：左半屏调亮度，右半屏调音量
                // 【v4.9 竖屏翻页】手势仲裁：
                //   竖屏铺满模式下，纵向滑动让位给「抖音式翻页」，
                //   亮度/音量改为**横向拖动**触发（左半屏亮度、右半屏音量）——
                //   功能一个不丢，只是把操作方向从纵向改成横向。
                if (act == MotionEvent.ACTION_DOWN) {
                    downX = ev.getX();
                    downY = ev.getY();
                    vertDrag = false;
                    horzDrag = false;
                    captureStartLevels();
                    // 【v4.9 修复】即使动画进行中也要重置基准点。
                    // 否则动画期间的 ACTION_DOWN 被跳过，swipeLastY 仍是上次手势的值，
                    // 动画结束后第一个 MOVE 会用陈旧基准算出巨大的 dy（画面瞬移），
                    // 抬手时又会因位移超阈值而白翻一页。
                    if (swipeMode) {
                        swipeOnDown(ev.getX(), ev.getY());
                    }
                } else if (act == MotionEvent.ACTION_MOVE) {
                    if (swipeMode && !swipeAnimating && !boosting) {
                        float dyv = ev.getY() - downY;
                        float dxv = ev.getX() - downX;
                        // 纵向位移占优 → 翻页；横向占优 → 亮度/音量
                        if (!swiping && !horzDrag) {
                            if (Math.abs(dyv) > dp(14) && Math.abs(dyv) > Math.abs(dxv)) {
                                swiping = true;
                                cancelLongPressTimer(ev);   // 【v4.9.5】掐掉长按倍速计时器
                            } else if (Math.abs(dxv) > dp(14) && Math.abs(dxv) > Math.abs(dyv)) {
                                horzDrag = true;
                                vertVolume = ev.getX() >= v.getWidth() / 2f;
                            }
                        }
                        if (swiping) {
                            swipeOnMove(ev.getY());
                            return true;    // 翻页期间不喂给手势，避免误触暂停
                        }
                        if (horzDrag) {
                            applyLevel(dxv);
                            return true;
                        }
                    }
                    if (!vertDrag && !boosting && !swipeMode) {
                        float dx = Math.abs(ev.getX() - downX);
                        float up = downY - ev.getY();
                        if (Math.abs(up) > dp(14) && Math.abs(up) > dx) {
                            vertDrag = true;
                            // 落在右半屏 → 音量；左半屏 → 亮度
                            vertVolume = ev.getX() >= v.getWidth() / 2f;
                        }
                    }
                    if (vertDrag) {
                        applyLevel(downY - ev.getY());
                        return true;         // 拖动期间不喂给手势，避免误触暂停
                    }
                } else if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) {
                    // 【v4.9.5】抬手必复位倍速：滑动分支会在下面提前 return，
                    // 不复位的话误触的 2× 会一直挂到下一次抬手。
                    if (boosting) {
                        boosting = false;
                        if (speedTag != null) speedTag.setVisibility(View.INVISIBLE);
                        setRate(1.0f);
                    }
                    if (swipeMode && (swiping || swipeDy != 0f)) {
                        boolean wasSwipe = swiping;
                        swiping = false;
                        // 【v4.9 修复】ACTION_CANCEL 是手势被系统/父容器打断，
                        // 不能当成「抬手确认」——否则一次误打断会真的翻页。
                        // 取消时一律弹回原位。
                        if (act == MotionEvent.ACTION_CANCEL) {
                            swipeCancel();
                        } else {
                            swipeOnUp();
                        }
                        if (wasSwipe) {
                            if (horzDrag) { horzDrag = false; hideHudSoon(); }
                            return true;
                        }
                    }
                    if (horzDrag) {
                        horzDrag = false;
                        hideHudSoon();
                        return true;
                    }
                    if (vertDrag) {
                        vertDrag = false;
                        hideHudSoon();
                        return true;
                    }
                }

                if (act == MotionEvent.ACTION_UP && boosting) {
                    boosting = false;
                    speedTag.setVisibility(View.INVISIBLE);
                    setRate(1.0f);
                }
                gesture.onTouchEvent(ev);
                return true;
            }
        });
    }

    // ------------------------------------------------------------ 音量 / 亮度

    void captureStartLevels() {
        try {
            if (audioMgr == null) {
                audioMgr = (android.media.AudioManager)
                        getSystemService(android.content.Context.AUDIO_SERVICE);
            }
            maxVolume = Math.max(1, audioMgr.getStreamMaxVolume(
                    android.media.AudioManager.STREAM_MUSIC));
            startVolume = audioMgr.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
        } catch (Throwable ignore) { }
        try {
            float b = getWindow().getAttributes().screenBrightness;
            if (b < 0f) {
                b = android.provider.Settings.System.getInt(getContentResolver(),
                        android.provider.Settings.System.SCREEN_BRIGHTNESS, 128) / 255f;
            }
            startBrightness = Math.max(0.05f, Math.min(1f, b));
        } catch (Throwable ignore) {
            startBrightness = 0.5f;
        }
    }

    /** dy>0 表示手指上滑（增大） */
    void applyLevel(float dy) {
        float range = Math.max(1f, holder.getHeight() * 0.85f);
        float f = dy / range;
        try {
            if (vertVolume) {
                int v = Math.round(startVolume + f * maxVolume);
                if (v < 0) v = 0;
                if (v > maxVolume) v = maxVolume;
                if (audioMgr != null) {
                    audioMgr.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, v, 0);
                }
                showHud("右侧 · 音量  " + Math.round(v * 100f / maxVolume) + "%");
            } else {
                float b = startBrightness + f;
                if (b < 0.05f) b = 0.05f;
                if (b > 1f) b = 1f;
                WindowManager.LayoutParams lp = getWindow().getAttributes();
                lp.screenBrightness = b;
                getWindow().setAttributes(lp);
                showHud("左侧 · 亮度  " + Math.round(b * 100f) + "%");
            }
        } catch (Throwable ignore) { }
    }

    final Runnable hideHudTask = new Runnable() {
        @Override public void run() {
            if (hud != null) hud.setVisibility(View.GONE);
        }
    };

    void showHud(String text) {
        if (hud == null) return;
        hud.setText(text);
        hud.setVisibility(View.VISIBLE);
        ui.removeCallbacks(hideHudTask);
    }

    void hideHudSoon() {
        if (hud == null) return;
        ui.removeCallbacks(hideHudTask);
        ui.postDelayed(hideHudTask, 800);
    }

    // ------------------------------------------------------------ 弹幕 / 竖屏优化

    long currentPos() {
        try {
            return video != null ? video.getCurrentPosition() : 0;
        } catch (Throwable e) {
            return 0;
        }
    }

    void toggleDanmaku() {
        dmOn = !dmOn;
        // 【v4.19.0】滑动中弹幕层被临时隐去，这里不要强行拉回来
        if (dmView != null && !dmSuspended) {
            dmView.setVisibility(dmOn ? View.VISIBLE : View.GONE);
        }
        if (dmSwitch != null) {
            dmSwitch.setClickable(true);
            dmSwitch.invalidate();
        }
        if (vDmSwitch != null) vDmSwitch.invalidate();
        if (dmHint != null) {
            dmHint.setText(dmOn ? "发个友善的弹幕见证当下" : "弹幕已关闭，点这里重新开启");
        }
        if (vDmHint != null) {
            vDmHint.setText(dmOn ? "发个友善的弹幕见证当下" : "弹幕已关闭，点这里重新开启");
        }
        showToastSafe(dmOn ? "弹幕已开启（长按「弹」调颜色/字号）" : "弹幕已关闭");
    }

    void showDmPanel() {
        if (dmPanel == null) return;
        dmPanel.setVisibility(View.VISIBLE);
        dmPanel.bringToFront();
        dmInput.requestFocus();
        try {
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager)
                            getSystemService(INPUT_METHOD_SERVICE);
            imm.showSoftInput(dmInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
        } catch (Throwable ignore) { }
    }

    void hideDmPanel() {
        if (dmPanel == null) return;
        dmPanel.setVisibility(View.GONE);
        try {
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager)
                            getSystemService(INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(dmInput.getWindowToken(), 0);
        } catch (Throwable ignore) { }
    }

    void sendDanmaku() {
        final String txt = dmInput != null ? dmInput.getText().toString().trim() : "";
        if (txt.isEmpty()) {
            showToastSafe("先写点内容吧");
            return;
        }
        if (BiliApi.loggedInUid() == null) {
            showToastSafe("请先在「我的」页登录 B 站账号");
            return;
        }
        if (dmCid <= 0 || dmBvid.isEmpty()) {
            showToastSafe("视频信息还没准备好，稍等几秒");
            return;
        }
        final long pos = currentPos();
        hideDmPanel();
        dmInput.setText("");
        new Thread(new Runnable() { @Override public void run() {
            try {
                BiliApi.danmakuSend(dmBvid, dmCid, txt, pos);
                ui.post(new Runnable() { @Override public void run() {
                    if (dmView != null) dmView.addLocal(txt);
                    showToastSafe("弹幕已发送");
                } });
            } catch (final Throwable e) {
                ui.post(new Runnable() { @Override public void run() {
                    showToastSafe("发送失败：" + e.getMessage());
                } });
            }
        } }).start();
    }

    void loadDanmaku(final long cid) {
        dmCid = cid;
        if (dmView == null) return;
        dmView.clear();
        new Thread(new Runnable() { @Override public void run() {
            try {
                final ArrayList<DanmakuView.Item> list =
                        DanmakuView.parse(BiliApi.danmakuXml(cid));
                ui.post(new Runnable() { @Override public void run() {
                    if (dmCid == cid && dmView != null) dmView.setData(list);
                } });
            } catch (Throwable ignore) { }
        } }).start();
    }

    /** 取标题/UP/封面/尺寸：竖屏视频场景与背景填充都靠它 */
    /** 拉取视频详情（view 接口）：标题 / UP / 统计 / 分P / 竖屏尺寸，并同步互动状态 */
    void loadVideoMeta(final String bv) {
        if (bv == null || bv.isEmpty() || bv.startsWith("av")) return;
        new Thread(new Runnable() { @Override public void run() {
            // 【v4.8】原来 4 个请求（meta / like / coin / follow）全串行，
            // 最坏要等 4 个网络往返才把标题和互动数显示出来。
            // 现在 like/coin 与 meta 同时开跑，follow 依赖 meta 里的 mid 再跟上，
            // 整体耗时约等于 max(meta+follow, like, coin)，通常能快 30%~50%。
            final int[] st = new int[3];   // 0=liked 1=coins(已投) 2=followed
            final Thread tl = new Thread(new Runnable() { @Override public void run() {
                try { st[0] = BiliApi.likeState(bv); } catch (Throwable ignore) { }
            } }, "meta-like");
            final Thread tc = new Thread(new Runnable() { @Override public void run() {
                try { st[1] = BiliApi.coinState(bv); } catch (Throwable ignore) { }
            } }, "meta-coin");
            tl.start();
            tc.start();

            JSONObject v = null;
            try {
                v = BiliApi.archiveMeta(bv);
            } catch (Throwable e) {
                v = null;
            }
            if (v == null) {
                // meta 都没拿到就没必要等状态了（线程都是短请求，会自行结束）
                return;
            }
            final JSONObject fv = v;
            JSONObject owner = fv.optJSONObject("owner");
            final long mid = owner == null ? 0 : owner.optLong("mid", 0);
            try { if (mid > 0) st[2] = BiliApi.followState(mid); } catch (Throwable ignore) { }
            try { tl.join(2500); } catch (Throwable ignore) { }
            try { tc.join(2500); } catch (Throwable ignore) { }
            final int lk = st[0], cn = st[1], fl = st[2];
            ui.post(new Runnable() { @Override public void run() {
                // 【v4.7】已切到别的视频时，旧视频的元数据晚返回会把标题/UP主/互动状态改回去
                if (!bv.equals(bvid)) return;
                applyMeta(fv, lk, cn, fl);
            } });
        } }).start();
    }

    void applyMeta(JSONObject v, int lk, int cn, int fl) {
        if (v == null || isFinishing()) return;
        try {
            metaLoaded = true;
            aid = v.optLong("aid", 0);
            videoTitle = BiliApi.cleanTitle(v.optString("title", ""));
            videoCover = v.optString("pic", "");
            coverUrl = videoCover;
            // meta 拿到封面后补写历史（取 cid 时尚无封面 → 历史页全灰）。带封面重入会去重并置顶。
            if (!coverUrl.isEmpty() && bvid != null && !bvid.isEmpty()) {
                History.add(PlayActivity.this, bvid, videoTitle.isEmpty() ? bvid : videoTitle, coverUrl);
            }
            vTitle = videoTitle;

            JSONObject owner = v.optJSONObject("owner");
            if (owner != null) {
                ownerMid = owner.optLong("mid", 0);
                upName = owner.optString("name", "");
                String face = owner.optString("face", "");
                face = ImgLoader.https(face);
                ownerFace = face;
                if (!face.isEmpty()) {
                    if (upAvatarBot != null) ImgLoader.load(upAvatarBot, face, dp(34), dp(34));
                    if (upAvatarTop != null) ImgLoader.load(upAvatarTop, face, dp(26), dp(26));
                    if (vAvatar != null) ImgLoader.load(vAvatar, face, dp(50), dp(50));
                }
                if (bgCover != null && !videoCover.isEmpty() && !portraitFill) {
                    ImgLoader.load(bgCover, videoCover,
                            getResources().getDisplayMetrics().widthPixels, dp(400));
                }
            }
            // 【v4.9.1】头像 / 昵称可点，进 UP 主主页
            View.OnClickListener upClick = new View.OnClickListener() {
                @Override public void onClick(View x) { goUpHome(); }
            };
            if (upAvatarBot != null) upAvatarBot.setOnClickListener(upClick);
            if (upAvatarTop != null) upAvatarTop.setOnClickListener(upClick);
            if (upNameTop != null) upNameTop.setOnClickListener(upClick);
            if (pUp != null) pUp.setOnClickListener(upClick);
            if (upNameTop != null) upNameTop.setText(upName.isEmpty() ? "UP 主" : upName);
            if (pUp != null) pUp.setText(upName.isEmpty() ? "" : "UP主 · " + upName);

            JSONObject stat = v.optJSONObject("stat");
            if (stat != null) {
                statView = stat.optLong("view", 0);
                statDm = stat.optLong("danmaku", 0);
                statReply = stat.optInt("reply", 0);
                statLike = stat.optInt("like", 0);
                statCoin = stat.optInt("coin", 0);
                statFav = stat.optInt("favorite", 0);
                statShare = stat.optInt("share", 0);
            }
            long pub = v.optLong("pubdate", 0);
            if (pub > 0) {
                pubDate = new java.text.SimpleDateFormat("yyyy-MM-dd")
                        .format(new java.util.Date(pub * 1000L));
            }
            JSONArray pages = v.optJSONArray("pages");
            if (pages != null && pages.length() > 0) {
                pagesArr = pages;
                if (nextBtn != null) nextBtn.setAlpha(pages.length() > 1 ? 1f : 0.35f);
            }
            JSONObject dim = v.optJSONObject("dimension");
            if (dim != null) {
                int dw = dim.optInt("width", 0);
                int dh = dim.optInt("height", 0);
                if (dw > 0 && dh > 0) {
                    int rot = dim.optInt("rotate", 0);
                    if (rot == 90 || rot == 270) { int t = dw; dw = dh; dh = t; }
                    videoAspect = dw / (float) dh;
                    portraitVideo = dh > dw * 1.15f;
                }
            }
            // 竖屏视频在竖屏持机时自动铺满整屏；横屏视频恢复普通布局
            if (Prefs.portraitOptimize(this)) {
                if (portraitVideo && !fullscreen) enterPortraitFill();
                else if (!portraitVideo && portraitFill) exitPortraitFill();
            }
            // 【v4.9】宽高比确定后才知道是不是竖屏 → 同步加载框样式（竖屏只留文字）
            applyVerticalLoadStyle();

            String t = videoTitle.isEmpty() ? curBv : videoTitle;
            if (titleTop != null) titleTop.setText(t);
            if (bvidText != null) bvidText.setText(t);
            if (pTitle != null) pTitle.setText(t);
            // 【v4.19.0】底部信息条同步
            if (vTitleTxt != null) vTitleTxt.setText(t);
            if (vUpTxt != null) vUpTxt.setText(upName.isEmpty() ? "" : "UP主 · " + upName);

            String watch = numText(statView) + "播放";
            if (!pubDate.isEmpty()) watch += " · " + pubDate;
            if (watchText != null) watchText.setText(watch);
            if (statText != null) {
                statText.setText(numText(statView) + "播放 · " + numText(statReply) + "评论 · "
                        + numText(statDm) + "弹幕"
                        + (pubDate.isEmpty() ? "" : " · " + pubDate));
            }
            if (tabComment != null) tabComment.setText("评论 " + numText(statReply));

            liked = lk == 1;
            coins = Math.max(0, cn);
            followedState = Math.max(0, fl);
            refreshActs();
            if (fullscreen) applyFullscreenLayout();
        } catch (Throwable e) {
            CrashHandler.note("meta", "填充视频信息失败：" + e);
        }
    }

    /** 全屏画面排布：竖屏视频按高度居中 + 封面虚化背景 + 左侧信息；横屏视频照常铺满 */
    void applyFullscreenLayout() {
        if (!fullscreen) return;
        boolean opt = portraitVideo && Prefs.portraitOptimize(this);
        try {
            if (opt) {
                int h = getResources().getDisplayMetrics().heightPixels;
                int w = (int) (h * Math.max(0.3f, Math.min(1.2f, videoAspect)));
                int maxW = getResources().getDisplayMetrics().widthPixels - dp(28);
                if (w > maxW) w = maxW;
                if (w < dp(110)) w = dp(110);
                FrameLike.LayoutParams lp =
                        new FrameLike.LayoutParams(w, ViewGroup.LayoutParams.MATCH_PARENT,
                                Gravity.CENTER);
                video.setLayoutParams(lp);
                vlcSurface.setLayoutParams(new FrameLike.LayoutParams(
                        w, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
                if (dmView != null) dmView.setLayoutParams(new FrameLike.LayoutParams(
                        w, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
                bgCover.setVisibility(View.VISIBLE);
                if (!coverUrl.isEmpty()) {
                    String u = ImgLoader.https(coverUrl);
                    u = ImgLoader.face(u, 640);
                    ImgLoader.load(bgCover, u, dp(360), dp(640));
                }
                pTitle.setText(vTitle);
                pUp.setText(vUp.isEmpty() ? "" : "UP主 · " + vUp);
                portraitInfo.setVisibility(View.VISIBLE);
            } else {
                video.setLayoutParams(new FrameLike.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                vlcSurface.setLayoutParams(new FrameLike.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                if (dmView != null) dmView.setLayoutParams(new FrameLike.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                bgCover.setVisibility(View.GONE);
                portraitInfo.setVisibility(View.GONE);
            }
            overlay.setLayoutParams(new FrameLike.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            // 【v4.9】翻页层跟随全屏尺寸（横屏全屏不启用翻页，仅保持容器正确）
            if (swipeLayer != null) swipeLayer.setLayoutParams(new FrameLike.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } catch (Throwable ignore) { }
    }

    // ------------------------------------------------------------ 竖屏视频铺满（竖屏持机）

    /**
     * 竖屏视频 + 竖屏持机 → 铺满整屏。
     * 视频画面按自身宽高比放大到「覆盖」整个画面区（超出部分由父容器裁剪），
     * 因此不会变形、也不会留下左右黑边；顶部栏与底部控制条悬浮在画面之上。
     */
    void enterPortraitFill() {
        if (!portraitVideo || fullscreen || portraitFill) return;
        portraitFill = true;
        try {
            // 画面容器占满整屏
            root.updateViewLayout(holder, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            // 收起普通模式下的页面内容
            if (infoWrap != null) infoWrap.setVisibility(View.GONE);
            if (recWrap != null) recWrap.setVisibility(View.GONE);
            // 【v4.19.0】顶部只留返回键：标题与互动图标全部让位给右侧互动栏 / 底部信息条
            if (upRow != null) upRow.setVisibility(View.GONE);
            if (actRowRef != null) actRowRef.setVisibility(View.GONE);
            if (titleColRef != null) titleColRef.setVisibility(View.GONE);
            if (topBar != null) topBar.setBackground(new android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{0xB3000000, 0x33000000, 0x00000000}));
            setBackChip(true);
            // 控制条从页面流里移到画面浮层（随后由沉浸式界面接管，普通控制条隐藏）
            ctrlToOverlay();
            ctrlBar.setBackgroundColor(0xB3000000);
            overlay.setLayoutParams(new FrameLike.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            if (bgCover != null) bgCover.setVisibility(View.GONE);
            if (portraitInfo != null) portraitInfo.setVisibility(View.GONE);
            if (exitFillBtn != null) exitFillBtn.setVisibility(View.GONE);
            ctrlVisible = true;
            // 【v4.19.0】切到 B 站竖屏沉浸式界面（右侧互动栏 + 底部信息条 + 细进度条）
            showImmersiveUi(true);
            if (topBar != null) topBar.setVisibility(View.VISIBLE);
            resetZoom();
            applyPortraitFillSize();
            // 【v4.9】竖屏铺满 → 开启抖音式上下滑翻页
            enterSwipeMode();
        } catch (Throwable e) {
            CrashHandler.note("portraitFill", "进入竖屏铺满失败：" + e);
        }
    }

    /** 按视频宽高比计算 cover 尺寸，让画面铺满且不留黑边 */
    void applyPortraitFillSize() {
        if (!portraitFill || fullscreen) return;
        ui.post(new Runnable() { @Override public void run() {
            if (!portraitFill || fullscreen || isFinishing()) return;
            int W = holder.getWidth() > 0 ? holder.getWidth()
                    : getResources().getDisplayMetrics().widthPixels;
            int H = holder.getHeight() > 0 ? holder.getHeight()
                    : getResources().getDisplayMetrics().heightPixels;
            if (W <= 0 || H <= 0 || videoAspect <= 0) return;
            float screenAr = W / (float) H;
            int vw, vh;
            if (screenAr < videoAspect) {
                // 屏幕相对更瘦高：按高度撑满，宽度略微超出左右裁切
                vh = H;
                vw = Math.round(H * videoAspect);
            } else {
                // 屏幕相对更宽扁：按宽度撑满，高度略微超出上下裁切
                vw = W;
                vh = Math.round(W / videoAspect);
            }
            final FrameLike.LayoutParams vp =
                    new FrameLike.LayoutParams(vw, vh, Gravity.CENTER);
            video.setLayoutParams(vp);
            vlcSurface.setLayoutParams(new FrameLike.LayoutParams(vw, vh, Gravity.CENTER));
            // 弹幕层跟随可见画面铺满屏幕（弹幕坐标以屏幕为准）
            if (dmView != null) dmView.setLayoutParams(new FrameLike.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
            if (flipCover != null) flipCover.setLayoutParams(new FrameLike.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            // 【v4.9】翻页层铺满整屏：slotCur / slotNext 都等于屏高，
            // 两者相距一个屏高 → 上滑时下一槽正好从屏幕下方进入
            if (swipeLayer != null) {
                swipeLayer.setLayoutParams(new FrameLike.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
            }
            try { video.setScaleX(1f); video.setScaleY(1f); } catch (Throwable ignore) { }
        } });
    }

    /** 退出竖屏铺满，还原到普通竖屏详情页布局 */
    void exitPortraitFill() {
        if (!portraitFill) return;
        portraitFill = false;
        try {
            if (exitFillBtn != null) exitFillBtn.setVisibility(View.GONE);
            // 控制条回到页面流
            ctrlToRootFlow();
            ctrlBar.setBackgroundColor(0xE60B0D10);
            // 【v4.19.0】收起沉浸式界面，恢复普通详情页控制条
            showImmersiveUi(false);
            // 恢复页面内容
            if (infoWrap != null) infoWrap.setVisibility(View.VISIBLE);
            if (recWrap != null) recWrap.setVisibility(View.VISIBLE);
            if (upRow != null) upRow.setVisibility(View.GONE);
            if (actRowRef != null) actRowRef.setVisibility(View.GONE);
            if (titleColRef != null) titleColRef.setVisibility(View.GONE);
            if (topBar != null) {
                topBar.setBackground(null);
                topBar.setVisibility(View.VISIBLE);
            }
            setBackChip(true);
            root.updateViewLayout(holder, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            video.setLayoutParams(new FrameLike.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, videoHeight));
            vlcSurface.setLayoutParams(new FrameLike.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, videoHeight));
            if (dmView != null) dmView.setLayoutParams(new FrameLike.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, videoHeight));
            if (flipCover != null) flipCover.setLayoutParams(new FrameLike.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, videoHeight));
            overlay.setLayoutParams(new FrameLike.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, videoHeight));
            // 【v4.9】退出竖屏铺满 → 关闭翻页模式，翻页层缩回普通高度并复位
            exitSwipeMode();
            if (swipeLayer != null) swipeLayer.setLayoutParams(new FrameLike.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, videoHeight));
            if (bgCover != null) bgCover.setVisibility(View.GONE);
            if (portraitInfo != null) portraitInfo.setVisibility(View.GONE);
            ctrlVisible = true;
            ctrlBar.setVisibility(View.VISIBLE);
            resetZoom();
        } catch (Throwable e) {
            CrashHandler.note("portraitFill", "退出竖屏铺满失败：" + e);
        }
    }

    // ------------------------------------------------------------ 控制条归位

    /**
     * 【v4.17.1】把控制条挂到画面浮层底部。
     * 关键点：先判断父容器再动。同一个 child 二次 addView 会直接抛
     * IllegalStateException —— 从「分屏」直接进「全屏」时控制条本来就浮着，
     * 旧写法会让播放页在切换瞬间崩掉。
     */
    void ctrlToOverlay() {
        try {
            FrameLike.LayoutParams lp = new FrameLike.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM);
            if (ctrlBar.getParent() == overlay) {
                overlay.updateViewLayout(ctrlBar, lp);
            } else {
                if (ctrlBar.getParent() != null) {
                    ((ViewGroup) ctrlBar.getParent()).removeView(ctrlBar);
                }
                overlay.addView(ctrlBar, lp);
            }
        } catch (Throwable e) {
            CrashHandler.note("ctrl", "控制条浮动失败：" + e);
        }
    }

    /** 【v4.17.1】把控制条放回页面流（holder 下方）；重复调用不会重复添加 */
    void ctrlToRootFlow() {
        try {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (ctrlBar.getParent() == root) {
                root.updateViewLayout(ctrlBar, lp);
            } else {
                if (ctrlBar.getParent() != null) {
                    ((ViewGroup) ctrlBar.getParent()).removeView(ctrlBar);
                }
                root.addView(ctrlBar, Math.min(1, root.getChildCount()), lp);
            }
        } catch (Throwable e) {
            CrashHandler.note("ctrl", "控制条归位失败：" + e);
        }
    }

    // ------------------------------------------------------------ 全屏

    /** 全屏/退出全屏的点击节流：旋转过程要 300~600ms，连点会把布局推到中间态 */
    long lastFullToggle = 0;

    void toggleFullscreen() {
        // 【v4.17.1】旋转还没结束就再点一次，屏幕方向请求会互相打断，
        // 出现「刚退出又进全屏」的错乱，这里 350ms 内只认第一次点击。
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastFullToggle < 350) return;
        lastFullToggle = now;
        if (fullscreen) exitFullscreen();
        else enterFullscreen();
    }

    void enterFullscreen() {
        if (fullscreen) return;
        if (portraitFill) exitPortraitFill();   // 与竖屏铺满互斥，先还原
        fullscreen = true;
        if (fullIcon != null) Ic.set(fullIcon, "exitfull", Color.WHITE);
        ctrlVisible = true;
        ctrlBar.setVisibility(View.VISIBLE);

        // 控制条从 root 移到 overlay 底部，浮在画面上
        ctrlToOverlay();

        recWrap.setVisibility(View.GONE);
        if (infoWrap != null) infoWrap.setVisibility(View.GONE);
        if (upRow != null) upRow.setVisibility(View.VISIBLE);
        if (actRowRef != null) actRowRef.setVisibility(View.VISIBLE);
        if (titleColRef != null) titleColRef.setVisibility(View.VISIBLE);
        if (topBar != null) topBar.setBackground(new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xCC000000, 0x66000000, 0x00000000}));
        setBackChip(false);

        root.updateViewLayout(holder, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        applyFullscreenLayout();

        resetZoom();
        applyFullscreenWindow(true);
        try {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        } catch (Throwable ignore) { }
    }

    void exitFullscreen() {
        if (!fullscreen) return;
        fullscreen = false;
        if (fullIcon != null) Ic.set(fullIcon, "full", Color.WHITE);

        ctrlToRootFlow();

        recWrap.setVisibility(View.VISIBLE);
        if (infoWrap != null) infoWrap.setVisibility(View.VISIBLE);
        if (upRow != null) upRow.setVisibility(View.GONE);
        if (actRowRef != null) actRowRef.setVisibility(View.GONE);
        if (titleColRef != null) titleColRef.setVisibility(View.GONE);
        if (topBar != null) topBar.setBackground(null);
        setBackChip(true);

        root.updateViewLayout(holder, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        video.setLayoutParams(new FrameLike.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, videoHeight));
        vlcSurface.setLayoutParams(new FrameLike.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, videoHeight));
        if (dmView != null) dmView.setLayoutParams(new FrameLike.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, videoHeight));
        overlay.setLayoutParams(new FrameLike.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, videoHeight));
        // 【v4.9 修复】退出全屏时 holder 变回 WRAP_CONTENT 高度，
        // 若 swipeLayer 还留着 MATCH_PARENT 会被撑成整屏，画面被顶到屏幕外看不见。
        // 必须跟着恢复成 videoHeight。
        if (swipeLayer != null) swipeLayer.setLayoutParams(new FrameLike.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, videoHeight));
        bgCover.setVisibility(View.GONE);
        portraitInfo.setVisibility(View.GONE);

        ctrlVisible = true;
        ctrlBar.setVisibility(View.VISIBLE);
        resetZoom();
        applyFullscreenWindow(false);
        try {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        } catch (Throwable ignore) { }
        // 【v4.17.1】当前仍是横屏（平板横放 / 全屏前就在横屏）说明不会发生旋转，
        // 立刻回到分屏；只有真的要从横转竖时才等旋转动画走完，避免布局在半途错位。
        final boolean landNow = getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        final boolean portNow = !landNow;
        // 退出横屏全屏、回到竖屏后：竖屏视频重新铺满整屏
        ui.postDelayed(new Runnable() { @Override public void run() {
            if (isFinishing() || fullscreen) return;
            boolean port = getResources().getConfiguration().orientation
                    != android.content.res.Configuration.ORIENTATION_LANDSCAPE;
            if (!port) return;   // 还是横屏就别做竖屏铺满
            if (portraitVideo && Prefs.portraitOptimize(PlayActivity.this)) enterPortraitFill();
        } }, portNow ? 120 : 450);
    }

    void applyFullscreenWindow(boolean on) {
        try {
            if (on) {
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
                getWindow().getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
            } else {
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
                getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
            }
        } catch (Throwable ignore) { }
    }

    /** 返回：全屏时先退出全屏，竖屏铺满时先还原，普通模式直接返回上一页 */
    void goBack() {
        if (fullscreen) {
            exitFullscreen();
            return;
        }
        if (portraitFill) {
            exitPortraitFill();
            return;
        }
        finish();
    }

    @Override
    public void onBackPressed() {
        if (fullscreen) {
            exitFullscreen();
            return;
        }
        if (portraitFill) {
            exitPortraitFill();
            return;
        }
        super.onBackPressed();
    }

    // ------------------------------------------------------------ 双指缩放

    void resetZoom() {
        zoom = 1f;
        tx = 0f;
        ty = 0f;
        applyZoom();
    }

    void applyZoom() {
        View t = video;
        try {
            int w = t.getWidth();
            int h = t.getHeight();
            if (zoom > 1f && w > 0 && h > 0) {
                float minX = -w * (zoom - 1f);
                float minY = -h * (zoom - 1f);
                if (tx > 0f) tx = 0f;
                if (tx < minX) tx = minX;
                if (ty > 0f) ty = 0f;
                if (ty < minY) ty = minY;
            } else if (zoom <= 1.02f) {
                tx = 0f;
                ty = 0f;
            }
            t.setPivotX(0);
            t.setPivotY(0);
            t.setScaleX(zoom);
            t.setScaleY(zoom);
            t.setTranslationX(tx);
            t.setTranslationY(ty);
        } catch (Throwable ignore) { }
        if (zoomTag != null) {
            if (zoom > 1.02f) {
                zoomTag.setText(String.format("%.1f×", zoom));
                zoomTag.setVisibility(View.VISIBLE);
            } else {
                zoomTag.setVisibility(View.GONE);
            }
        }
    }

    // ------------------------------------------------------------ 播放控制

    float rate = 1f;
    long lastPosSave = 0;      // 上次保存播放进度的时间
    int pendingResumeMs = 0;   // 待提示的续播位置（seek 成功后弹一次提示）

    /** 当前播放位置（ms），兼容 DASH / VLC / 普通三种引擎 */
    int curPosMs() {
        try {
            if (dashMode) return video.getCurrentPosition();
            if (video != null) return video.getCurrentPosition();
        } catch (Throwable ignore) { }
        return 0;
    }

    /** 当前总时长（ms） */
    int curDurMs() {
        try {
            if (dashMode) return pageDurMs > 0 ? (int) pageDurMs : video.getDuration();
            if (video != null) return video.getDuration();
        } catch (Throwable ignore) { }
        return 0;
    }

    /** 保存当前进度（供 tick 周期保存与退出时保存） */
    void savePosNow() {
        try {
            if (curBv == null || curBv.isEmpty()) return;
            int p = curPosMs(), d = curDurMs();
            if (p > 5000) PlayPos.save(this, curBv, p, d);
        } catch (Throwable ignore) { }
    }

    /** 续播成功后弹一次轻提示 */
    void announceResume() {
        if (pendingResumeMs <= 0) return;
        showToastSafe("已从上次 " + PlayPos.fmt(pendingResumeMs) + " 继续播放");
        pendingResumeMs = 0;
    }

    String rateLabel(float r) {
        if (Math.abs(r - 1f) < 0.01f) return "倍速";
        if (Math.abs(r - 0.75f) < 0.01f) return "0.75×";
        if (Math.abs(r - 1.25f) < 0.01f) return "1.25×";
        if (Math.abs(r - 1.5f) < 0.01f) return "1.5×";
        if (Math.abs(r - 2f) < 0.01f) return "2.0×";
        return "0.5×";
    }

    void setRate(float r) {
        rate = r;
        try {
            Compat.applyRate(player, r);
            if (dashMode && audioPlayer != null && audioStarted) {
                Compat.applyRate(audioPlayer, r);
            }
        } catch (Throwable ignore) { }
    }

    void togglePlay() {
        try {
            boolean playing = video != null && video.isPlaying();
            updatePlayIcon(!playing);
            if (video.isPlaying()) {
                video.pause();
                if (dashMode && audioPlayer != null && audioStarted) audioPlayer.pause();
            } else {
                video.start();
                // 【v4.6】仅当音频已起播过才跟随播放；尚未起播的（正在等画面首帧）由起播回调统一放行，
                // 否则声音又会先于画面出来。
                if (dashMode && audioPlayer != null && audioStarted) audioPlayer.start();
            }
        } catch (Throwable ignore) { }
    }

    void onDecodeSwitch(int mode) {
        play(mode);
    }

    /** 拖动进度条：三套引擎分别处理，高清双流要同步拖音频 */
    void doSeek(int p) {
        try {
            if (dashMode) {
                video.seekTo(p);
                if (audioPlayer != null && audioStarted) audioPlayer.seekTo(p);
                lastAudioSync = System.currentTimeMillis();
            } else {
                video.seekTo(p);
            }
        } catch (Throwable ignore) { }
    }

    // ------------------------------------------------------------ 清晰度

    static String qualityShort(int q) {
        switch (q) {
            case Prefs.Q_4K: return "4K";
            case Prefs.Q_2K: return "2K";
            case Prefs.Q_1080P60: return "1080P·60帧";
            case Prefs.Q_1080P: return "1080P";
            case Prefs.Q_720P: return "720P";
            case Prefs.Q_480P: return "480P";
            case Prefs.Q_360P: return "360P";
            default: return "自动";
        }
    }

    /**
     * 【v4.9.6】清晰度面板重做：一档一行、每行一句人话解释。
     * 旧版只有一个「4K 超清 / 1080P60 / 720P」的干名单，普通用户根本不知道
     * 自己该选哪个、选了会不会卡。现在每档都标清楚「清晰度 / 耗流量 / 适合什么场景」，
     * 并直接告诉用户这台手机的上限在哪。
     */
    void showQualityDialog() {
        final int cur = Prefs.quality(this);
        final int maxH = codecMaxPlayableHeight();

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(8), dp(2), dp(8), dp(2));

        if (maxH > 0) {
            TextView hint = new TextView(this);
            hint.setText("这台手机最高能播 " + heightName(maxH)
                    + "，选更高的档位会自动用最高可播的那一档。");
            hint.setTextColor(0xFF9AA0AB);
            hint.setTextSize(11.5f);
            hint.setPadding(dp(10), dp(4), dp(10), dp(10));
            box.addView(hint);
        }

        for (int i = 0; i < Prefs.Q_NAMES.length; i++) {
            final int which = i;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(10), dp(10), dp(10), dp(10));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(which == cur ? 0x1AFB7299 : Color.TRANSPARENT);
            bg.setCornerRadius(dp(10));
            row.setBackground(bg);

            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);

            LinearLayout titleRow = new LinearLayout(this);
            titleRow.setOrientation(LinearLayout.HORIZONTAL);
            titleRow.setGravity(Gravity.CENTER_VERTICAL);

            TextView name = new TextView(this);
            name.setText(qualityPlain(which));
            name.setTextColor(which == cur ? 0xFFFB7299 : 0xFFE8EAF0);
            name.setTextSize(14.5f);
            if (which == cur) name.setTypeface(Typeface.DEFAULT_BOLD);
            titleRow.addView(name);

            if (which == cur) {
                TextView now = new TextView(this);
                now.setText("  使用中");
                now.setTextColor(0xFFFB7299);
                now.setTextSize(10.5f);
                titleRow.addView(now);
            }
            col.addView(titleRow);

            TextView desc = new TextView(this);
            desc.setText(qualityDesc(which));
            desc.setTextColor(0xFF8A909C);
            desc.setTextSize(11f);
            desc.setLineSpacing(dp(2), 1f);
            LinearLayout.LayoutParams dp2 = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            dp2.topMargin = dp(3);
            col.addView(desc, dp2);

            row.addView(col, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            boolean beyond = maxH > 0 && heightOfQuality(which) > maxH && which != Prefs.Q_AUTO;
            TextView tag = new TextView(this);
            if (beyond) {
                tag.setText("超出本机上限");
                tag.setTextColor(0xFF6F7581);
                tag.setTextSize(10f);
            } else if (which == cur) {
                tag.setText("✓");
                tag.setTextColor(0xFFFB7299);
                tag.setTextSize(15f);
            } else {
                tag.setText("");
            }
            row.addView(tag);

            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    qualityDlg.dismiss();
                    Prefs.setQuality(PlayActivity.this, which);
                    if (qualityBtn != null) qualityBtn.setText(qualityShort(which));
                    applyQuality();
                }
            });
            box.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        android.widget.ScrollView sc = new android.widget.ScrollView(this);
        sc.addView(box);

        qualityDlg = new AlertDialog.Builder(this)
                .setTitle("画质")
                .setView(sc)
                .setNegativeButton("取消", null)
                .create();
        qualityDlg.show();
    }

    /** 清晰度弹窗引用（行点击时用它关闭） */
    AlertDialog qualityDlg;

    /** 通俗名：不带编码术语，只讲「清晰 / 省流量」 */
    static String qualityPlain(int q) {
        switch (q) {
            case Prefs.Q_4K: return "超清 4K";
            case Prefs.Q_2K: return "超清 2K";
            case Prefs.Q_1080P60: return "高帧率 1080P（60 帧）";
            case Prefs.Q_1080P: return "高清 1080P";
            case Prefs.Q_720P: return "标清 720P";
            case Prefs.Q_480P: return "流畅 480P";
            case Prefs.Q_360P: return "极速 360P";
            default: return "自动（推荐）";
        }
    }

    /** 每档一句人话：清不清楚、费不费流量、适合谁 */
    static String qualityDesc(int q) {
        switch (q) {
            case Prefs.Q_4K: return "最清晰，需要手机和网络都跟得上，流量也最费";
            case Prefs.Q_2K: return "比 1080P 更细腻，网络好的时候选它";
            case Prefs.Q_1080P60: return "60 帧更顺滑，适合游戏、运动、舞蹈类";
            case Prefs.Q_1080P: return "清晰和流量的平衡点，大多数手机够用";
            case Prefs.Q_720P: return "省流量，小屏上看几乎没差别";
            case Prefs.Q_480P: return "网速一般时也能播，细节会糊一点";
            case Prefs.Q_360P: return "只求能播完时选它，最省流量";
            default: return "按网络和手机自动挑，不用操心";
        }
    }

    /** 该档位的目标高度，用于和手机上限比较（自动返回 0 = 不限） */
    static int heightOfQuality(int q) {
        switch (q) {
            case Prefs.Q_4K: return 2160;
            case Prefs.Q_2K: return 1440;
            case Prefs.Q_1080P60: return 1080;
            case Prefs.Q_1080P: return 1080;
            case Prefs.Q_720P: return 720;
            case Prefs.Q_480P: return 480;
            case Prefs.Q_360P: return 360;
            default: return 0;
        }
    }

    static String heightName(int h) {
        if (h >= 2000) return "4K";
        if (h >= 1400) return "2K";
        if (h >= 1000) return "1080P";
        if (h >= 700) return "720P";
        if (h >= 400) return "480P";
        if (h > 0) return "360P";
        return "未知";
    }

    /** 编码家族的白话名：只留用户能理解的部分 */
    static String codecPlain(String fam) {
        if (fam == null) return "";
        if (fam.equalsIgnoreCase("avc") || fam.contains("H.264")) return "H.264（兼容最好）";
        if (fam.equalsIgnoreCase("hevc") || fam.contains("H.265")) return "H.265（更省流量）";
        if (fam.equalsIgnoreCase("av1")) return "AV1（最新，部分手机不支持）";
        return fam;
    }

    /** 本机可解轨道里的最高高度；0 = 还不知道 */
    int codecMaxPlayableHeight() {
        try {
            JSONArray ab = codecAbilityArr;
            if (ab == null) return 0;
            int best = 0;
            for (int i = 0; i < ab.length(); i++) {
                JSONObject o = ab.optJSONObject(i);
                if (o == null) continue;
                if (o.optBoolean("playable", true)) {
                    int h = o.optInt("height", 0);
                    if (h > best) best = h;
                }
            }
            return best;
        } catch (Throwable ignore) { return 0; }
    }

    /**
     * 【v4.9 解码能力】展示本视频的解码清单。
     *
     * 清单内容：每一档清晰度分别有哪些编码轨道（AVC/H.265/AV1），
     * 以及本机 MediaCodecList 是否支持该编码。
     *
     * 【为什么这个功能重要】
     * 同一支视频 B 站会同时给 AVC / HEVC / AV1 多条轨。
     * 用户把清晰度拉到 4K 却只拿到 1080P，通常不是网络问题，
     * 而是「4K 只有 AV1 轨，而本机解码器不支持 AV1」。
     * 以前这个过程是黑箱（用户只会觉得「软件有 bug」），
     * 现在把能力清单直接摊开，一眼就能看懂机器的上限在哪。
     */
    void showCodecDialog() {
        final JSONArray ab = codecAbilityArr;
        if (ab == null || ab.length() == 0) {
            new AlertDialog.Builder(this)
                    .setTitle("解码能力")
                    .setMessage("尚未获取到本视频的轨道信息。\n\n"
                            + "DASH 高清流需要登录或视频提供多码率轨道时才会显示。")
                    .setPositiveButton("知道了", null)
                    .show();
            return;
        }
        StringBuilder sb = new StringBuilder();
        int mh = codecMaxPlayableHeight();
        sb.append("这台手机最高能播：").append(mh > 0 ? heightName(mh) : "未知").append("\n");
        sb.append("选超过上限的画质也不会出错，会自动用能播的那一档。\n\n");
        sb.append("本视频提供的画质（✓ = 能播）：\n");
        for (int i = 0; i < ab.length(); i++) {
            JSONObject o = ab.optJSONObject(i);
            if (o == null) continue;
            int hgt = o.optInt("height", 0);
            boolean ok = o.optBoolean("playable", true);
            sb.append(ok ? "  ✓ " : "  ✗ ").append(heightName(hgt));
            sb.append("  ").append(codecPlain(o.optString("codec", "")));
            sb.append("\n");
        }
        sb.append("\n提示：同一画质可能有好几种编码（H.264 / H.265 / AV1），\n")
          .append("手机支持哪一种，就用哪一种，播不出来时换低一档画质即可。");
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("画质说明");
        b.setMessage(sb.toString());
        b.setPositiveButton("知道了", null);
        b.show();
    }

    /**
     * 【v4.9】playurl 拿到后刷新解码能力清单。
     * 只有真正拿到 DASH 轨道才有清单，合流模式（durl）本身就是单一 mp4，无需展示。
     */
    void refreshCodecAbility(JSONObject playData) {
        try {
            JSONArray ab = BiliApi.codecAbility(playData);
            codecAbilityArr = (ab != null && ab.length() > 0) ? ab : null;
            if (codecBtn == null) return;
            if (codecAbilityArr == null) {
                codecBtn.setVisibility(View.GONE);
                return;
            }
            codecBtn.setVisibility(View.VISIBLE);
            // 【v4.9.6】按钮上只留一句最容易懂的结论：这台手机最高能播到几 K。
            int mh = codecMaxPlayableHeight();
            codecBtn.setText(mh > 0 ? "最高可播 " + heightName(mh) : "画质说明");
            codecBtn.setTextColor(0xB3FFFFFF);
        } catch (Throwable ignore) { }
    }

    /** 切清晰度：360p / 兼容模式走合流，其余走 DASH 高清双流 */
    /**
     * 【v4.18.0】切清晰度重写：无损、不叠音、不从头播。
     *
     * 旧版的三个毛病：
     *  ① 合流模式下 `resumePos = 0`（只有 dashMode 才取位置）→ 切档后直接从头播；
     *  ② 旧音轨没停就发新请求，取址那 1~2 秒里旧声音还在放，之后新音轨再起一遍
     *     → 听起来就是「重复播放 / 不同步」；
     *  ③ 新旧两路解码器短暂并存，部分机型上新的那路直接不出画面。
     *
     * 现在：立刻把旧音轨静音并释放（画面继续停在原处），记下真实进度与播放状态，
     * 新地址一到就 seek 回原位、按原状态继续。
     */
    void applyQuality() {
        int q = Prefs.quality(this);
        int dec = Prefs.decoder(this);
        int pos = 0;
        boolean playing = true;
        try {
            pos = video.getCurrentPosition();
            playing = video.isPlaying();
        } catch (Throwable ignore) { }
        // ① 旧音轨立即静音，避免和新音轨叠在一起；旧画面轨的报错也一并静默
        silenceOldPlayer();
        try {
            if (audioPlayer != null) audioPlayer.setVolume(0f, 0f);
        } catch (Throwable ignore) { }
        releaseAudio();
        // ② 位置与状态带到新的一路
        resumePos = pos > 800 ? pos : 0;
        pendingResumeMs = resumePos;
        switchPausePending = !playing;
        dashFailed = false;

        // ③ 【v4.18.0 秒切】同一视频的播放数据里本来就含所有清晰度轨道
        //（playurl 按最高可用请求），直接在本地重选一条即可 —— 零网络往返。
        // 旧版每次都重新请求 playurl，光取址就要 0.3~1 秒，还会让两条流短暂并存。
        JSONObject pd = playDataCache;
        if (pd != null && dashMode && q != Prefs.Q_360P) {
            try {
                String[] vs = BiliApi.pickVideo(pd, Prefs.qualityHeight(q));
                String[] as = BiliApi.pickAudio(pd);
                playGen = proxy.newEpoch();
                if (bvidText != null) bvidText.setText(bvid + " · " + vs[3]);
                startDash(vs[0], as[0], pd, Integer.parseInt(vs[1]));
                return;
            } catch (Throwable ignore) {
                // 缓存里没有这一档（如 4K 需大会员）→ 回落重新取址
            }
        }

        qualitySwitchPending = true;
        if (q == Prefs.Q_360P) {
            play(3);
        } else {
            play(dec == Prefs.DEC_LOW ? 3 : 1);
        }
    }

    // ------------------------------------------------------------ 播放

    /**
     * 【v4.18.0】起播请求线程池。
     * 旧版每次 play() 都 new Thread()：连点推荐时会有好几个线程同时在跑
     * playurl + pages，抢线程、抢网络，最后只有最后一个有效，纯属浪费。
     * 固定 3 条线程足够（同一时刻只有一个视频需要取址），多余的排队。
     */
    static final java.util.concurrent.ExecutorService PLAY_POOL =
            java.util.concurrent.Executors.newFixedThreadPool(3,
                    new java.util.concurrent.ThreadFactory() {
                        @Override public Thread newThread(Runnable r) {
                            Thread t = new Thread(r, "bxq-play");
                            t.setPriority(Thread.NORM_PRIORITY + 2);
                            return t;
                        }
                    });

    void play(final int mode) {
        if (proxy == null) {
            showToastSafe("本地代理启动失败");
            // 【v4.9】代理起不来 → 不会触发 onPrepared → 海报要手动撤掉
            fadeSwipeSlots();
            resetSwipeSlots();
            return;
        }
        muxRetry = 0;   // 每次新播放重置重试计数
        playStartMs = android.os.SystemClock.uptimeMillis();
        // 【v4.9】每次播放都要能处理一次海报淡出（翻页后新视频的 onPrepared 会调用）
        swipePosterHidden = false;
        frameWatchArmed = false;   // 【v4.9.1】每个视频重新挂一次画面自检
        // 【v4.9.4】加载框不再立刻上屏：竖屏刷流本身有封面海报兜底，延迟 3000ms 才弹，
        // 正常网络下首帧先到 → 用户全程看不到任何加载提示。普通详情页 160ms 也够快。
        final boolean immersive = swipeMode || portraitFill;
        // 【v4.13.7】普通详情页 160ms → 900ms：正常 4G/5G 下首帧常在 1 秒内到，
        // 延长延迟让加载框「根本来不及出现」，避免用户看到一闪而过的「加载中喵~」。
        // 沉浸刷流仍保持 3000ms（有封面海报兜底）。
        scheduleLoadAnim(playLoadMsg, immersive ? 3000 : 900);
        playLoadMsg = "加载中喵~";   // 一次性：用完即恢复默认文案
        tipText.setText(immersive ? "" : "获取播放地址…");
        // 【v4.18.0】开新一代：上一路视频/音轨还在下载的分片会被代理立刻掐断，
        // 新视频独占带宽，这是「点哪播哪」能落到 2 秒内的前提。
        if (proxy != null) playGen = proxy.newEpoch();
        final int gen = playGen;
        final String currentBv = bvid;
        // 【v4.7】在 UI 线程快照，避免工作线程读到半更新的状态
        final long snapCid = curCid;
        final String snapBv = curBv;
        final String snapTitle = videoTitle;
        final long snapDurSec = pageDurMs / 1000;
        PLAY_POOL.execute(new Runnable() { @Override public void run() {
            try {
                String bv = currentBv;
                String aid = null;
                if (bv.startsWith("av")) {
                    aid = bv.substring(2);
                    bv = null;
                }
                String realBvid;
                long cid;
                String pageTitle;
                long durSec = 0;
                long knownCid = prefetchedCid;
                String knownTitle = prefetchedTitle;
                long knownDur = prefetchedDur;
                // 【v4.7】切过视频后 prefetchedCid 已清零；若仍是同一个 bvid 且已有 curCid，
                // 直接复用（切清晰度不必再走一次 pages 往返，也少一次风控风险）
                if (knownCid <= 0 && snapCid > 0 && pageIndex == 0
                        && bv != null && bv.equals(snapBv)) {
                    knownCid = snapCid;
                    knownTitle = snapTitle;
                    knownDur = snapDurSec;
                }
                if (bv != null && knownCid > 0 && pageIndex == 0) {
                    // 【v4.1 提速】列表页已带 cid，直接用，跳过 pages() 网络往返
                    // 分P信息由 loadVideoMeta 后台补齐，不阻塞起播
                    cid = knownCid;
                    realBvid = bv;
                    pageTitle = (knownTitle != null && !knownTitle.isEmpty())
                            ? knownTitle : bv;
                    durSec = knownDur;
                } else if (bv != null) {
                    // 无预取 cid（如深链/搜索）：走 pagelist（抗风控更好，还能拿到分P时长）
                    final JSONArray pgs = BiliApi.pages(bv);
                    if (pageIndex >= pgs.length()) pageIndex = 0;
                    ui.post(new Runnable() { @Override public void run() {
                        pagesArr = pgs;
                        if (nextBtn != null) nextBtn.setAlpha(pgs.length() > 1 ? 1f : 0.35f);
                    } });
                    JSONObject pg = pgs.getJSONObject(pageIndex);
                    cid = pg.getLong("cid");
                    realBvid = bv;
                    pageTitle = pg.optString("part", bv);
                    durSec = pg.optLong("duration", 0);
                } else {
                    // av 号需要 view 反查 bvid
                    JSONObject v = BiliApi.view(null, aid);
                    realBvid = v.optString("bvid", "");
                    JSONObject p0 = v.getJSONArray("pages").getJSONObject(0);
                    cid = p0.getLong("cid");
                    pageTitle = v.optString("title", realBvid);
                    durSec = p0.optLong("duration", 0);
                }
                History.add(PlayActivity.this, realBvid, pageTitle);
                // 续播：本地有进度且当前没有「切清晰度时保留的位置」，就从上次位置继续
                if (resumePos <= 0) {
                    int saved = PlayPos.pos(PlayActivity.this, realBvid);
                    if (saved > 5000) {
                        resumePos = saved;
                        pendingResumeMs = saved;
                    }
                }
                final String bvF = realBvid;
                final long cidF = cid;
                final long durMs = durSec * 1000;

                // ① 高清：DASH 音视频分离流（4K / 2K / 1080P60 / 1080P）
                int q = Prefs.quality(PlayActivity.this);
                boolean wantDash = (mode == 0 || mode == 1) && !dashFailed && q != Prefs.Q_360P;
                if (wantDash) {
                    try {
                        // 【v4.18.0】一律请求「最高可用」，一次把 4K/2K/1080/720 全部拿回来。
                        // 这样切清晰度只要在本地重选轨道，不用再发一次网络请求 —— 秒切。
                        JSONObject pd = BiliApi.playurl(bvF, null, cidF, BiliApi.QN_ALL);
                        final String[] vs = BiliApi.pickVideo(pd, Prefs.qualityHeight(q));
                        final String[] as = BiliApi.pickAudio(pd);
                        final JSONObject pdF = pd;
                        final int maxH = BiliApi.maxVideoHeight(pd);
                        ui.post(new Runnable() { @Override public void run() {
                            // 【v4.7】连点推荐会并发多个 play()，旧视频晚返回的结果必须丢弃，
                            // 否则会把刚切过去的新视频又覆盖回旧视频
                            if (!bvF.equals(bvid)) return;
                            playDataCache = pdF;
                            pageDurMs = durMs;
                            curCid = cidF;
                            curBv = bvF;
                            bvidText.setText(bvF + " · " + vs[3]);
                            tipText.setText("");
                            dmBvid = bvF;
                            // 【v4.9】展示本视频的解码能力清单
                            refreshCodecAbility(pdF);
                            loadDanmaku(cidF);
                            loadVideoMeta(bvF);
                            // 【v3.9 四线路】带上 playData，DASH 音/画面也能逐条切换
                            startDash(vs[0], as[0], pdF, Integer.parseInt(vs[1]));
                            if (q == Prefs.Q_4K && maxH < 2000) {
                                showToastSafe("该视频/账号暂无 4K，已用 " + vs[3]);
                            } else if (q == Prefs.Q_2K && maxH < 1400) {
                                showToastSafe("该视频/账号暂无 2K，已用 " + vs[3]);
                            }
                        } });
                        return;
                    } catch (Throwable de) {
                        dashFailed = true;
                        CrashHandler.note("dash", "高清流不可用：" + de);
                    }
                }

                // ② 合流：兼容模式 / 兼容 360p / DASH 失败兜底（统一走系统解码器）
                // 【v3.9 四线路】一次取回最多 4 条 CDN，代理逐条尝试，某条不通自动切换
                String[] lines = (mode == 3) ? BiliApi.playDurlMp4Multi(bvF, cidF, true)
                                             : BiliApi.playDurlMp4Multi(bvF, cidF, false);
                final String local = proxy.playUrlMulti(lines, gen);
                final String title = bvF + " · 合流";
                ui.post(new Runnable() { @Override public void run() {
                    if (!bvF.equals(bvid)) return;   // 【v4.7】丢弃已被切换掉的旧视频结果
                    bvidText.setText(title);
                    tipText.setText("");
                    dmBvid = bvF;
                    curCid = cidF;
                    curBv = bvF;
                    pageDurMs = durMs;
                    // 【v4.9】合流模式没有多轨清单 → 清掉上一个视频的解码能力显示
                    refreshCodecAbility(null);
                    loadDanmaku(cidF);
                    loadVideoMeta(bvF);
                    startHard(local);
                } });
            } catch (final Throwable e) {
                ui.post(new Runnable() { @Override public void run() {
                    if (!currentBv.equals(bvid)) return;   // 【v4.7】旧视频的失败提示不要打扰新视频
                    showLoadAnim(false, null);
                    tipText.setText("播放失败：" + e.getMessage());
                    // 【v4.9 修复】翻页后若播放失败，onPrepared 永不触发 → 海报会永久盖屏。
                    // 失败路径必须把两槽复位，让用户看到真实的错误提示而不是一张封面。
                    fadeSwipeSlots();
                    resetSwipeSlots();
                } });
            }
        } });
    }

    // ------------------------------------------------------------ 高清双流

    /**
     * 【v4.17.1】Surface 重建后恢复到摘下前的播放状态。
     * 只有「摘下时本来就处于暂停」才暂停；正常播放走不到这里，不受影响。
     */
    void applySurfaceLostPause(MediaPlayer mp) {
        if (!surfaceLostPaused) return;
        surfaceLostPaused = false;
        try { if (mp != null && mp.isPlaying()) mp.pause(); } catch (Throwable ignore) { }
        try {
            if (audioPlayer != null && audioPlayer.isPlaying()) audioPlayer.pause();
        } catch (Throwable ignore) { }
        ui.post(new Runnable() { @Override public void run() {
            if (!isFinishing() && !swipeDestroyed) updatePlayIcon(false);
        } });
    }

    /** 视频轨给 VideoView（静音），音频轨单独一个 MediaPlayer，两路按进度校正 */
    void startDash(final String vUrl, final String aUrl) {
        startDash(vUrl, aUrl, null, 0);
    }

    /** 【v3.9 四线路】带 playData 的版本：从 DASH backupUrl 补充候选线路 */
    void startDash(final String vUrl, final String aUrl, final JSONObject pd, final int height) {
        dashMode = true;
        player = null;   // v4.5：本轮 DASH 是否已 prepared 以 player 是否为空为准，看门狗据此判超时
        // 【v4.18.0】新地址已就绪，先把旧解码会话彻底交还系统再切源：
        // 两个硬件解码器并存时，部分机型新的那路会「有声音没画面」。
        try { video.reset(); } catch (Throwable ignore) { }
        // 【v4.18.0】切清晰度期间画面一直在播，取址耗时 1~2 秒，
        // 真正切换的那一刻位置已经往前走了 —— 以此刻的位置为准，才不会「跳回去重播」。
        if (qualitySwitchPending) {
            qualitySwitchPending = false;
            try {
                int p = video.getCurrentPosition();
                if (p > 800) resumePos = p;
            } catch (Throwable ignore) { }
        }
        video.setVisibility(View.VISIBLE);
        vlcSurface.setVisibility(View.GONE);
        // 先挂回调再 setVideoPath：VideoView 的 prepare 是异步的，顺序反了有概率收不到 onPrepared
        video.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override public void onPrepared(MediaPlayer mp) {
                player = mp;
                noteStartupCost("DASH");
                showLoadAnim(false, null);   // 画面就绪，收起跳舞动画
                hideSwipePoster();           // 【v4.9】撤掉翻页海报，露出真实画面（不黑屏）
                armFrameWatchdog();          // 【v4.9.1】画面自检，防「有声音没画面」
                try { mp.setVolume(0f, 0f); } catch (Throwable ignore) { }  // 画面轨本身无音轨
                mp.start();
                applyZoom();
                if (resumePos > 800) {
                    try { mp.seekTo(resumePos); } catch (Throwable ignore) { }
                    pendingAudioStartPos = resumePos;   // 【v4.6】音频也直接从断点起，别从头播
                    resumePos = 0;
                    announceResume();
                }
                applySurfaceLostPause(mp);
                if (rate != 1f) {
                    Compat.applyRate(mp, rate);   // 【v4.11.0】低版本兼容
                }
                // 【v4.18.0】切清晰度前是暂停态 → 切完继续暂停，不要自己播起来
                if (switchPausePending) {
                    switchPausePending = false;
                    try { mp.pause(); } catch (Throwable ignore) { }
                    updatePlayIcon(false);
                    return;   // 暂停态不用起音频
                }
                // 【v4.6】不在这里直接放音频：onPrepared 只代表流已就绪，首帧还要等解码器出图。
                // 改为等「画面进度真正推进（首帧已出图）」后再放音频，音画同帧；300ms 后兜底。
                startAudioDeferred(aUrl, pd);
                awaitFirstFrameThenStartAudio(0);
            }
        });
        video.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override public boolean onError(MediaPlayer mp, int what, int extra) {
                fallbackMux("高清流解码失败");
                return true;
            }
        });
        try {
            String[] vLines = BiliApi.videoLines(pd, vUrl, height);
            video.setVideoPath(proxy.playUrlMulti(vLines, playGen));
        } catch (Throwable e) {
            fallbackMux("高清流打开失败");
            return;
        }
        // 【v4.5 关键修复】看门狗必须在「准备阶段」就启动。旧版放在 onPrepared 里，
        // 一旦编码不被支持导致 onPrepared 永不触发，看门狗就永远不启动 → 无限卡在加载页。
        armDashWatchdog();
        // 【v4.18.0 视频优先】不再和音轨并行抢带宽：先让视频轨独占网络把首帧拉出来，
        // 音轨在画面 prepared 之后再起（见 onPrepared 里的 startAudioDeferred）。
        // 实测「画面先到」比「声音先到」体感快得多，总起播时长也更短。
        video.start();
        applyZoom();
    }

    /**
     * 【v4.18.0】换片/切档前，先把上一路播放器的出错回调换成一个「静默吞掉」的。
     *
     * 为什么需要：新版换代时会主动 disconnect 上一代的所有连接，旧播放器正在读的
     * 分片会立刻报错。若它还挂着旧的 onError，就会触发 fallbackMux → 重新取址 →
     * 把刚切过去的新视频又打断，表现为「切了又跳回上一个」「反复重播」。
     * 这里让旧播放器安静地失败，新的一路随后会重新挂上自己的回调。
     */
    void silenceOldPlayer() {
        try {
            if (video != null) {
                video.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                    @Override public boolean onError(MediaPlayer mp, int what, int extra) {
                        return true;   // 已处理：不弹框、不回退、不打断新播放
                    }
                });
            }
        } catch (Throwable ignore) { }
    }

    /**
     * 【v4.18.0】记录一次起播耗时。正常网络下目标：点开到 prepared ≤ 2 秒。
     * 看日志里 perf 那几行，能直接判断慢在「取址」「建连」还是「解码器初始化」。
     */
    void noteStartupCost(String mode) {
        try {
            if (playStartMs <= 0) return;
            long cost = android.os.SystemClock.uptimeMillis() - playStartMs;
            CrashHandler.note("perf", mode + " 首帧 " + cost + "ms · " + bvid);
            playStartMs = 0;
        } catch (Throwable ignore) { }
    }

    /**
     * 【v4.18.0】音轨延后起播：先让视频轨把首帧拉出来，再拉音轨。
     * 视频先出画的体感比「声音先响、画面晚到」好得多，也避免两路同时抢带宽。
     */
    void startAudioDeferred(final String aUrl, final JSONObject pd) {
        if (aUrl == null || aUrl.isEmpty()) return;
        ui.postDelayed(new Runnable() { @Override public void run() {
            if (isFinishing() || swipeDestroyed || !dashMode || player == null) return;
            startAudio(aUrl, pd);
        } }, 60);
    }

    /** 【v3.9 四线路】音轨也按 backupUrl 提供多线路 */
    void startAudio(final String aUrl, final JSONObject pd) {
        try {
            releaseAudio();
            audioPlayer = new MediaPlayer();
            // 【v4.17.0】走媒体通道的音频属性：让系统按「影视」处理这条流，
            // 音量键、音频焦点、来电暂停都更正确，也避免占用通话通道降质。
            // 必须在 prepareAsync 之前设置（AudioAttributes 决定了输出路由）。
            try {
                android.media.AudioAttributes aa = new android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build();
                audioPlayer.setAudioAttributes(aa);
            } catch (Throwable ignore) {
                try { audioPlayer.setAudioStreamType(android.media.AudioManager.STREAM_MUSIC); }
                catch (Throwable ignore2) { }
            }
            String[] aLines = BiliApi.audioLines(pd == null ? playDataCache : pd, aUrl);
            audioPlayer.setDataSource(this, Uri.parse(proxy.playUrlMulti(aLines, playGen)));
            audioPlayer.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override public void onPrepared(MediaPlayer mp) {
                    // 【v4.6】音轨与画面轨是并行 prepare 的，但视频分片（fMP4 首片+关键帧）通常比音频慢。
                    // 旧版在这里直接 mp.start()，于是"声音先出来、画面晚一秒"。现在只标记就绪，
                    // 由画面 onPrepared 触发 startAudioIfVideoReady() 对齐起播。
                    audioPrepared = true;
                    startAudioIfVideoReady();
                }
            });
            audioPlayer.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override public boolean onError(MediaPlayer mp, int what, int extra) {
                    return true;   // 音频轨出问题不崩，画面继续
                }
            });
            audioPlayer.prepareAsync();
        } catch (Throwable e) {
            fallbackMux("音轨加载失败");
        }
    }

    /**
     * 【v4.6】等画面真正出首帧后再放音频。
     * 不覆盖 VideoView 内部的 OnInfoListener（它会破坏缓冲状态机），改用进度轮询：
     * 进度 > 0 说明解码器已出图；或累计 300ms 兜底（个别机型首帧慢/进度不更新）。
     */
    void awaitFirstFrameThenStartAudio(final int attempt) {
        if (!audioPrepared || audioPlayer == null) return;   // 音频还没备好，由音频自己 onPrepared 时放行
        if (dashMode && player == null) return;              // 画面被回退/重建，放弃等待
        int pos = -1;
        try { pos = video.getCurrentPosition(); } catch (Throwable ignore) { }
        if (pos > 0 || attempt >= 12) {
            startAudioIfVideoReady();
            return;
        }
        ui.postDelayed(new Runnable() { @Override public void run() {
            awaitFirstFrameThenStartAudio(attempt + 1);
        } }, 25);
    }

    /**
     * 【v4.6】音画同步起播：音频可以提前 prepare 完，但必须等画面 onPrepared 后才出声。
     * - 音频先备好 → 静音待命，画面就绪时由 onPrepared 再调本方法；
     * - 画面先就绪 → 音频后到时按画面当前位置对齐后再起播。
     */
    void startAudioIfVideoReady() {
        if (!audioPrepared || audioPlayer == null) return;
        if (dashMode && player == null) return;   // 画面还没 prepared，继续静音等待
        audioPrepared = false;
        try {
            int target = pendingAudioStartPos;
            pendingAudioStartPos = -1;
            if (target < 0) {
                try { target = video.getCurrentPosition(); } catch (Throwable ignore) { target = 0; }
            }
            if (target > 400) audioPlayer.seekTo(target);   // 起播初期无需 seek，避免多一次跳转
            if (rate != 1f) {
                Compat.applyRate(audioPlayer, rate);   // 【v4.11.0】低版本兼容
            }
            audioPlayer.start();
            audioStarted = true;
            lastAudioSync = System.currentTimeMillis();
            CrashHandler.note("play", "audio start @v=" + target + "ms");
        } catch (Throwable e) {
            CrashHandler.note("play", "audio start failed: " + e);
        }
    }

    void releaseAudio() {
        try {
            if (audioPlayer != null) {
                audioPlayer.reset();
                audioPlayer.release();
            }
        } catch (Throwable ignore) { }
        audioPlayer = null;
        audioPrepared = false;
        audioStarted = false;
        pendingAudioStartPos = -1;
    }

    /**
     * 【v4.13.7】看门狗分级超时：旧版 5 秒一刀切，高码率 1080p 首帧本就可能要 6~8 秒，
     * 一到 5 秒就回退合流 → 重新取址、重新拉流，用户看到的是「转一圈又重来」的二次卡顿。
     * 现在：8 秒仍没 prepared 才判定流拉不下来；只要 player 已就绪就给足首帧时间，
     * 进度停滞改由 armFrameWatchdog 的无帧检测处理，避免重复回退。
     */
    void armDashWatchdog() {
        ui.postDelayed(new Runnable() { @Override public void run() {
            if (!dashMode) return;
            // 已经 prepared → 交给首帧看门狗，不在这里过早回退
            if (player != null) return;
            // 8 秒过去 player 仍为空 = onPrepared 从未触发（编码不支持/流确实拉不下来）
            fallbackMux("高清流准备超时，已切回兼容播放");
        } }, 8000);
    }

    /** 高清失败 → 自动回退合流，不让用户看黑屏 */
    void fallbackMux(final String why) {
        if (!dashMode && dashFailed) return;
        dashMode = false;
        dashFailed = true;
        releaseAudio();
        ui.post(new Runnable() { @Override public void run() {
            if (isFinishing()) return;
            tipText.setText(why + "，已切回兼容播放");
            resumePos = 0;
            play(1);
        } });
    }

    /** 系统硬解路径（本地代理补 Referer） */
    void startHard(String localUrl) {
        startHard(localUrl, true);
    }

    /**
     * 【v3.9】合流播放 + 失败自动重试。
     * retryAllowed=false 时由重试调用，避免重复注册回调导致递归。
     */
    void startHard(String localUrl, final boolean retryAllowed) {
        dashMode = false;
        releaseAudio();
        try { video.reset(); } catch (Throwable ignore) { }
        // 【v4.18.0】从 DASH 切到 360P/合流时同样要接住进度
        if (qualitySwitchPending) {
            qualitySwitchPending = false;
            try {
                int p = video.getCurrentPosition();
                if (p > 800) resumePos = p;
            } catch (Throwable ignore) { }
        }
        video.setVisibility(View.VISIBLE);
        vlcSurface.setVisibility(View.GONE);
        video.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override public boolean onError(MediaPlayer mp, int what, int extra) {
                if (retryAllowed && muxRetry < MAX_MUX_RETRY) {
                    muxRetry++;
                    final int attempt = muxRetry;
                    ui.post(new Runnable() { @Override public void run() {
                        if (isFinishing()) return;
                        tipText.setText("切换线路 " + attempt + "/" + MAX_MUX_RETRY + " 重试中喵~");
                        // 重新请求播放地址（拿到不同调度节点）+ 重新播放
                        retryMux();
                    } });
                } else {
                    ui.post(new Runnable() { @Override public void run() {
                        if (!isFinishing()) {
                            showLoadAnim(false, null);
                            tipText.setText("播放失败，请检查网络后重试");
                        }
                    } });
                }
                return true;   // 自己处理，不让系统弹出错误框
            }
        });
        video.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override public void onPrepared(MediaPlayer mp) {
                player = mp;
                noteStartupCost("MUX");
                showLoadAnim(false, null);   // 画面就绪，收起跳舞动画
                hideSwipePoster();           // 【v4.9】撤掉翻页海报，露出真实画面（不黑屏）
                armFrameWatchdog();          // 【v4.9.1】画面自检，防「有声音没画面」
                muxRetry = 0;   // 成功打开，清零重试计数
                // 【v4.17.0】合流播放的音轨就在这条流里，给它套上媒体通道属性：
                // 音量键 / 音频焦点 / 来电暂停都按「影视」处理。
                try {
                    mp.setAudioAttributes(new android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE)
                            .build());
                } catch (Throwable ignore) { }
                // 【v4.1】续播 seek 放到 prepared 之后：此前在 start() 后立即 seek，
                // 视频尚未就绪，seek 会失败/重复缓冲，导致「看过的视频打开很慢」。
                int seekTo = resumePos;
                resumePos = 0;
                if (seekTo > 800) {
                    try {
                        mp.seekTo(seekTo);
                        if (pendingResumeMs <= 0) pendingResumeMs = seekTo;
                        announceResume();
                    } catch (Throwable ignore) { }
                }
                try { mp.start(); } catch (Throwable ignore) { }
                if (switchPausePending) {
                    switchPausePending = false;
                    try { mp.pause(); } catch (Throwable ignore) { }
                    updatePlayIcon(false);
                }
                applySurfaceLostPause(mp);
                applyZoom();
                if (rate != 1f) {
                    Compat.applyRate(mp, rate);   // 【v4.11.0】低版本兼容
                }
            }
        });
        try {
            video.setVideoPath(localUrl);
        } catch (Throwable e) {
            video.setOnErrorListener(null);
            if (retryAllowed && muxRetry < MAX_MUX_RETRY) {
                muxRetry++;
                retryMux();
            } else {
                ui.post(new Runnable() { @Override public void run() {
                    if (!isFinishing()) {
                        showLoadAnim(false, null);
                        tipText.setText("播放失败，请检查网络后重试");
                    }
                } });
            }
            return;
        }
        // 【v4.1】seek 已移到 onPrepared 内，此处不再过早 seek（否则触发重复缓冲拖慢起播）
    }

    /** 合流失败重试：后台重新取四线路地址，再走 startHard（禁止再次自动重试由计数控制） */
    void retryMux() {
        final String bv = bvid;
        new Thread(new Runnable() { @Override public void run() {
            try {
                long cid = curCid;
                int low = (Prefs.decoder(PlayActivity.this) == Prefs.DEC_LOW) ? 1 : 0;
                String[] lines = BiliApi.playDurlMp4Multi(bv, cid, low == 1);
                final String local = proxy.playUrlMulti(lines, playGen);
                ui.post(new Runnable() { @Override public void run() {
                    if (isFinishing()) return;
                    tipText.setText("");
                    video.stopPlayback();
                    startHard(local, true);
                } });
            } catch (final Throwable e) {
                ui.post(new Runnable() { @Override public void run() {
                    if (!isFinishing()) tipText.setText("重试失败：" + e.getMessage());
                } });
            }
        } }).start();
    }

    // ------------------------------------------------------------ 相关推荐

    // ------------------------------------------------------------ 【v4.9】竖屏上下滑翻页（抖音式）

    /**
     * 开启翻页模式（竖屏铺满时调用）。
     *
     * 【队列从哪来】抖音的竖屏流是「一条无限长的竖屏短视频队列」。
     * 本应用没有抖音那样的竖屏接口，但 B 站的 related / popular 都带
     * dimension（宽高比），可以筛出竖屏视频拼成队列。
     * 队列首项就是当前视频，向上滑取下一条，向下滑取上一条。
     */
    void enterSwipeMode() {
        if (swipeMode) return;
        swipeMode = true;
        try {
            // 【v4.9】swipeLayer 始终 VISIBLE（它是画面槽的父容器，不能 GONE），
            // 这里只复位两槽的位置，slotNext 由 resetSwipeSlots 负责隐藏。
            resetSwipeSlots();
            ensureSwipeFeed();
        } catch (Throwable e) {
            CrashHandler.note("swipe", "开启竖屏翻页失败：" + e);
        }
    }

    /** 关闭翻页模式（退出竖屏铺满 / 切到横屏视频时调用） */
    void exitSwipeMode() {
        swipeMode = false;
        swiping = false;
        swipeDy = 0f;
        swipeTarget = 0;
        try {
            // 【v4.9 修复】必须取消在途的翻页动画。
            // 否则动画的 update 回调会继续 applySwipeOffset，把已隐藏的相邻槽
            // 重新设成 VISIBLE 并填上黑底封面 —— 表现为「退出竖屏后详情页被黑块盖住」；
            // 而且动画结束还会执行 applySwipeSwitch 多切一次视频。
            cancelSwipeAnim();
            // 【v4.9】绝不能把 swipeLayer 设 GONE —— 它是 slotCur（画面）的父容器，
            // GONE 会让视频一起消失（表现为「退出铺满后画面空白」）。
            // 只复位两槽并隐藏 slotNext。
            resetSwipeSlots();
        } catch (Throwable ignore) { }
    }

    /**
     * 【v4.9】取消在途的翻页动画。
     * 动画持有 Activity 的 View 引用，页面销毁或退出竖屏时若不取消，
     * 它的回调会在不该再操作界面的时候继续跑。
     */
    void cancelSwipeAnim() {
        swipeAnimating = false;
        try {
            if (swipeAnim != null) {
                swipeAnim.cancel();
                swipeAnim = null;
            }
        } catch (Throwable ignore) { }
        swipeDy = 0f;
    }

    /** 【v4.19.0】手指开始拖动 → 暂停弹幕推进并暂时隐去弹幕层，把合成时间全留给位移动画 */
    void suspendDm() {
        if (dmSuspended) return;
        dmSuspended = true;
        try {
            if (dmView != null) dmView.setVisibility(View.INVISIBLE);
        } catch (Throwable ignore) { }
    }

    /** 【v4.19.0】翻页结束 → 恢复弹幕；用户关掉了弹幕就保持隐藏 */
    void resumeDm() {
        if (!dmSuspended) return;
        dmSuspended = false;
        try {
            if (dmView != null) dmView.setVisibility(dmOn ? View.VISIBLE : View.GONE);
        } catch (Throwable ignore) { }
    }

    /** 两槽复位到「当前在上、下一槽在下方一个屏高处」的标准位置 */
    void resetSwipeSlots() {
        try {
            resumeDm();
            int h = swipeScreenH();
            if (slotCur != null) slotCur.setTranslationY(0f);
            if (slotNext != null) {
                slotNext.setTranslationY(h);
                // 隐藏相邻槽：它是黑底不透明的，留着会挡住下面的画面
                slotNext.setVisibility(View.GONE);
            }
            if (slotPrev != null) {
                slotPrev.setTranslationY(-h);
                slotPrev.setVisibility(View.GONE);
            }
            if (slotNextCover != null) {
                slotNextCover.setAlpha(1f);
                slotNextCover.setImageDrawable(null);
                slotNextCover.setVisibility(View.GONE);
            }
            if (slotNextTitle != null) {
                slotNextTitle.setText("");
                slotNextTitle.setVisibility(View.GONE);
            }
            if (slotPrevCover != null) {
                slotPrevCover.setAlpha(1f);
                slotPrevCover.setImageDrawable(null);
                slotPrevCover.setVisibility(View.GONE);
            }
            if (slotPrevTitle != null) {
                slotPrevTitle.setText("");
                slotPrevTitle.setVisibility(View.GONE);
            }
        } catch (Throwable ignore) { }
    }

    /** 翻页容器可视高度（一个屏高） */
    int swipeScreenH() {
        try {
            int h = swipeLayer != null ? swipeLayer.getHeight() : 0;
            if (h <= 0) h = holder != null ? holder.getHeight() : 0;
            if (h <= 0) h = getResources().getDisplayMetrics().heightPixels;
            return h;
        } catch (Throwable e) {
            return getResources().getDisplayMetrics().heightPixels;
        }
    }

    /**
     * 构建竖屏滑动队列。
     *
     * 【数据源策略（实测）】
     *   related（当前视频的相似内容）：从竖屏视频出发时约 80% 是竖屏 → 主力来源
     *   popular（热门榜单）：仅约 16% 竖屏 → 只作补充
     *
     * 【队列不足时怎么办】
     *   竖屏视频滑到 related 的末尾 → 再拉 popular 补；
     *   还不够 → 换一批 popular 页。宁可多翻一页热门，也要保证「一直能滑下去」。
     */
    void ensureSwipeFeed() {
        if (!swipeMode) return;
        if (swipeFeedLoading) return;
        if (swipeFeed.size() >= SWIPE_FEED_MIN
                && System.currentTimeMillis() - swipeFeedStamp < 5 * 60 * 1000L) {
            // 队列还够用，只做一次预取刷新
            prefetchSwipeNeighbors();
            return;
        }
        swipeFeedLoading = true;
        final String curBv = bvid;
        new Thread(new Runnable() { @Override public void run() {
            final ArrayList<VideoItem> got = new ArrayList<>();
            try {
                // ① 相关推荐（主力：竖屏密度最高）
                try {
                    JSONArray arr = BiliApi.related(curBv);
                    collectSwipeItems(arr, got);
                } catch (Throwable ignore) { }
                // ② 热门补充（related 不够长时）
                for (int pg = 1; pg <= 3 && got.size() < SWIPE_FEED_MIN; pg++) {
                    try {
                        JSONArray arr = BiliApi.popular(pg);
                        collectSwipeItems(arr, got);
                    } catch (Throwable ignore) { }
                }
            } catch (Throwable ignore) { }
            ui.post(new Runnable() { @Override public void run() {
                swipeFeedLoading = false;
                // 【v4.9】页面已销毁时不要再去碰界面
                if (isFinishing() || isDestroyed()) return;
                swipeFeedStamp = System.currentTimeMillis();
                if (got.isEmpty()) return;
                // 合并去重（保留原顺序）
                java.util.LinkedHashMap<String, VideoItem> uniq =
                        new java.util.LinkedHashMap<String, VideoItem>();
                for (VideoItem it : swipeFeed) uniq.put(it.bvid, it);
                for (VideoItem it : got) if (!uniq.containsKey(it.bvid)) uniq.put(it.bvid, it);
                swipeFeed = new ArrayList<VideoItem>(uniq.values());
                // 当前视频定位（找不到就以队首为当前）
                swipeIndex = -1;
                for (int i = 0; i < swipeFeed.size(); i++) {
                    if (swipeFeed.get(i).bvid.equals(curBv)) { swipeIndex = i; break; }
                }
                if (swipeIndex < 0 && !swipeFeed.isEmpty()) swipeIndex = 0;
                prefetchSwipeNeighbors();
            } });
        } }, "swipe-feed").start();
    }

    /**
     * 从接口数组里挑出可滑动的条目（带 cid、带宽高比）。
     *
     * 【实测数据】从竖屏视频出发调 related，返回的 40 条里 32 条是竖屏（80%）；
     * 而 popular 榜单只有 16%。所以 related 才是竖屏流的主要来源，
     * popular 只在 related 不够用时补充。
     *
     * 【为什么只要竖屏】滑动队列是抖音式「一屏一个竖屏短视频」的体验，
     * 混进横屏视频会让翻页时突然变成黑边横屏，体验断裂。
     * 所以这里优先收竖屏（宽高比 < 1），实在不够才放宽收横屏兜底。
     */
    void collectSwipeItems(JSONArray arr, ArrayList<VideoItem> out) {
        if (arr == null) return;
        ArrayList<VideoItem> vertical = new ArrayList<>();
        ArrayList<VideoItem> horizontal = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String bv = o.optString("bvid", "");
            if (bv.isEmpty()) continue;
            long cid = o.optLong("cid", 0);
            if (cid <= 0) continue;      // 没有 cid 的条目滑过去会多一次 pages() 往返，收益低
            float aspect = 0f;
            JSONObject dim = o.optJSONObject("dimension");
            if (dim != null) {
                int dw = dim.optInt("width", 0), dh = dim.optInt("height", 0);
                int rot = dim.optInt("rotate", 0);
                if (rot == 90 || rot == 270) { int t = dw; dw = dh; dh = t; }
                if (dw > 0 && dh > 0) aspect = dw / (float) dh;
            }
            String up = "";
            try {
                JSONObject ow = o.optJSONObject("owner");
                if (ow != null) up = ow.optString("name", "");
            } catch (Throwable ignore) { }
            VideoItem it = new VideoItem(bv,
                    BiliApi.cleanTitle(o.optString("title", "")),
                    up, o.optString("pic", ""),
                    cid, aspect, o.optInt("duration", 0));
            // aspect 未知（没 dimension）时按竖屏处理 —— 宁可滑过去看看，也不要队列空着
            if (aspect <= 0f || aspect < 1f) vertical.add(it);
            else horizontal.add(it);
        }
        // 竖屏优先入队；横屏仅在竖屏不足时补位
        for (VideoItem it : vertical) out.add(it);
        if (out.size() < SWIPE_FEED_MIN) {
            for (VideoItem it : horizontal) out.add(it);
        }
    }

    /**
     * 【v4.9 提速关键】预取相邻视频的播放地址 + 封面。
     * 滑动切换时 playurl 直接命中缓存 → 零网络等待，这是「丝滑」的根本保证。
     */
    void prefetchSwipeNeighbors() {
        if (!swipeMode || swipeFeed.isEmpty() || swipeIndex < 0) return;
        // 【v4.14.0】预取窗口 ±3：连滑时相邻项更快命中，避免滑到第 3 条才现场取址。
        // 缓存已扩到 32 槽，足以容纳，不会互相挤掉。
        for (int step = 1; step <= 3; step++) {
            int ni = swipeIndex + step;
            if (ni < swipeFeed.size()) {
                VideoItem nx = swipeFeed.get(ni);
                BiliApi.prefetchPlayurl(nx.bvid, nx.cid, BiliApi.QN_ALL);
                if (step == 1) prefetchCover(nx);   // 封面只预取紧邻的，省流量
            }
            int pi = swipeIndex - step;
            if (pi >= 0) {
                VideoItem pv = swipeFeed.get(pi);
                BiliApi.prefetchPlayurl(pv.bvid, pv.cid, BiliApi.QN_ALL);
                // 【v4.9 修复】上一槽的封面也必须预取 ——
                // 少了它，用户第一次下滑时看到的是黑底（ImgLoader.load 走网络，最长 8 秒）。
                if (step == 1) prefetchCover(pv);
            }
        }
        // 【v4.19.0 滑动手感关键】把相邻两槽的海报提前塞进 View：
        // 图片预取只保证「已在内存」，真正滑动的第一帧仍要 setImageBitmap + 纹理上传，
        // 这一步正好落在手指拖动最吃帧的时刻 —— 表现就是「第一下顿一下」。
        // 提前在这里贴好，拖动时只是位移，没有任何解码/上传开销。
        prepareSwipePreview(slotNext, 1);
        prepareSwipePreview(slotPrev, -1);
    }

    /**
     * 【v4.13.14 边看边加载】当前视频首帧出画后，后台预热紧邻下一条的播放线路。
     * 只复用已预取的 playurl 地址（不额外打 API），让本地代理做一次极小线路探测，
     * 提前把最快 CDN 写入亲和缓存。用户下滑到该视频时命中快路径、跳过全量竞速。
     * 全程低优先级 + 错峰 + 不下载视频数据，不抢当前视频带宽。
     */
    void warmupNextLine() {
        if (!swipeMode || proxy == null || swipeFeed.isEmpty() || swipeIndex < 0) return;
        final VideoItem nx = swipeNeighbor(1);    // 紧邻下一条
        final VideoItem pv = swipeNeighbor(-1);   // 紧邻上一条（下滑看上一个 / 回退）
        if ((nx == null || nx.cid <= 0) && (pv == null || pv.cid <= 0)) return;
        new Thread(new Runnable() { @Override public void run() {
            try {
                // 【v4.13.14】低优先级 + 错峰 200ms：当前视频首帧已出、带宽刚空闲，
                // 这时探一下相邻两条的线路最划算；【v4.19.0】800ms → 200ms，
                // 并同时探「上一条」—— 让「用哪条 CDN 最快」在用户抬手前就定好。
                Thread.currentThread().setPriority(Thread.NORM_PRIORITY - 1);
                Thread.sleep(200L);
                if (swipeDestroyed || isFinishing() || isDestroyed()) return;
                warmupOne(nx);
                warmupOne(pv);
            } catch (Throwable ignore) { }
        }}, "bxq-warmup").start();
    }

    /**
     * 【v4.19.0】用已预取的 playurl 探测一条最快线路（只握手读 1KB，不拉视频数据）。
     * 命中后 LocalProxy 会把这台 CDN 记成该流的亲和线路，真正起播直接走它，省掉竞速。
     */
    void warmupOne(VideoItem it) {
        if (it == null || it.cid <= 0 || proxy == null) return;
        try {
            int q = Prefs.quality(this);
            int qn = Prefs.qualityQn(q);
            String ck = BiliApi.urlCacheKey(it.bvid, it.cid, qn);
            if (ck == null) return;
            // 复用预取地址（takeUrlCache 命中不删除，正式播放仍可命中）
            JSONObject pd = BiliApi.takeUrlCache(ck);
            if (pd == null) return;   // 预取没到位就跳过，不自己发请求抢带宽
            String[] vs = BiliApi.pickVideo(pd, Prefs.qualityHeight(q));
            String[] lines = BiliApi.videoLines(pd, vs[0], Integer.parseInt(vs[1]));
            proxy.warmup(lines);
        } catch (Throwable ignore) { }
    }

    /** 预取封面到内存缓存（ImgLoader 有 LRU，滑到时直接命中，不再等网络） */
    void prefetchCover(VideoItem it) {
        if (it == null || it.pic == null || it.pic.isEmpty()) return;
        try {
            // 【v4.13.12】竖屏全屏海报用高清封面；预取与加载同一 URL 才能命中 LRU
            final String u = it.thumbHi();
            if (u == null) return;
            ImgLoader.prefetch(u, getResources().getDisplayMetrics().widthPixels,
                    getResources().getDisplayMetrics().heightPixels);
        } catch (Throwable ignore) { }
    }

    /**
     * 取第 n 个相邻条目（n=+1 下一个，n=-1 上一个）；越界返回 null。
     *
     * 【越界处理】滑到队列末尾时不能「滑不动」——
     * 那正是抖音式体验最扫兴的地方。这里在越界时立刻后台补一批新条目，
     * 用户继续滑就能接着往下走（第一次滑到边界会略有停顿，
     * 补货完成后就是连续的无限流）。
     */
    VideoItem swipeNeighbor(int n) {
        if (swipeFeed.isEmpty() || swipeIndex < 0) return null;
        int i = swipeIndex + n;
        if (i >= 0 && i < swipeFeed.size()) {
            VideoItem it = swipeFeed.get(i);
            if (it != null && !it.isPlaceholder()) return it;
        }
        // 越界 → 补货（后台异步，不阻塞当前手势）
        if (n > 0) requestMoreSwipeFeed();
        return null;
    }

    /**
     * 往滑动队列尾部追加更多内容。
     * 用当前视频的 related 续上（保证内容同源、竖屏率高），
     * 不够再用 popular 补。
     */
    void requestMoreSwipeFeed() {
        if (swipeFeedLoading) return;
        swipeFeedLoading = true;
        final String curBv = bvid;
        new Thread(new Runnable() { @Override public void run() {
            final ArrayList<VideoItem> got = new ArrayList<>();
            try {
                try {
                    JSONArray arr = BiliApi.related(curBv);
                    collectSwipeItems(arr, got);
                } catch (Throwable ignore) { }
                if (got.size() < 6) {
                    try {
                        JSONArray arr = BiliApi.popular(1 + (swipeFeed.size() % 5));
                        collectSwipeItems(arr, got);
                    } catch (Throwable ignore) { }
                }
            } catch (Throwable ignore) { }
            ui.post(new Runnable() { @Override public void run() {
                swipeFeedLoading = false;
                // 【v4.9】页面已销毁时不要再去碰界面
                if (isFinishing() || isDestroyed()) return;
                if (got.isEmpty()) return;
                java.util.LinkedHashMap<String, VideoItem> uniq =
                        new java.util.LinkedHashMap<String, VideoItem>();
                for (VideoItem it : swipeFeed) uniq.put(it.bvid, it);
                int before = swipeFeed.size();
                for (VideoItem it : got) if (!uniq.containsKey(it.bvid)) uniq.put(it.bvid, it);
                swipeFeed = new ArrayList<VideoItem>(uniq.values());
                // 追加后预取新的尾部（用户马上要滑到的那几条）
                if (swipeFeed.size() > before) prefetchSwipeNeighbors();
            } });
        } }, "swipe-more").start();
    }

    /**
     * 手指按下：准备跟手滑动。
     * 【手势仲裁】竖屏铺满时纵向滑动归「翻页」，
     * 原来的「左右半屏纵向调亮度/音量」改为横向拖动触发 —— 功能不丢，只是换个方向。
     *
     * 【v4.9】动画进行中也要调用（用于重置基准点），但不能动 swipeDy / swiping，
     * 否则会把正在跑的动画打断、槽位突然跳回原位。
     * 【v4.9.5】同时给 GestureDetector 补一个 ACTION_CANCEL，取消已排定的长按计时器：
     * 只说「swipeMode 下 onLongPress 直接 return」还不够，若手势在进入翻页前一刻按下，
     * 计时器已排上，这里主动掐断更保险。
     */
    void cancelLongPressTimer(MotionEvent ev) {
        try {
            if (gesture == null || ev == null) return;
            MotionEvent c = MotionEvent.obtain(ev);
            c.setAction(MotionEvent.ACTION_CANCEL);
            gesture.onTouchEvent(c);
            c.recycle();
        } catch (Throwable ignore) { }
    }

    void swipeOnDown(float x, float y) {
        // 【v4.9.5】防御：上一个手势若留下 2× 倍速（长按误触后被滑动分支抢走 UP），
        // 这里无条件复位，保证每一帧都是原速起手。
        if (boosting) {
            boosting = false;
            if (speedTag != null) speedTag.setVisibility(View.INVISIBLE);
            setRate(1.0f);
        }
        swipeLastY = y;
        swipeLastT = System.currentTimeMillis();
        swipeVel = 0f;
        if (swipeAnimating) return;    // 动画中：只更新基准点
        swipeDy = 0f;
        swiping = false;
        swipeTarget = 0;
        swipeDownTime = System.currentTimeMillis();
    }

    float swipeLastY = 0f;
    long swipeLastT = 0;

    /** 手指移动：跟手更新位移 */
    void swipeOnMove(float y) {
        if (!swipeMode || swipeAnimating) return;
        int h = swipeScreenH();
        float dy = y - swipeLastY;
        long now = System.currentTimeMillis();
        // 速度估计（用于抬手时判断是否该切）
        if (now > swipeLastT) {
            float inst = dy / (now - swipeLastT) * 1000f;
            swipeVel = swipeVel * 0.6f + inst * 0.4f;
        }
        swipeLastY = y;
        swipeLastT = now;
        swipeDy += dy;
        // 限制在 ±1.2 个屏高，避免无限拖
        float lim = h * 1.2f;
        if (swipeDy > lim) swipeDy = lim;
        if (swipeDy < -lim) swipeDy = -lim;
        if (!swiping && Math.abs(swipeDy) > dp(10)) swiping = true;
        // 【v4.19.0】真开始拖了才暂停弹幕，轻点不动时不打扰
        if (swiping) suspendDm();
        applySwipeOffset();
    }

    /**
     * 把当前位移应用到两槽。
     *
     * 【双向翻页的几何】
     *   上滑（dy<0）看下一个：下一槽在当前槽**下方**，即 translationY = dy + h
     *     → dy 从 0 走到 -h 时，下一槽从屏幕下方 h 处滑到 0，正好顶上来。
     *   下滑（dy>0）看上一个：上一槽在当前槽**上方**，即 translationY = dy - h
     *     → dy 从 0 走到 +h 时，上一槽从屏幕上方 -h 处滑到 0，正好压下来。
     *
     * 【为什么要两个槽】只有一个 slotNext 的话，上滑时它在下方、下滑时它得跳到上方，
     * 中途会闪一下。分成 slotNext（下方）和 slotPrev（上方）两个槽就没有这个问题。
     */
    void applySwipeOffset() {
        try {
            if (slotCur != null) slotCur.setTranslationY(swipeDy);
            int h = swipeScreenH();
            // 【v4.9 修复】不能用 swipeDy == 0f 判断「回到原位」——
            // 浮点累加几乎不可能精确等于 0，那是个永不成立的死分支。
            // 改用 1px 阈值。
            boolean atRest = Math.abs(swipeDy) < 1f;
            if (slotNext != null) {
                slotNext.setTranslationY(swipeDy + h);
                if (swipeDy < 0) {
                    if (slotNext.getVisibility() != View.VISIBLE) {
                        slotNext.setVisibility(View.VISIBLE);
                        prepareSwipePreview(slotNext, 1);
                    }
                } else if (atRest && slotNext.getVisibility() == View.VISIBLE) {
                    slotNext.setVisibility(View.GONE);
                }
            }
            if (slotPrev != null) {
                slotPrev.setTranslationY(swipeDy - h);
                if (swipeDy > 0) {
                    if (slotPrev.getVisibility() != View.VISIBLE) {
                        slotPrev.setVisibility(View.VISIBLE);
                        prepareSwipePreview(slotPrev, -1);
                    }
                } else if (atRest && slotPrev.getVisibility() == View.VISIBLE) {
                    slotPrev.setVisibility(View.GONE);
                }
            }
        } catch (Throwable ignore) { }
    }

    /**
     * 准备预览槽的内容：按方向取目标视频的封面 + 标题。
     * 封面已在预取阶段下载进内存 LRU，这里直接命中，不会卡。
     */
    void prepareSwipePreview(FrameLike slot, int dir) {
        if (slot == null || dir == 0) return;
        VideoItem target = swipeNeighbor(dir);
        if (target == null) return;
        try {
            ImageView cover = slot == slotNext ? slotNextCover : slotPrevCover;
            TextView title = slot == slotNext ? slotNextTitle : slotPrevTitle;
            if (cover != null) {
                cover.setAlpha(1f);
                String u = target.thumbHi();   // 【v4.13.12】竖屏海报清晰化
                if (u != null) {
                    ImgLoader.load(cover, u,
                            getResources().getDisplayMetrics().widthPixels,
                            getResources().getDisplayMetrics().heightPixels);
                    cover.setVisibility(View.VISIBLE);
                }
            }
            if (title != null && target.title != null && !target.title.isEmpty()) {
                title.setText(target.title);
                // 【v4.19.0】标题改由底部信息条统一承载，海报上不再重复贴字
                title.setVisibility(View.GONE);
            }
        } catch (Throwable ignore) { }
    }

    /**
     * 手指抬起：按位移与速度决定是否切换。
     * 切：把当前槽滑出、下一槽滑入，同时真正切换视频内容。
     * 不切：两槽弹回原位。
     */
    void swipeOnUp() {
        if (!swipeMode || swipeAnimating) return;
        int h = swipeScreenH();
        float dy = swipeDy;
        // 阈值：位移超过 1/4 屏高，或速度够快（>600px/s）且位移超过 60px
        boolean fast = Math.abs(swipeVel) > 600f && Math.abs(dy) > dp(60);
        boolean far = Math.abs(dy) > h * 0.25f;
        int dir = 0;
        if (dy < 0) dir = 1;      // 上滑 → 下一个
        else if (dy > 0) dir = -1; // 下滑 → 上一个
        if ((fast || far) && dir != 0) {
            VideoItem target = swipeNeighbor(dir);
            if (target != null) {
                swipeTarget = dir;
                animateSwipeSwitch(target, dir, h);
                return;
            }
        }
        // 不切 → 弹回
        animateSwipeBack();
    }

    /** 弹回原位（未达切换阈值） */
    void animateSwipeBack() {
        swipeAnimating = true;
        final float from = swipeDy;
        android.animation.ValueAnimator a =
                android.animation.ValueAnimator.ofFloat(from, 0f);
        // 【v4.13.7】弹回加 Q 弹：越过 0 点轻微回弹再归位，手感更活泼
        a.setDuration(300);
        a.setInterpolator(new android.view.animation.OvershootInterpolator(1.6f));
        swipeAnim = a;   // 【v4.9】登记在途动画，退出时统一取消
        a.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(android.animation.ValueAnimator an) {
                swipeDy = (Float) an.getAnimatedValue();
                applySwipeOffset();
            }
        });
        a.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator an) {
                swipeAnimating = false;
                swipeAnim = null;
                swipeDy = 0f;
                applySwipeOffset();
                resetSwipeSlots();
            }
        });
        a.start();
    }

    /**
     * 【v4.9】手势被系统打断（ACTION_CANCEL）→ 弹回原位，不翻页。
     */
    void swipeCancel() {
        swipeTarget = 0;
        animateSwipeBack();
    }

    /**
     * 真正翻页：动画把当前槽滑出、下一槽滑入，动画结束（或中途）切换视频内容。
     * 【无缝衔接】切换后 slotNext 里那张封面正好停在屏幕中央成为新视频的「海报」，
     * 等新视频首帧渲染出来再把海报撤掉 —— 全程不黑屏。
     */
    void animateSwipeSwitch(final VideoItem target, final int dir, final int h) {
        swipeAnimating = true;
        // 【v4.14.0 起播与动画并行】翻页动画 220ms 期间就让取址在后台跑起来，
        // 动画结束时 play() 直接命中缓存 → 省掉「动画→取址」的串行等待。
        // 这是竖屏连滑「卡一下」的关键来源之一。
        if (target != null && target.cid > 0) {
            BiliApi.prefetchPlayurl(target.bvid, target.cid, BiliApi.QN_ALL);
        }
        // 【v4.13.12 起播前置】不再等 220ms 动画结束后才切视频：
        // 动画一开始就切换内容并起播，让「取地址→建连→CDN 竞速→首帧解码」与位移动画并行。
        // 动画结束时首帧往往已就绪，海报随即淡出 → 几乎不再停留在糊封面上。
        // posterPendingAfterAnim 保证海报淡出必须等动画归位，避免中途露黑屏。
        if (swipeMode && !swipeDestroyed && !isFinishing() && !isDestroyed()) {
            posterPendingAfterAnim = false;
            applySwipeSwitch(target, dir);
        }
        final float from = swipeDy;
        final float to = (dir > 0) ? -h : h;   // 上滑 → 当前槽完全移出上方
        // 下一槽的目标位置：从 (from + h) 滑到 0
        android.animation.ValueAnimator a =
                android.animation.ValueAnimator.ofFloat(from, to);
        a.setDuration(220);
        a.setInterpolator(new android.view.animation.DecelerateInterpolator());
        swipeAnim = a;   // 【v4.9】登记在途动画，退出时统一取消
        a.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(android.animation.ValueAnimator an) {
                swipeDy = (Float) an.getAnimatedValue();
                applySwipeOffset();
            }
        });
        a.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator an) {
                swipeAnimating = false;
                swipeAnim = null;
                // 【v4.9 修复】翻页后必须把 swipeDy 归零。
                // 否则它停在 ±h（一个屏高），下一次抬手判定会拿这个旧值去比阈值，
                // 结果「刚翻完页又白翻一页」。
                swipeDy = 0f;
                // 【v4.9 修复】取消动画会同步触发 onAnimationEnd，
                // 若此时已退出竖屏铺满，就不该再执行切换（否则用户退出后还在起播新视频）。
                if (!swipeMode) return;
                // 页面已销毁 → 不再操作界面
                if (swipeDestroyed || isFinishing() || isDestroyed()) return;
                // 【v4.13.12】视频已在动画开始时切换并起播，这里不再重复切。
                // 若首帧在动画期间就已就绪（海报被暂缓淡出），动画归位后补做淡出。
                if (posterPendingAfterAnim) {
                    posterPendingAfterAnim = false;
                    hideSwipePoster();
                }
            }
        });
        a.start();
    }

    /**
     * 翻页落定：真正切到目标视频。
     * 关键顺序：
     *  1) 先把 slotNext 的封面/标题换成目标视频的（它已经在屏幕中央，直接当海报用）
     *  2) 再切视频内容 → 新视频首帧渲染出来时海报被盖住，全程无黑屏
     *  3) 复位两槽
     */
    void applySwipeSwitch(VideoItem target, int dir) {
        // 相邻槽换成目标视频的封面 + 标题
        try {
            // 【v4.9】按方向选槽：上滑用 slotNext（从下方顶上来的），
            // 下滑用 slotPrev（从上方压下来的）。此时它正停在屏幕中央，
            // 直接当新视频的「海报」用 —— 盖住首帧渲染前的空白，全程不黑屏。
            FrameLike slot = (dir > 0) ? slotNext : slotPrev;
            ImageView cover = (dir > 0) ? slotNextCover : slotPrevCover;
            TextView title = (dir > 0) ? slotNextTitle : slotPrevTitle;
            if (slot != null) {
                slot.setVisibility(View.VISIBLE);
                slot.setAlpha(1f);
            }
            if (cover != null && target.pic != null && !target.pic.isEmpty()) {
                String u = target.thumbHi();   // 【v4.13.12】竖屏海报清晰化
                if (u != null) {
                    cover.setAlpha(1f);
                    ImgLoader.load(cover, u,
                            getResources().getDisplayMetrics().widthPixels,
                            getResources().getDisplayMetrics().heightPixels);
                    cover.setVisibility(View.VISIBLE);
                }
            }
            if (title != null && target.title != null && !target.title.isEmpty()) {
                title.setText(target.title);
                // 【v4.19.0】标题改由底部信息条统一承载，海报上不再重复贴字
                title.setVisibility(View.GONE);
            }
        } catch (Throwable ignore) { }

        // 真正切换内容（走与点推荐同一套逻辑，保证标题/弹幕/互动都同步）
        int newIndex = swipeIndex + dir;
        swipeIndex = newIndex;
        swipeTarget = 0;
        // 【v4.9】本次切换要处理一次海报淡出
        swipePosterHidden = false;
        // 【v4.9】切视频前先把海报留在屏幕上：新视频首帧未出时用户看到的是封面，不是黑屏
        swipeSwitchVideo(target);
    }

    /**
     * 翻页切视频：复用 switchVideo 的状态重置逻辑，但不做翻书动画、不退出竖屏铺满。
     *
     * 【v4.9 提速】队列条目自带 title / cid / 宽高比 / 时长，
     * 所以标题、UP主、进度条时长这些「要等 meta 才知道」的东西可以立刻填上，
     * 不用等 loadVideoMeta 的网络往返回来 —— 画面一出来信息就已经是对的。
     */
    void swipeSwitchVideo(VideoItem target) {
        // 【v4.9.6】翻页换视频时，评论抽屉里还是上一条的评论，必须先收起
        if (commentSheet != null && commentSheet.isShowing()) commentSheet.hide();
        String bv = target.bvid;
        if (bv == null || bv.isEmpty() || bv.equals(bvid)) {
            // 目标就是当前视频 → 直接复位
            resetSwipeSlots();
            return;
        }
        bvid = bv;
        cached = false;
        prefetchedCid = target.cid;
        prefetchedTitle = target.title != null ? target.title : "";
        prefetchedDur = target.durSec;
        prefetchedAspect = target.aspect;
        pageIndex = 0;
        pagesArr = null;
        resumePos = 0;
        pendingResumeMs = 0;
        dashFailed = false;
        playDataCache = null;
        pageDurMs = target.durSec * 1000;

        // 【v4.9】先用队列里的元数据把界面填上，不等 meta
        try {
            if (target.title != null && !target.title.isEmpty()) {
                videoTitle = target.title;
                vTitle = target.title;
                if (titleTop != null) titleTop.setText(target.title);
                if (bvidText != null) bvidText.setText(target.title);
                if (pTitle != null) pTitle.setText(target.title);
                // 【v4.19.0】底部信息条立刻换成新视频的标题，不等 meta 往返
                if (vTitleTxt != null) vTitleTxt.setText(target.title);
            }
            if (target.sub != null && !target.sub.isEmpty()) {
                vUp = target.sub;
                if (pUp != null) pUp.setText("UP主 · " + target.sub);
                if (vUpTxt != null) vUpTxt.setText("UP主 · " + target.sub);
            }
            if (target.aspect > 0f) {
                videoAspect = target.aspect;
                portraitVideo = target.aspect < 1f / 1.15f;
            }
            // 封面也先铺上（背景虚化图用）；竖屏沉浸模式下这张图根本不显示，别再解码一张全屏大图
            if (target.pic != null && !target.pic.isEmpty() && bgCover != null && !portraitFill) {
                String cu = ImgLoader.https(target.pic);
                ImgLoader.load(bgCover, cu,
                        getResources().getDisplayMetrics().widthPixels, dp(400));
            }
        } catch (Throwable ignore) { }

        releaseAudio();
        silenceOldPlayer();
        // 【v4.18.0】这里只暂停、保留最后一帧：真正释放旧解码器放到
        // startDash/startHard 的开头（新地址已就绪那一瞬），黑屏时间最短。
        try { if (video != null) video.pause(); } catch (Throwable ignore) { }
        playLoadMsg = "加载中喵~";
        tipText.setText("");
        play(Prefs.decoder(this));
        // 立刻预取新的相邻项，保证下一次滑动同样零等待
        prefetchSwipeNeighbors();
    }

    /**
     * 视频首帧渲染出来后，把「海报」（slotNext 里的封面）撤掉，露出真实画面。
     * 由 startDash / startHard 的 onPrepared 调用。
     *
     * 【v4.9 修复】如果用户此刻正在拖动（swiping），不能硬拉两槽回位 ——
     * 那会把正在进行的翻页动画打断、封面突然闪没。
     * 这种情况下只把海报淡出，两槽位置等用户抬手后由 swipeOnUp 统一收尾。
     */
    /**
     * 【v4.9.1】画面自检看门狗。
     * 「有声音、没画面」的典型成因是 MediaPlayer 的渲染目标丢了：
     * 音频照常走、解码也在推进，但 Surface 没接上，屏幕永远黑。
     * 这里在 2.5 秒后检查：如果进度在走却一帧都没渲染过，就重新绑一次 Surface，
     * 再给 1.6 秒观察，仍然不行就明确提示用户（而不是让用户对着黑屏猜）。
     */
    void armFrameWatchdog() {
        if (frameWatchArmed) return;
        frameWatchArmed = true;
        ui.postDelayed(new Runnable() { @Override public void run() {
            if (swipeDestroyed || isFinishing() || isDestroyed()) return;
            try {
                if (video == null || video.hasRenderedFrame()) return;
                if (!video.isPlaying() && video.getCurrentPosition() <= 0) return;
                video.rebindSurface();
                ui.postDelayed(new Runnable() { @Override public void run() {
                    if (swipeDestroyed || isFinishing() || isDestroyed()) return;
                    try {
                        if (video != null && !video.hasRenderedFrame()) {
                            // 【v4.12 竖屏加载修复】rebind 之后 1.6s 仍一帧未出，
                            // 说明这条分离流在本机「有声音没画面」，继续等只会一直
                            // 卡在「加载中喵~」（竖屏高码率轨尤其常见）→ 直接降级合流
                            if (dashMode) {
                                fallbackMux("画面连接异常，已切回兼容播放");
                            } else {
                                showToastSafe("画面加载异常，已尝试重新连接");
                                try { video.rebindSurface(); } catch (Throwable ignore) { }
                            }
                        }
                    } catch (Throwable ignore) { }
                } }, 1600);
            } catch (Throwable ignore) { }
        } }, 2500);
    }

    void hideSwipePoster() {
        if (swipePosterHidden) return;
        // 【v4.13.12】起播前置后，首帧可能在翻页动画还没结束时就已就绪。
        // 此时若立刻淡出海报，会露出仍在移动中的空槽 → 一闪黑屏。
        // 推迟到动画 onAnimationEnd 归位后再淡出，衔接才平滑。
        if (swipeAnimating) {
            posterPendingAfterAnim = true;
            return;
        }
        swipePosterHidden = true;
        // 【v4.13.14】当前视频首帧就绪 → 后台预热紧邻下一条线路（边看边加载）
        warmupNextLine();
        // 【v4.9】只有屏幕上真的有海报时才需要等首帧。
        // 普通播放（没翻页）时两个槽都是 GONE，没必要起轮询空转。
        boolean hasPoster = (slotNext != null && slotNext.getVisibility() == View.VISIBLE)
                || (slotPrev != null && slotPrev.getVisibility() == View.VISIBLE);
        if (!hasPoster) {
            fadeSwipeSlots();
            return;
        }
        awaitSwipeFirstFrameThenHide(0);
    }

    /**
     * 轮询等待首帧渲染完成后再淡出海报。
     * 【v4.9 改用 TextureView 回调判定】原来靠「播放进度 > 0」判断，
     * 但续播视频 getCurrentPosition() 会直接返回 seek 目标值（画面还没出帧就已经 >0），
     * 导致海报提前淡出、露出残帧。现在改看 TxVideoView 的真实出帧标志。
     */
    void awaitSwipeFirstFrameThenHide(final int attempt) {
        ui.postDelayed(new Runnable() { @Override public void run() {
            if (swipeDestroyed || isFinishing() || isDestroyed()) return;
            boolean ready = false;
            try {
                if (video != null) ready = video.hasRenderedFrame();
            } catch (Throwable ignore) { }
            // 最多等 2 秒（80 次 × 25ms），超时也淡出，避免海报卡住不消失
            if (ready || attempt >= 80) {
                fadeSwipeSlots();
            } else {
                awaitSwipeFirstFrameThenHide(attempt + 1);
            }
        } }, 25);
    }

    /** 淡出两个相邻槽的海报，露出真实画面 */
    void fadeSwipeSlots() {
        try {
            // 【v4.9 修复】两个槽的淡出是并发的，若各自的 end-action 都调 resetSwipeSlots，
            // 先完成的那个会把另一个正在淡出的海报瞬间清掉（海报一闪就没）。
            // 所以用计数：需要动画的槽全部淡完后才统一复位。
            swipeFadePending = 0;
            swipeFadePending += fadeSwipeSlot(slotNext, slotNextCover, slotNextTitle);
            swipeFadePending += fadeSwipeSlot(slotPrev, slotPrevCover, slotPrevTitle);
            swipeFadeCheck();
        } catch (Throwable ignore) { }
    }

    /**
     * 淡出某个相邻槽的封面（海报），露出真实画面。
     * 返回 1 表示这个槽走了动画（结束时会回调 swipeFadeCheck），0 表示无需等待。
     */
    int fadeSwipeSlot(final FrameLike slot, final ImageView cover, final TextView title) {
        if (slot == null || cover == null) return 0;
        try {
            if (cover.getVisibility() == View.VISIBLE) {
                cover.animate().alpha(0f).setDuration(160)
                        .withEndAction(new Runnable() { @Override public void run() {
                            try {
                                cover.setAlpha(1f);
                                cover.setVisibility(View.GONE);
                                cover.setImageDrawable(null);
                                if (title != null) title.setVisibility(View.GONE);
                                slot.setVisibility(View.GONE);
                                swipeFadePending--;
                                swipeFadeCheck();
                            } catch (Throwable ignore) { }
                        } }).start();
                return 1;
            }
            if (slot.getVisibility() == View.VISIBLE) slot.setVisibility(View.GONE);
            return 0;
        } catch (Throwable ignore) { return 0; }
    }

    /** 所有海报都淡出完成后统一复位两槽 */
    void swipeFadeCheck() {
        if (swipeFadePending > 0) return;
        try {
            // 正在拖动时不复位，等抬手后统一收尾
            if (!swiping && !swipeAnimating) resetSwipeSlots();
        } catch (Throwable ignore) { }
    }

    void switchVideo(VideoItem it) {
        if (commentSheet != null && commentSheet.isShowing()) commentSheet.hide();
        if (it == null || it.isPlaceholder()) return;
        switchVideo(it.bvid, it.cid, it.durSec, it.title);
    }

    /**
     * 【v4.8】cid/title/durSec 来自推荐列表接口，直接复用可跳过 pages() 网络往返。
     * cid<=0 时自动回落到 pages() 现查。
     */
    void switchVideo(String bv, long cid, long durSec, String title) {
        if (bv == null || bv.isEmpty() || bv.equals(bvid)) return;
        bvid = bv;
        cached = false;
        // 【v4.7 关键修复】切视频必须整体替换上一个视频的预取状态。
        // 旧版这里没清 prefetchedCid，导致 play() 拿「新 bvid + 旧 cid」请求 playurl，
        // B站判定 cid 不属于该视频，直接返回 -404「啥都木有」。
        prefetchedCid = cid;                       // 新视频的 cid（0 = 未知，走 pages）
        prefetchedTitle = title != null ? title : "";
        prefetchedDur = durSec;
        prefetchedAspect = 0f;
        pageIndex = 0;          // 新视频从第 1P 开始
        pagesArr = null;        // 丢弃上个视频的分P列表
        resumePos = 0;          // 否则会沿用上个视频的续播进度
        surfaceLostPaused = false;   // 【v4.17.1】换片时清掉上一次的暂停兜底状态
        pendingResumeMs = 0;
        dashFailed = false;     // 上个视频 DASH 失败不能连累新视频
        playDataCache = null;
        pageDurMs = 0;          // 进度条总时长先清空，避免显示成上个视频的时长
        if (fullscreen) exitFullscreen();
        if (portraitFill) exitPortraitFill();
        resetZoom();
        // 【v4.8】立即停掉旧视频（含独立音轨），否则网络等待的几百毫秒里旧视频还在出声，
        // 观感上就是「新视频加载中、旧视频还在播」的错乱。
        releaseAudio();
        silenceOldPlayer();
        // 【v4.18.0】这里只暂停、保留最后一帧：真正释放旧解码器放到
        // startDash/startHard 的开头（新地址已就绪那一瞬），黑屏时间最短。
        try { if (video != null) video.pause(); } catch (Throwable ignore) { }
        playLoadMsg = "加载中喵~";
        tipText.setText("");
        play(Prefs.decoder(this));
        // 【v4.9】翻页切换不再重拉推荐列表：
        // 竖屏铺满时页面下方的推荐列表根本不可见，重拉纯属浪费，
        // 还会跟滑动预取抢带宽、拖慢切换。推荐列表留给普通详情页的点选切换用。
    }

    /**
     * 【v4.18.0】给列表前 n 条预热播放地址（只请求 playurl，不拉视频数据，不抢带宽）。
     * 错开 700ms 执行，避免和当前视频的起播抢同一条网络。
     */
    void prefetchRows(final ArrayList<VideoItem> rows, final int n) {
        if (rows == null || rows.isEmpty()) return;
        ui.postDelayed(new Runnable() { @Override public void run() {
            if (isFinishing() || swipeDestroyed) return;
            int done = 0;
            for (int i = 0; i < rows.size() && done < n; i++) {
                VideoItem it = rows.get(i);
                if (it == null || it.isPlaceholder() || it.cid <= 0) continue;
                if (it.bvid == null || !it.bvid.startsWith("BV")) continue;
                if (it.bvid.equals(bvid)) continue;   // 正在播的不用预热
                BiliApi.prefetchPlayurl(it.bvid, it.cid, BiliApi.QN_ALL);
                done++;
            }
        } }, 700);
    }

    void loadRec() {
        final String currentBv = bvid;
        new Thread(new Runnable() { @Override public void run() {
            final ArrayList<VideoItem> got = new ArrayList<>();
            try {
                JSONArray arr = null;
                if (currentBv != null && currentBv.startsWith("BV")) {
                    try { arr = BiliApi.related(currentBv); } catch (Throwable ignore) { }
                }
                if (arr == null || arr.length() == 0) arr = BiliApi.popular(1);   // 降级热门
                if (arr != null) {
                    for (int i = 0; i < arr.length() && got.size() < 20; i++) {
                        JSONObject o = arr.getJSONObject(i);
                        String bv = o.optString("bvid", "");
                        if (bv.isEmpty() || bv.equals(currentBv)) continue;
                        String up = o.optJSONObject("owner") != null
                                ? o.getJSONObject("owner").optString("name", "") : "";
                        int dur = o.optInt("duration", 0);
                        long play = o.optJSONObject("stat") != null
                                ? o.getJSONObject("stat").optLong("view", 0) : 0;
                        StringBuilder sub = new StringBuilder();
                        if (!up.isEmpty()) sub.append(up);
                        if (play > 0) {
                            if (sub.length() > 0) sub.append("  ·  ");
                            sub.append(VideoItem.views(play)).append("播放");
                        }
                        if (dur > 0) {
                            if (sub.length() > 0) sub.append("  ·  ");
                            sub.append(VideoItem.dur(dur));
                        }
                        // 【v4.8 提速】related/popular 返回项自带 cid，存下来给播放页直接用，
                        // 切视频时省掉一次 pages() 网络往返（约 150~600ms）
                        long rc = o.optLong("cid", 0);
                        float ra = 0f;
                        JSONObject rdim = o.optJSONObject("dimension");
                        if (rdim != null) {
                            int dw = rdim.optInt("width", 0), dh = rdim.optInt("height", 0);
                            int rot = rdim.optInt("rotate", 0);
                            if (rot == 90 || rot == 270) { int t = dw; dw = dh; dh = t; }
                            if (dw > 0 && dh > 0) ra = dw / (float) dh;
                        }
                        got.add(new VideoItem(bv,
                                BiliApi.cleanTitle(o.optString("title", "")),
                                sub.toString(), o.optString("pic", ""),
                                rc, ra, dur));
                    }
                }
            } catch (Throwable ignore) { }
            ui.post(new Runnable() { @Override public void run() {
                // 【v4.8】连点切换时，旧视频的推荐列表晚返回会把新列表冲掉
                if (!currentBv.equals(bvid)) return;
                recRows.clear();
                recRows.addAll(got);
                if (got.isEmpty()) {
                    recRows.add(new VideoItem("", "暂无推荐（网络或风控原因）", "", null));
                }
                if (recAdapter != null) recAdapter.notifyDataSetChanged();
                // 【v4.18.0】预热前几条推荐：点下去时 playurl 直接命中缓存，
                // 省掉 300~800ms 取址往返，配合代理换代取消，观感就是「点哪播哪」。
                prefetchRows(got, 3);
            } });
        } }).start();
    }

    // ------------------------------------------------------------ 缓存 MP4

    void cacheVideo() {
        if (cached) {
            showToastSafe("本视频已缓存过");
            return;
        }
        cached = true;
        showToastSafe("开始缓存 MP4…");
        new Thread(new Runnable() { @Override public void run() {
            File work = new File(getCacheDir(), "dl");
            File out = new File(work, "cache.mp4");
            File vf = new File(work, "v.m4s");
            File af = new File(work, "a.m4s");
            try {
                work.mkdirs();
                deleteQuiet(out); deleteQuiet(vf); deleteQuiet(af);

                String bv = bvid;
                String aid = null;
                if (bvid.startsWith("av")) {
                    aid = bvid.substring(2);
                    bv = null;
                }
                String realBvid;
                long cid;
                String title;
                if (bv != null) {
                    JSONObject pg = BiliApi.firstPage(bv);
                    cid = pg.getLong("cid");
                    realBvid = bv;
                    title = pg.optString("part", bv);
                } else {
                    JSONObject v = BiliApi.view(null, aid);
                    realBvid = v.optString("bvid", "");
                    cid = v.getJSONArray("pages").getJSONObject(0).getLong("cid");
                    title = v.optString("title", realBvid);
                }

                boolean ok = false;
                try {
                    JSONObject data = BiliApi.playurl(realBvid, aid, cid);
                    String vurl = BiliApi.bestVideoSource(data);
                    String aurl = BiliApi.bestMp4Audio(data);   // 只取能进 MP4 的 AAC
                    setTipText("缓存中：视频流…");
                    download(vurl, vf, true);
                    setTipText("缓存中：音频流…");
                    download(aurl, af, true);
                    setTipText("封装 MP4…");
                    ok = Mp4Muxer.mux(vf, af, out);
                } catch (Throwable e) {
                    CrashHandler.note("cache", "DASH 方案失败：" + e);
                } finally {
                    deleteQuiet(vf); deleteQuiet(af);
                }

                if (!ok) {
                    // 回退：直接下载 html5 合一流 mp4
                    setTipText("缓存中：合一流…");
                    String u = BiliApi.playDurlMp4(realBvid, cid);
                    deleteQuiet(out);
                    download(u, out, true);
                }
                if (!out.exists() || out.length() < 1024) {
                    throw new Exception("下载内容为空（可能被风控）");
                }

                String name = sanitize(title) + ".mp4";
                setTipText("保存到相册…");
                saveToMovies(out, name);
                deleteQuiet(out);

                SharedPreferences sp = getSharedPreferences("video_cache", MODE_PRIVATE);
                java.util.Set<String> set = new java.util.HashSet<>(
                        sp.getStringSet("files", new java.util.HashSet<String>()));
                set.add(name);
                sp.edit().putStringSet("files", set).apply();

                setTipText("");
                ui.post(new Runnable() { @Override public void run() {
                    Toast.makeText(PlayActivity.this,
                            "✓ MP4 已保存到 电影/BiliAudio", Toast.LENGTH_LONG).show();
                } });
            } catch (final Throwable e) {
                cached = false;
                deleteQuiet(out);
                setTipText("");
                ui.post(new Runnable() { @Override public void run() {
                    Toast.makeText(PlayActivity.this,
                            "缓存失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
                } });
            }
        } }).start();
    }

    void setTipText(final String s) {
        ui.post(new Runnable() { @Override public void run() {
            if (tipText != null) tipText.setText(s);
        } });
    }

    static void deleteQuiet(File f) {
        try { if (f != null && f.exists()) f.delete(); } catch (Throwable ignore) { }
    }

    void download(String url, File dst) throws Exception {
        download(url, dst, false);
    }

    /**
     * 【v4.13.5】带断点续传重试的下载：横屏高清视频（DASH 大文件）在中途断流时，
     * 旧版直接 read 到 -1 就结束，文件不完整 → 缓存失败/封装失败。现在断流后用
     * Range: bytes=<已写>- 重新请求续写，最多重试 3 次；每次都追加到同一文件，
     * 服务端返回 206 即续传成功。连接/读取超时也做了收紧以便更快触发重试。
     */
    void download(String url, File dst, boolean report) throws Exception {
        final int MAX_TRY = 3;
        long total = 0;   // 已写入字节数（跨重试累计，作为续传起点）
        long last = 0;
        byte[] buf = new byte[256 * 1024];   // v4.13.5：64KB→256KB，大文件下载更快
        FileOutputStream fos = new FileOutputStream(dst, false);
        Exception lastErr = null;

        try {
            for (int attempt = 0; attempt < MAX_TRY; attempt++) {
                HttpURLConnection c = null;
                InputStream in = null;
                try {
                    c = (HttpURLConnection) new URL(url).openConnection();
                    c.setConnectTimeout(12000);
                    c.setReadTimeout(45000);
                    c.setRequestProperty("User-Agent", BiliApi.UA);
                    c.setRequestProperty("Referer", BiliApi.REFERER);
                    // 续传：从第 total 字节继续
                    if (total > 0) c.setRequestProperty("Range", "bytes=" + total + "-");
                    StringBuilder ck = new StringBuilder();
                    for (java.util.Map.Entry<String, String> e : BiliApi.COOKIES.entrySet()) {
                        if (ck.length() > 0) ck.append("; ");
                        ck.append(e.getKey()).append('=').append(e.getValue());
                    }
                    if (ck.length() > 0) c.setRequestProperty("Cookie", ck.toString());

                    int code = c.getResponseCode();
                    if (code != 200 && code != 206) {
                        throw new Exception("下载失败 HTTP " + code);
                    }
                    // 服务端不支持 Range（返回 200 但我们要续传）→ 从头覆盖，重置计数
                    if (total > 0 && code == 200) {
                        fos.close();
                        fos = new FileOutputStream(dst, false);
                        total = 0;
                    }
                    in = c.getInputStream();
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        fos.write(buf, 0, n);
                        total += n;
                        if (report && total - last > 4 * 1024 * 1024) {
                            last = total;
                            final String mb = String.format("%.1f MB", total / 1048576.0);
                            ui.post(new Runnable() { @Override public void run() {
                                if (tipText != null) tipText.setText("缓存中 " + mb);
                            } });
                        }
                    }
                    fos.flush();
                    // 正常读到 EOF → 下载完成
                    in.close(); in = null;
                    c.disconnect(); c = null;
                    return;
                } catch (Exception e) {
                    lastErr = e;
                    CrashHandler.note("cache", "下载第 " + (attempt + 1) + " 次中断：" + e.getMessage());
                } finally {
                    if (in != null) { try { in.close(); } catch (Exception ignore) { } }
                    if (c != null) { try { c.disconnect(); } catch (Exception ignore) { } }
                }
                // 到这里说明本次连接中断，短暂等待后重试续传
                if (attempt < MAX_TRY - 1) {
                    try { Thread.sleep(300); } catch (InterruptedException ignore) { }
                }
            }
            // 三次都没能正常读完 EOF：若已写到一些数据，视为可接受（服务端可能因连接关闭提前结束）；
            // 否则抛出最后一次错误
            if (total <= 0) {
                throw (lastErr != null) ? lastErr : new Exception("下载失败：无数据");
            }
        } finally {
            try { fos.close(); } catch (Exception ignore) { }
        }
    }

    void saveToMovies(File src, String displayName) throws Exception {
        if (android.os.Build.VERSION.SDK_INT < 29) {
            // 【v4.11.0】低版本没有 relative_path/is_pending，直接 File 写公共 Movies 目录
            Compat.savePublicLegacy(this, src, displayName,
                    Environment.DIRECTORY_MOVIES, "BiliAudio");
            return;
        }
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.Video.Media.DISPLAY_NAME, displayName);
        cv.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        Compat.putRelativePath(cv, Environment.DIRECTORY_MOVIES + "/BiliAudio");
        Compat.putPending(cv, 1);
        Uri uri = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv);
        if (uri == null) {
            File f = new File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), displayName);
            copyFile(src, f);
            return;
        }
        OutputStream os = getContentResolver().openOutputStream(uri);
        byte[] buf = new byte[64 * 1024];
        InputStream is = new java.io.FileInputStream(src);
        int n;
        while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
        is.close();
        os.close();
        cv.clear();
        Compat.putPending(cv, 0);
        getContentResolver().update(uri, cv, null, null);
    }

    static void copyFile(File src, File dst) throws Exception {
        java.io.FileInputStream is = new java.io.FileInputStream(src);
        FileOutputStream os = new FileOutputStream(dst);
        byte[] buf = new byte[32 * 1024];
        int n;
        while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
        is.close();
        os.close();
    }

    static String sanitize(String name) {
        String s = name.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", "_")
                      .replaceAll("^[ ._-]+|[ ._-]+$", "");
        if (s.isEmpty()) s = "untitled";
        return s.substring(0, Math.min(80, s.length()));
    }

    void showToastSafe(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    /**
     * 互动写操作的统一失败处理：登录过期时清掉本地失效 Cookie（含 SharedPreferences），
     * 让下一次点击走正常的「请先登录」流程，避免用户反复点却一直失败。
     */
    void handleActError(String err) {
        if (err == null) return;
        showToastSafe(err);
        if (err.contains("重新登录") || err.contains("登录已过期") || err.contains("登录态已失效")) {
            BiliApi.clearLoginCookie();
            try {
                getSharedPreferences("bili_login", MODE_PRIVATE).edit()
                        .remove("SESSDATA").remove("bili_jct").remove("DedeUserID").remove("login_time")
                        .apply();
            } catch (Throwable ignore) { }
        }
    }

    /** 把互动异常转成可读文案，登录态失效走专属提示 */
    String actErr(Throwable e) {
        if (e == null) return "操作失败，请重试";
        if (e instanceof BiliApi.LoginExpired || e.getMessage() == null) return "登录已过期，请到「我的」页重新登录";
        String m = e.getMessage();
        return m.isEmpty() ? "操作失败，请重试" : m;
    }

    @Override
    protected void onDestroy() {
        savePosNow();   // 退出前落一次进度
        ui.removeCallbacksAndMessages(null);
        // 【v4.9】翻页动画若在销毁瞬间还在跑，它的 onAnimationEnd 会回调
        // applySwipeSwitch → play()，在已销毁的页面上新建 MediaPlayer 和线程，
        // 没人释放。这里取消动画并置位，让回调自行短路。
        swipeDestroyed = true;
        cancelSwipeAnim();
        if (dmStyleDlg != null) {   // 【v4.8】避免退出时窗口泄漏
            try { dmStyleDlg.dismiss(); } catch (Throwable ignore) { }
            dmStyleDlg = null;
        }
        if (video != null) video.stopPlayback();
        releaseAudio();
        if (proxy != null) proxy.stop();
        super.onDestroy();
    }

    // ------------------------------------------------------------ 互动 UI（图标统一 24dp 视觉框）

    static class Act {
        LinearLayout box;
        ImageView icon;
        TextView label;
        String off, on;
    }

    /**
     * 图标项：顶部栏用「数字在上、图标在下」（贴合横屏播放器），
     * 竖屏互动栏用「图标在上、文字在下」（贴合详情页）。图标本体尺寸统一由 sizeDp 决定。
     */
    Act makeAct(String off, String on, int sizeDp, boolean labelTop) {
        Act a = new Act();
        a.off = off;
        a.on = on;
        a.box = new LinearLayout(this);
        a.box.setOrientation(LinearLayout.VERTICAL);
        a.box.setGravity(Gravity.CENTER);
        a.box.setClickable(true);
        a.box.setPadding(dp(3), dp(1), dp(3), dp(1));

        a.label = new TextView(this);
        a.label.setTextColor(Color.WHITE);
        a.label.setTextSize(labelTop ? 9 : 10);
        a.label.setGravity(Gravity.CENTER);
        a.label.setSingleLine(true);
        if (labelTop) a.label.setShadowLayer(dp(3), 0, dp(1), 0x99000000);

        a.icon = Ic.view(this, off, Color.WHITE, sizeDp);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);

        if (labelTop) {
            a.box.addView(a.label, lp);
            ip.topMargin = dp(2);
            a.box.addView(a.icon, ip);
        } else {
            a.box.addView(a.icon, ip);
            lp.topMargin = dp(4);
            a.box.addView(a.label, lp);
        }
        return a;
    }

    void addAct(LinearLayout row, Act a) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(6);
        row.addView(a.box, lp);
    }

    /** 竖屏互动栏：五等分，保证图标间距一致 */
    void addActWide(LinearLayout row, Act a) {
        row.addView(a.box, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    }

    /** 控制条上的文字按钮（无背景，靠间距区分） */
    TextView textTool(String txt, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(txt);
        t.setTextColor(Color.WHITE);
        t.setTextSize(10.5f);
        t.setSingleLine(true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(5), dp(6), dp(5), dp(6));
        t.setOnClickListener(l);
        return t;
    }

    /** 竖屏给返回键一个半透明圆底（亮画面也能看清）；全屏用纯箭头 */
    void setBackChip(boolean chip) {
        if (backArrow == null) return;
        if (chip) {
            android.graphics.drawable.GradientDrawable g =
                    new android.graphics.drawable.GradientDrawable();
            g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            g.setColor(0x66000000);
            backArrow.setBackground(g);
        } else {
            backArrow.setBackground(null);
        }
    }

    void setTimeText(int pos, int len) {
        if (timeText != null) timeText.setText(mmss(pos) + "/" + mmss(len));
        if (vTime != null) vTime.setText(mmss(pos));
    }

    static String numText(long n) {
        if (n >= 100000000L) return String.format("%.1f亿", n / 100000000f);
        if (n >= 10000L) return String.format("%.1f万", n / 10000f);
        return String.valueOf(n);
    }

    void styleAct(Act a, boolean on, int onColor, String text) {
        if (a == null) return;
        a.icon.setImageResource(Ic.id(this, on ? a.on : a.off));
        a.icon.setColorFilter(on ? onColor : Color.WHITE);
        if (text != null) a.label.setText(text);
    }

    /** 同步顶部与底部两套图标的选中态和计数 */
    void refreshActs() {
        String lk = statLike > 0 ? numText(statLike) : "赞";
        String cm = statReply > 0 ? numText(statReply) : "评论";
        String cn = coins > 0 ? (coins + "/2") : (statCoin > 0 ? numText(statCoin) : "投币");
        String fv = statFav > 0 ? numText(statFav) : "收藏";

        styleAct(actLikeTop, liked, 0xFFFB7299, lk);
        styleAct(actLikeBot, liked, 0xFFFB7299, lk);
        styleAct(actCmtTop, false, Color.WHITE, cm);
        styleAct(actCmtBot, false, Color.WHITE, cm);
        styleAct(actCoinTop, coins > 0, 0xFFFFC53D, cn);
        styleAct(actCoinBot, coins > 0, 0xFFFFC53D, cn);
        styleAct(actFavTop, faved, 0xFFFFC53D, fv);
        styleAct(actFavBot, faved, 0xFFFFC53D, fv);
        styleAct(actShareTop, false, Color.WHITE, statShare > 0 ? numText(statShare) : "转发");
        styleAct(actShareBot, false, Color.WHITE, statShare > 0 ? numText(statShare) : "转发");
        // 【v4.19.0】右侧竖排互动栏同步选中态与计数
        styleAct(actLikeRail, liked, 0xFFFB7299, lk);
        styleAct(actCmtRail, false, Color.WHITE, cm);
        styleAct(actCoinRail, coins > 0, 0xFFFFC53D, cn);
        styleAct(actFavRail, faved, 0xFFFFC53D, fv);
        styleAct(actShareRail, false, Color.WHITE, statShare > 0 ? numText(statShare) : "转发");
        updateFollowBtn();
    }

    void updateFollowBtn() {
        boolean on = followedState > 0;
        String txt = on ? (followedState == 2 ? "互相关注" : "已关注") : "+ 关注";
        try {
            // 【v4.19.0】右侧头像下的徽标：未关注显示粉色「+」，已关注变成半透明「✓」
            if (vFollowBadge != null) {
                vFollowBadge.setText(on ? "✓" : "+");
                vFollowBadge.setTextSize(on ? 12 : 13);
                vFollowBadge.setBackground(roundRect(on ? 0x66000000 : 0xFFFB7299, dp(11), dp(22)));
            }
            if (followBtnTop != null) {
                followBtnTop.setText(txt);
                followBtnTop.setBackground(roundRect(on ? 0x33FFFFFF : 0x4DFB7299, dp(14), dp(28)));
                followBtnTop.setVisibility(metaLoaded ? View.VISIBLE : View.GONE);
            }
            if (followBtnBot != null) {
                followBtnBot.setText(txt);
                followBtnBot.setBackground(roundRect(on ? 0x33FFFFFF : 0xFFFB7299, dp(15), dp(30)));
                followBtnBot.setTextColor(on ? 0xCCFFFFFF : Color.WHITE);
            }
        } catch (Throwable ignore) { }
    }

    void updatePlayIcon(boolean playing) {
        if (playIcon == null) return;
        Ic.set(playIcon, playing ? "pause" : "play", Color.WHITE);
        // 【v4.19.0】竖屏底部工具行的播放键同步
        if (vPlay != null) Ic.set(vPlay, playing ? "pause" : "play", Color.WHITE);
    }

    // ------------------------------------------------------------ 互动动作

    boolean needLoginFor(String act) {
        if (BiliApi.loggedInUid() != null) return false;
        showToastSafe("请先登录后再" + act);
        return true;
    }

    /** 点赞图标轻轻摇一摇：左右小幅摆动后回正 */
    void wiggle(Act a) {
        if (a == null || a.icon == null) return;
        View v = a.icon;
        v.animate().cancel();
        v.setRotation(0f);
        try {
            android.animation.ObjectAnimator oa = android.animation.ObjectAnimator.ofFloat(
                    v, "rotation", 0f, -16f, 13f, -10f, 7f, -4f, 0f);
            oa.setDuration(560);
            oa.setInterpolator(new android.view.animation.DecelerateInterpolator());
            oa.start();
        } catch (Throwable ignore) {
            // 极老机型动画框架不可用时，静默降级为无动画
        }
    }

    /** 一键三连：点赞 + 投币(2枚) + 收藏(默认收藏夹)，逐个执行并带摇一摇反馈 */
    void tripleLike() {
        if (needLoginFor("一键三连")) return;
        showToastSafe("一键三连 ٩(๑>◡<๑)و");
        // 视觉反馈：赞、币、收藏三个图标依次摇一摇
        wiggle(actLikeTop); wiggle(actLikeBot);
        wiggle(actCoinTop); wiggle(actCoinBot);
        wiggle(actFavTop); wiggle(actFavBot);
        // 执行前快照：已经赞/藏过的就不再重复提交
        final boolean wasLiked = liked;
        final boolean wasFaved = faved;
        final int coinN = 2 - coins;   // 补足到 2 枚
        // 不预先改 UI —— 每个动作只有服务端确认成功才更新状态，避免「假成功」
        new Thread(new Runnable() { @Override public void run() {
            final boolean[] ok = { true, true, true }; // 赞 / 币 / 藏
            final String[] errs = { null, null, null };
            if (!wasLiked) {
                try { BiliApi.setLike(curBv, true); }
                catch (Throwable e) { ok[0] = false; errs[0] = actErr(e); }
            }
            if (coinN > 0) {
                try { BiliApi.addCoin(curBv, coinN, false); }
                catch (Throwable e) { ok[1] = false; errs[1] = actErr(e); }
            }
            if (!wasFaved) {
                try {
                    String uid = BiliApi.loggedInUid();
                    JSONArray fs = uid == null ? null : BiliApi.favFolders(uid);
                    if (fs == null || fs.length() == 0) {
                        ok[2] = false; errs[2] = "没有可用的收藏夹";
                    } else {
                        long fid = fs.getJSONObject(0).optLong("id", 0);
                        if (fid == 0) { ok[2] = false; errs[2] = "收藏夹 id 异常"; }
                        else BiliApi.dealFav(aid, String.valueOf(fid), "");
                    }
                } catch (Throwable e) { ok[2] = false; errs[2] = actErr(e); }
            }
            ui.post(new Runnable() { @Override public void run() {
                if (ok[0] && !wasLiked) { liked = true; statLike += 1; }
                if (ok[1] && coinN > 0) { coins = Math.min(2, coins + coinN); statCoin += coinN; }
                if (ok[2] && !wasFaved) { faved = true; statFav += 1; }
                refreshActs();
                // 汇总失败原因；登录过期统一清理失效态
                String firstErr = null;
                for (String e : errs) if (e != null) { firstErr = e; break; }
                if (firstErr != null) {
                    handleActError(firstErr);
                } else {
                    showToastSafe("三连成功 ٩(๑>◡<๑)و");
                }
            } });
        } }).start();
    }

    void tapLike() {
        if (needLoginFor("点赞")) return;
        // 短按点赞：图标摇一摇
        wiggle(actLikeTop); wiggle(actLikeBot);
        final boolean target = !liked;
        liked = target;
        statLike += target ? 1 : -1;
        if (statLike < 0) statLike = 0;
        refreshActs();
        new Thread(new Runnable() { @Override public void run() {
            String err = null;
            try {
                BiliApi.setLike(curBv, target);
            } catch (Throwable e) {
                err = actErr(e);
            }
            final String fErr = err;
            if (fErr != null) ui.post(new Runnable() { @Override public void run() {
                liked = !target;
                statLike += target ? -1 : 1;
                if (statLike < 0) statLike = 0;
                refreshActs();
                handleActError(fErr);
            } });
        } }).start();
    }

    void tapCoin() {
        if (needLoginFor("投币")) return;
        if (coins >= 2) {
            showToastSafe("这个视频已经投过 2 枚硬币了");
            return;
        }
        final String[] items = {"投 1 枚硬币", "投 2 枚硬币"};
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("投币支持 UP 主");
        b.setItems(items, new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int which) { doCoin(which + 1); }
        });
        b.setNegativeButton("取消", null);
        b.show();
    }

    void doCoin(final int n) {
        showToastSafe("投币中…");
        new Thread(new Runnable() { @Override public void run() {
            String err = null;
            try {
                BiliApi.addCoin(curBv, n, false);
            } catch (Throwable e) {
                err = actErr(e);
            }
            final String fErr = err;
            ui.post(new Runnable() { @Override public void run() {
                if (fErr != null) {
                    handleActError(fErr);
                    return;
                }
                coins = Math.min(2, coins + n);
                statCoin += n;
                refreshActs();
                showToastSafe("已投 " + n + " 枚硬币，感谢支持");
            } });
        } }).start();
    }

    void tapFav() {
        if (needLoginFor("收藏")) return;
        if (faved && !favFolderId.isEmpty()) {
            AlertDialog.Builder b = new AlertDialog.Builder(this);
            b.setTitle("已收藏");
            b.setMessage("要把这个视频从收藏夹移除吗？");
            b.setPositiveButton("取消收藏", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) { doFav("", favFolderId); }
            });
            b.setNegativeButton("再选收藏夹", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) { pickFavFolders(); }
            });
            b.show();
            return;
        }
        pickFavFolders();
    }

    void pickFavFolders() {
        final String uid = BiliApi.loggedInUid();
        if (uid == null) return;
        new Thread(new Runnable() { @Override public void run() {
            JSONArray fs = null;
            String err = null;
            try {
                fs = BiliApi.favFolders(uid);
            } catch (Throwable e) {
                err = actErr(e);
            }
            final JSONArray fFs = fs;
            final String fErr = err;
            ui.post(new Runnable() { @Override public void run() {
                if (fFs == null || fFs.length() == 0) {
                    handleActError(fErr != null ? fErr : "没有可用的收藏夹");
                    return;
                }
                final ArrayList<String> ids = new ArrayList<>();
                ArrayList<String> names = new ArrayList<>();
                for (int i = 0; i < fFs.length(); i++) {
                    JSONObject f = fFs.optJSONObject(i);
                    if (f == null) continue;
                    ids.add(String.valueOf(f.optLong("id", 0)));
                    names.add(f.optString("title", "收藏夹"));
                }
                if (ids.isEmpty()) {
                    showToastSafe("没有可用的收藏夹");
                    return;
                }
                final boolean[] checked = new boolean[ids.size()];
                AlertDialog.Builder b = new AlertDialog.Builder(PlayActivity.this);
                b.setTitle("收藏到");
                b.setMultiChoiceItems(names.toArray(new String[0]), checked,
                        new DialogInterface.OnMultiChoiceClickListener() {
                            @Override public void onClick(DialogInterface d, int which, boolean isChecked) {
                                checked[which] = isChecked;
                            }
                        });
                b.setPositiveButton("确定", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        StringBuilder add = new StringBuilder();
                        for (int i = 0; i < checked.length; i++) {
                            if (!checked[i]) continue;
                            if (add.length() > 0) add.append(',');
                            add.append(ids.get(i));
                        }
                        if (add.length() == 0) {
                            showToastSafe("请至少选择一个收藏夹");
                            return;
                        }
                        doFav(add.toString(), "");
                    }
                });
                b.setNegativeButton("取消", null);
                b.show();
            } });
        } }).start();
    }

    void doFav(final String addIds, final String delIds) {
        new Thread(new Runnable() { @Override public void run() {
            String err = null;
            try {
                BiliApi.dealFav(aid, addIds, delIds);
            } catch (Throwable e) {
                err = actErr(e);
            }
            final String fErr = err;
            ui.post(new Runnable() { @Override public void run() {
                if (fErr != null) {
                    handleActError(fErr);
                    return;
                }
                if (!delIds.isEmpty()) {
                    faved = false;
                    statFav = Math.max(0, statFav - 1);
                    showToastSafe("已取消收藏");
                } else {
                    faved = true;
                    favFolderId = addIds.split(",")[0];
                    statFav += 1;
                    showToastSafe("已收藏");
                }
                refreshActs();
            } });
        } }).start();
    }

    void tapShare() {
        final String url = "https://www.bilibili.com/video/" + curBv;
        final String[] items = {"复制链接", "分享到其他应用"};
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle(videoTitle.isEmpty() ? "转发" : videoTitle);
        b.setItems(items, new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int which) {
                if (which == 0) {
                    try {
                        android.content.ClipboardManager cm = (android.content.ClipboardManager)
                                getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("bili", url));
                        showToastSafe("链接已复制");
                    } catch (Throwable e) {
                        showToastSafe("复制失败：" + e.getMessage());
                    }
                    return;
                }
                try {
                    android.content.Intent i = new android.content.Intent(
                            android.content.Intent.ACTION_SEND);
                    i.setType("text/plain");
                    i.putExtra(android.content.Intent.EXTRA_SUBJECT, videoTitle);
                    i.putExtra(android.content.Intent.EXTRA_TEXT,
                            (videoTitle.isEmpty() ? "哔哩哔哩视频" : videoTitle) + "\n" + url);
                    startActivity(android.content.Intent.createChooser(i, "分享到"));
                } catch (Throwable e) {
                    showToastSafe("分享失败：" + e.getMessage());
                }
            }
        });
        b.setNegativeButton("取消", null);
        b.show();
    }

    void tapFollow() {
        if (needLoginFor("关注")) return;
        if (ownerMid <= 0) {
            showToastSafe("还没拿到 UP 主信息，稍后再试");
            return;
        }
        final boolean target = followedState == 0;
        final int old = followedState;
        followedState = target ? 1 : 0;
        updateFollowBtn();
        new Thread(new Runnable() { @Override public void run() {
            String err = null;
            try {
                BiliApi.modifyFollow(ownerMid, target);
            } catch (Throwable e) {
                err = actErr(e);
            }
            final String fErr = err;
            ui.post(new Runnable() { @Override public void run() {
                if (fErr != null) {
                    followedState = old;
                    updateFollowBtn();
                    handleActError(fErr);
                } else {
                    showToastSafe(target ? "已关注" : "已取消关注");
                }
            } });
        } }).start();
    }

    /**
     * 【v4.9.6】评论从「整页 Activity」改为「底部抽屉」。
     * 旧做法一进去评论就把视频完全盖住，想边看边刷评论必须来回退出；
     * 现在抽屉只占 62% 高，上方画面仍在播，下滑或点 × 就能收起。
     */
    void openComments() {
        if (aid <= 0) {
            showToastSafe("评论还没加载好，稍后再试");
            return;
        }
        try {
            if (commentSheet == null && shellFrame != null) {
                commentSheet = new CommentSheet(this, shellFrame);
            }
            if (commentSheet != null) {
                commentSheet.open(aid, curBv, statReply, videoTitle);
            }
        } catch (Throwable e) {
            CrashHandler.note("comment", "打开评论抽屉失败：" + e);
            showToastSafe("打开评论失败：" + e.getMessage());
        }
    }

    // ------------------------------------------------------------ 控制条菜单

    void showMoreMenu() {
        // 【v4.19.0】把「清晰度」也放进来：竖屏沉浸式界面底部只留常用键，
        // 档位、解码这些低频设置走这里，画面才干净。
        final String[] items = {"清晰度：" + qualityShort(Prefs.quality(this)),
                "解码方式：" + decName(Prefs.decoder(this)), "缓存到本地", "复制视频链接"};
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("更多");
        b.setItems(items, new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int which) {
                if (which == 0) showQualityDialog();
                else if (which == 1) showDecoderDialog();
                else if (which == 2) cacheVideo();
                else tapShare();
            }
        });
        b.setNegativeButton("取消", null);
        b.show();
    }

    static String decName(int v) {
        switch (v) {
            case Prefs.DEC_HW: return "硬解";
            case Prefs.DEC_COMPAT: return "兼容（合流）";
            case Prefs.DEC_LOW: return "兼容 360p";
            default: return "自动";
        }
    }

    void showDecoderDialog() {
        int cur = Prefs.decoder(this);
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("解码方式");
        b.setSingleChoiceItems(Prefs.DEC_NAMES, cur, new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int which) {
                d.dismiss();
                Prefs.setDecoder(PlayActivity.this, which);
                play(which);
            }
        });
        b.setNegativeButton("取消", null);
        b.show();
    }

    void showPages() {
        if (pagesArr == null || pagesArr.length() <= 1) {
            showToastSafe("该视频只有一个分P");
            return;
        }
        final String[] names = new String[pagesArr.length()];
        for (int i = 0; i < pagesArr.length(); i++) {
            JSONObject o = pagesArr.optJSONObject(i);
            String part = o == null ? "" : o.optString("part", "");
            names[i] = "P" + (i + 1) + "  " + (part.isEmpty() ? "第" + (i + 1) + "话" : part);
        }
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("选集");
        b.setSingleChoiceItems(names, pageIndex, new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, final int which) {
                d.dismiss();
                if (which == pageIndex) return;
                flipToPage(new Runnable() {
                    @Override public void run() {
                        pageIndex = which;
                        resumePos = 0;
                        play(Prefs.decoder(PlayActivity.this));
                    }
                });
            }
        });
        b.setNegativeButton("取消", null);
        b.show();
    }

    /**
     * 切视频「翻书」过渡：用封面遮罩绕 Y 轴翻页，翻到 90°（画面侧向不可见）时执行切换，
     * 再从 -90° 翻回 0°。总时长约 400ms，不快不慢。遮罩用当前封面，避免露出黑屏。
     */
    void flipToPage(final Runnable switchAction) {
        if (flipCover == null || isFinishing()) {
            if (switchAction != null) switchAction.run();
            return;
        }
        if (flipping) return;   // 动画中忽略重复点击
        flipping = true;
        // 尝试加载当前封面作为翻页画面
        flipCover.setImageDrawable(null);
        flipCover.setBackgroundColor(0xFF0E1014);
        if (!coverUrl.isEmpty()) {
            try { ImgLoader.load(flipCover, coverUrl,
                    getResources().getDisplayMetrics().widthPixels, dp(400)); } catch (Throwable ignore) { }
        }
        flipCover.setVisibility(View.VISIBLE);
        flipCover.setAlpha(1f);
        flipCover.setRotationY(0f);
        flipCover.setPivotX(flipCover.getWidth() / 2f);
        flipCover.setCameraDistance(12000f);

        // 第一段：0 → -90（封面翻走），中点 0.5 之后执行切换
        final android.animation.ValueAnimator out =
                android.animation.ValueAnimator.ofFloat(0f, -90f);
        out.setDuration(200);
        out.setInterpolator(new android.view.animation.AccelerateInterpolator());
        final boolean[] switched = {false};
        out.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(android.animation.ValueAnimator a) {
                float ang = (Float) a.getAnimatedValue();
                flipCover.setRotationY(ang);
                if (!switched[0] && ang <= -84f) {
                    switched[0] = true;
                    if (switchAction != null) switchAction.run();
                }
            }
        });
        out.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) {
                if (!switched[0] && switchAction != null) switchAction.run();
                // 第二段：-90 → 0 翻入新画面（从另一侧进入，形成连续翻页感）
                flipCover.setRotationY(90f);
                android.animation.ValueAnimator in =
                        android.animation.ValueAnimator.ofFloat(90f, 0f);
                in.setDuration(200);
                in.setInterpolator(new android.view.animation.DecelerateInterpolator());
                in.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
                    @Override public void onAnimationUpdate(android.animation.ValueAnimator a2) {
                        flipCover.setRotationY((Float) a2.getAnimatedValue());
                    }
                });
                in.addListener(new android.animation.AnimatorListenerAdapter() {
                    @Override public void onAnimationEnd(android.animation.Animator a2) {
                        flipCover.setVisibility(View.GONE);
                        flipCover.setRotationY(0f);
                        flipping = false;
                    }
                });
                in.start();
            }
        });
        out.start();
    }

    void nextPage() {
        if (pagesArr == null || pageIndex + 1 >= pagesArr.length()) {
            showToastSafe("已经是最后一集了");
            return;
        }
        flipToPage(new Runnable() {
            @Override public void run() {
                pageIndex++;
                resumePos = 0;
                play(Prefs.decoder(PlayActivity.this));
            }
        });
    }

    void showSpeed() {
        if (android.os.Build.VERSION.SDK_INT < 23) {
            Toast.makeText(this, "当前系统（Android " + android.os.Build.VERSION.RELEASE
                    + "）不支持变速播放", Toast.LENGTH_SHORT).show();
            return;
        }
        final float[] rates = {0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f};
        final String[] names = {"0.5×", "0.75×", "1.0×", "1.25×", "1.5×", "2.0×"};
        int cur = 2;
        for (int i = 0; i < rates.length; i++) if (Math.abs(rates[i] - rate) < 0.01f) cur = i;
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("播放倍速");
        b.setSingleChoiceItems(names, cur, new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int which) {
                d.dismiss();
                setRate(rates[which]);
                Prefs.setSpeed(PlayActivity.this, rates[which]);   // 记住倍速
                if (speedBtn != null) speedBtn.setText(which == 2 ? "倍速" : names[which]);
            }
        });
        b.setNegativeButton("取消", null);
        b.show();
    }

    // ------------------------------------------------------------ 【v4.8】弹幕样式设置

    /** 第 0 项 = 0 表示跟随弹幕自带颜色（B站彩色弹幕） */
    static final int[] DM_COLORS = {
            0x000000, 0xFFFFFF, 0xFFEB3B, 0xFB7299, 0x4FC3F7, 0x81C784, 0xBA68C8, 0xFF9800
    };

    void applyDmStyle() {
        if (dmView != null) {
            dmView.setStyle(Prefs.dmColor(this), Prefs.dmAlpha(this), Prefs.dmSize(this));
            dmView.setTuning(Prefs.dmSpeed(this), Prefs.dmArea(this), Prefs.dmDensity(this));
        }
    }

    void showDmStylePanel() {
        if (isFinishing()) return;
        final android.app.Dialog dlg = new android.app.Dialog(this);
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);

        // 【v4.10.6】面板改成可滚动：滑块比原来的胶囊多三组，矮屏 / 横屏也能放得下
        final android.widget.FrameLayout shell = new android.widget.FrameLayout(this);
        android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();
        bg.setColor(0xF21C1C22);
        bg.setCornerRadius(dp(16));
        shell.setBackground(bg);
        try {
            shell.setClipToOutline(true);
            shell.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override public void getOutline(View v, android.graphics.Outline o) {
                    o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(16));
                }
            });
        } catch (Throwable ignore) { }

        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        shell.addView(sv, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        final android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(14), dp(16), dp(12));
        sv.addView(box, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        dlg.setContentView(shell);
        dmStyleDlg = dlg;
        fillDmPanel(box);
        dlg.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            @Override public void onDismiss(android.content.DialogInterface d) { dmStyleDlg = null; }
        });
        dlg.show();
        if (dlg.getWindow() != null) {
            dlg.getWindow().setBackgroundDrawable(
                    new android.graphics.drawable.ColorDrawable(0x00000000));
            int maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.78f);
            dlg.getWindow().setLayout(dp(324), Math.min(dp(556), maxH));
        }
    }

    android.widget.TextView dmRowLabel(String s) {
        android.widget.TextView t = new android.widget.TextView(this);
        t.setText(s);
        t.setTextSize(12f);
        t.setTextColor(0x99FFFFFF);
        t.setPadding(0, dp(12), 0, dp(6));
        return t;
    }

    /** 滑块回调 */
    interface DmSet { void on(int v); }

    /**
     * 【v4.10.6】一行「标题 + 当前值 + 滑块」。
     * 拖动即时写 Prefs 并立刻 applyDmStyle()，画面上的弹幕当场变化，不用关面板再看。
     */
    android.widget.LinearLayout dmSlider(final String label, final int min, final int max,
                                        final int step, int cur, final String unit,
                                        final DmSet setter) {
        android.widget.LinearLayout wrap = new android.widget.LinearLayout(this);
        wrap.setOrientation(android.widget.LinearLayout.VERTICAL);
        wrap.setPadding(0, dp(9), 0, 0);

        android.widget.LinearLayout head = new android.widget.LinearLayout(this);
        head.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        head.setGravity(android.view.Gravity.CENTER_VERTICAL);

        android.widget.TextView t = new android.widget.TextView(this);
        t.setText(label);
        t.setTextSize(12f);
        t.setTextColor(0x99FFFFFF);
        head.addView(t, new android.widget.LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final android.widget.TextView val = new android.widget.TextView(this);
        val.setText(cur + unit);
        val.setTextSize(12f);
        val.setTextColor(0xFFFB7299);
        head.addView(val);
        wrap.addView(head);

        final android.widget.SeekBar sb = new android.widget.SeekBar(this);
        sb.setMax(Math.max(1, (max - min) / step));
        sb.setProgress(Math.max(0, (cur - min) / step));
        sb.setPadding(dp(2), dp(8), dp(2), dp(2));
        try {
            android.content.res.ColorStateList pink =
                    android.content.res.ColorStateList.valueOf(0xFFFB7299);
            sb.setProgressTintList(pink);
            sb.setThumbTintList(pink);
        } catch (Throwable ignore) { }
        sb.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar s, int p, boolean fromUser) {
                if (!fromUser) return;
                int v = min + p * step;
                val.setText(v + unit);
                setter.on(v);
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar s) { }
            @Override public void onStopTrackingTouch(android.widget.SeekBar s) { }
        });
        wrap.addView(sb, new android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return wrap;
    }

    void fillDmPanel(final android.widget.LinearLayout box) {
        box.removeAllViews();

        android.widget.TextView title = new android.widget.TextView(this);
        title.setText("弹幕设置");
        title.setTextSize(16f);
        title.setTextColor(0xFFFFFFFF);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        box.addView(title);

        android.widget.TextView sub = new android.widget.TextView(this);
        sub.setText("拖动滑块即时生效，设置会自动记住");
        sub.setTextSize(10.5f);
        sub.setTextColor(0x66FFFFFF);
        sub.setPadding(0, dp(3), 0, dp(2));
        box.addView(sub);

        final int curColor = Prefs.dmColor(this);

        // ---- 颜色 ----
        box.addView(dmRowLabel("颜色（第 1 个为原色，保留 B 站彩色弹幕）"));
        android.widget.LinearLayout crow = new android.widget.LinearLayout(this);
        crow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        for (int i = 0; i < DM_COLORS.length; i++) {
            final int c = DM_COLORS[i];
            boolean sel = (c == curColor);
            android.view.View dot = new android.view.View(this);
            android.graphics.drawable.GradientDrawable g;
            if (c == 0) {
                g = new android.graphics.drawable.GradientDrawable(
                        android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                        new int[]{0xFFFF6B6B, 0xFFFFD166, 0xFF5FD3A6, 0xFF4FC3F7});
                g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            } else {
                g = new android.graphics.drawable.GradientDrawable();
                g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                g.setColor(c);
            }
            g.setStroke(sel ? dp(2.5f) : dp(1), sel ? 0xFFFFFFFF : 0x33FFFFFF);
            dot.setBackground(g);
            android.widget.LinearLayout.LayoutParams lp =
                    new android.widget.LinearLayout.LayoutParams(dp(28), dp(28));
            lp.rightMargin = dp(5);
            crow.addView(dot, lp);
            dot.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    Prefs.setDmColor(PlayActivity.this, c);
                    applyDmStyle();
                    fillDmPanel(box);
                }
            });
        }
        box.addView(crow);

        // ---- 字号 / 不透明度 ----
        box.addView(dmSlider("字号", 60, 180, 10, Prefs.dmSize(this), "%",
                new DmSet() {
                    @Override public void on(int v) {
                        Prefs.setDmSize(PlayActivity.this, v);
                        applyDmStyle();
                    }
                }));
        box.addView(dmSlider("不透明度", 10, 100, 5, Prefs.dmAlpha(this), "%",
                new DmSet() {
                    @Override public void on(int v) {
                        Prefs.setDmAlpha(PlayActivity.this, v);
                        applyDmStyle();
                    }
                }));

        // ---- 新增：速度 / 显示区域 / 密度 ----
        box.addView(dmSlider("滚动速度（越大跑得越快）", 50, 200, 10, Prefs.dmSpeed(this), "%",
                new DmSet() {
                    @Override public void on(int v) {
                        Prefs.setDmSpeed(PlayActivity.this, v);
                        applyDmStyle();
                    }
                }));
        box.addView(dmSlider("显示区域（占画面高度，越小越不挡画面）", 40, 100, 10,
                Prefs.dmArea(this), "%",
                new DmSet() {
                    @Override public void on(int v) {
                        Prefs.setDmArea(PlayActivity.this, v);
                        applyDmStyle();
                    }
                }));
        box.addView(dmSlider("同屏密度（最多几条）", 10, 80, 5, Prefs.dmDensity(this), " 条",
                new DmSet() {
                    @Override public void on(int v) {
                        Prefs.setDmDensity(PlayActivity.this, v);
                        applyDmStyle();
                    }
                }));

        // ---- 底部：恢复默认 / 完成 ----
        android.widget.LinearLayout brow = new android.widget.LinearLayout(this);
        brow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        brow.setPadding(0, dp(16), 0, dp(2));

        android.widget.TextView reset = new android.widget.TextView(this);
        reset.setText("恢复默认");
        reset.setTextSize(12f);
        reset.setTextColor(0x80FFFFFF);
        reset.setGravity(android.view.Gravity.CENTER_VERTICAL);
        reset.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Prefs.setDmColor(PlayActivity.this, 0);
                Prefs.setDmAlpha(PlayActivity.this, 100);
                Prefs.setDmSize(PlayActivity.this, 100);
                Prefs.setDmSpeed(PlayActivity.this, 100);
                Prefs.setDmArea(PlayActivity.this, 100);
                Prefs.setDmDensity(PlayActivity.this, 40);
                applyDmStyle();
                fillDmPanel(box);
            }
        });
        brow.addView(reset, new android.widget.LinearLayout.LayoutParams(0, dp(34), 1f));

        android.widget.TextView done = new android.widget.TextView(this);
        done.setText("完成");
        done.setTextSize(13f);
        done.setTextColor(0xFFFFFFFF);
        done.setGravity(android.view.Gravity.CENTER);
        android.graphics.drawable.GradientDrawable dg =
                new android.graphics.drawable.GradientDrawable();
        dg.setColor(0xFFFB7299);
        dg.setCornerRadius(dp(17));
        done.setBackground(dg);
        done.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (dmStyleDlg != null) dmStyleDlg.dismiss();
            }
        });
        brow.addView(done, new android.widget.LinearLayout.LayoutParams(dp(88), dp(34)));
        box.addView(brow);
    }

    // ------------------------------------------------------------ 弹幕开关（「弹」字方框 + 勾）

    class DmSwitch extends View {
        final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

        DmSwitch(android.content.Context c) { super(c); }

        @Override protected void onDraw(android.graphics.Canvas cv) {
            boolean on = dmOn;
            float w = getWidth(), h = getHeight();
            float boxH = h - dp(8);
            android.graphics.RectF rf = new android.graphics.RectF(
                    dp(1.5f), dp(1.5f), w - dp(1.5f), boxH);
            float r = dp(6);
            p.setStyle(android.graphics.Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(2f, dp(1.4f)));
            p.setColor(on ? Color.WHITE : 0x8AFFFFFF);
            cv.drawRoundRect(rf, r, r, p);

            p.setStyle(android.graphics.Paint.Style.FILL);
            p.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            p.setTextSize(dp(12));
            p.setTextAlign(android.graphics.Paint.Align.CENTER);
            p.setColor(on ? Color.WHITE : 0x8AFFFFFF);
            android.graphics.Paint.FontMetrics fm = p.getFontMetrics();
            cv.drawText("弹", w / 2f, rf.centerY() - (fm.ascent + fm.descent) / 2f, p);

            // 右下角小勾：开=粉，关=灰
            android.graphics.Path path = new android.graphics.Path();
            path.moveTo(w - dp(11), boxH - dp(1));
            path.lineTo(w - dp(7.5f), boxH + dp(3));
            path.lineTo(w - dp(2), boxH - dp(4.5f));
            p.setStyle(android.graphics.Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(2f, dp(1.5f)));
            p.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            p.setStrokeJoin(android.graphics.Paint.Join.ROUND);
            p.setColor(on ? 0xFFFB7299 : 0x666F7581);
            cv.drawPath(path, p);
        }
    }

    /** 轻量 FrameLayout 容器 */
    static class FrameLike extends android.widget.FrameLayout {
        FrameLike(Activity a) { super(a); }
        static class LayoutParams extends android.widget.FrameLayout.LayoutParams {
            LayoutParams(int w, int h) { super(w, h); }
            LayoutParams(int w, int h, int gravity) { super(w, h, gravity); }
        }
    }
}
