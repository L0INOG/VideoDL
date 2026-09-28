package com.videodl.app;

import android.Manifest;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.content.ComponentName;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.view.animation.OvershootInterpolator;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.videodl.app.parser.LinkRouter;

import java.util.List;

/**
 * 【入口】主界面：复刻 design/ 设计稿 + 两段式下载小窗。
 *
 * 功能: 链接输入、平台校验、权限申请；任务小窗的四态轮转
 *       解析中(点动画,至少3秒) → 就绪(下载按钮) → 进度(xx MB 或 图 x/y) → 完成(点击其他区域关闭)。
 * 链路: startFromText → DownloadService(ACTION_PARSE) → onParsed → 小窗就绪
 *       → 用户点下载 → DownloadService(ACTION_DOWNLOAD) → onProgress/onFinished。
 * 界面: 布局在 res/layout/activity_main.xml，视觉规范对应 design/index.html。
 */
public class MainActivity extends Activity implements DownloadService.Listener {

    // ---------------------------------------------------------------- 小窗四态

    private static final int STATE_PARSING = 0;
    private static final int STATE_READY = 1;
    private static final int STATE_PROGRESS = 2;
    private static final int STATE_DONE = 3;

    /** 权限申请码：存储（Android 9 及以下写公共下载目录） */
    private static final int REQ_STORAGE = 1;
    /** 权限申请码：通知（Android 13+ 前台服务通知可见性） */
    private static final int REQ_NOTIFICATIONS = 2;

    private LinearLayout inputCard;
    private EditText input;
    private FrameLayout parseBtn;

    private DownloadService service;
    private boolean bound;
    private boolean running;
    private String pendingText;

    // ---------------------------------------------------------------- 任务小窗

    private Dialog jobDialog;
    private int dlgState = -1;
    private int dlgGen = 0;          // 防止上一次小窗的延迟回调串场
    private long dlgShownAt;
    private TextView dlgTitle;
    private TextView dlgSub;
    private TextView dlgText;
    private TextView dlgHint;
    private ProgressBar dlgBar;
    private FrameLayout dlgBtn;
    private int dots;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Runnable dotsRunnable;

    private final ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((DownloadService.LocalBinder) binder).getService();
            service.setListener(MainActivity.this);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
        }
    };

    // ═══════════════════════════════════════════════════════════════
    //  [UI] 生命周期与入口
    // ═══════════════════════════════════════════════════════════════

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        inputCard = findViewById(R.id.inputCard);
        input = findViewById(R.id.input);
        parseBtn = findViewById(R.id.parseBtn);
        ImageButton aboutBtn = findViewById(R.id.aboutBtn);

        // 输入框聚焦时卡片描边变淡紫（对应设计稿 :focus-within）
        input.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                inputCard.setBackgroundResource(hasFocus
                        ? R.drawable.bg_input_card_focus : R.drawable.bg_input_card);
            }
        });

        parseBtn.setOnTouchListener(new PressScaleListener());
        parseBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pulse(v);
                startFromText(input.getText().toString());
            }
        });

        aboutBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showAbout();
            }
        });

        requestNotifications();
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onStart() {
        super.onStart();
        bindService(new Intent(this, DownloadService.class), conn, BIND_AUTO_CREATE);
        bound = true;
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (bound) {
            if (service != null) {
                service.setListener(null);
            }
            unbindService(conn);
            bound = false;
        }
    }

    /** 入口一：主界面输入；入口二：系统分享（ACTION_SEND）或通知点击回传的文案 */
    private void handleIntent(Intent intent) {
        if (intent == null) {
            return;
        }
        String text = null;
        if (Intent.ACTION_SEND.equals(intent.getAction()) && intent.getStringExtra(Intent.EXTRA_TEXT) != null) {
            text = intent.getStringExtra(Intent.EXTRA_TEXT);
        } else if (intent.getStringExtra(DownloadService.EXTRA_TEXT) != null) {
            text = intent.getStringExtra(DownloadService.EXTRA_TEXT);
        }
        if (text != null && !text.trim().isEmpty()) {
            input.setText(text);
            startFromText(text);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  [UI] 任务发起（文案校验 + 权限 + 派发解析）
    // ═══════════════════════════════════════════════════════════════

    private void startFromText(String text) {
        if (running) {
            toast("已有任务在进行");
            return;
        }
        if (text == null || text.trim().isEmpty()) {
            shake(inputCard);
            return;
        }
        String u = LinkRouter.extractUrl(text);
        if (u == null || LinkRouter.detect(u) == null) {
            shake(inputCard);
            toast("没找到抖音、B站、快手或小红书的分享链接");
            return;
        }
        // Android 9 及以下写公共下载目录需要存储权限
        if (Build.VERSION.SDK_INT < 29
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            pendingText = text;
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
            return;
        }
        actuallyStart(text);
    }

    /** 点箭头：弹出解析小窗（不立即下载），服务只做解析 */
    private void actuallyStart(String text) {
        running = true;
        parseBtn.setAlpha(0.55f);
        showJobDialog();

        Intent it = new Intent(this, DownloadService.class)
                .setAction(DownloadService.ACTION_PARSE)
                .putExtra(DownloadService.EXTRA_TEXT, text);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(it);
        } else {
            startService(it);
        }
        if (!bound) {
            bindService(new Intent(this, DownloadService.class), conn, BIND_AUTO_CREATE);
            bound = true;
        }
    }

    /** 点下载按钮：让服务开始下载已解析的任务 */
    private void startDownload() {
        Intent it = new Intent(this, DownloadService.class)
                .setAction(DownloadService.ACTION_DOWNLOAD);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(it);
        } else {
            startService(it);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  [UI] 任务小窗（纯代码构建，四态轮转）
    // ═══════════════════════════════════════════════════════════════

    private void showJobDialog() {
        final int gen = ++dlgGen;
        dlgState = STATE_PARSING;
        dlgShownAt = System.currentTimeMillis();

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setBackgroundResource(R.drawable.bg_dialog);
        box.setPadding(dp(26), dp(24), dp(26), dp(24));

        dlgTitle = new TextView(this);
        dlgTitle.setTextSize(15);
        dlgTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        dlgTitle.setTextColor(getColor(R.color.ink));
        dlgTitle.setGravity(Gravity.CENTER);
        dlgTitle.setVisibility(View.GONE);
        box.addView(dlgTitle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        dlgSub = new TextView(this);
        dlgSub.setTextSize(12.5f);
        dlgSub.setTextColor(getColor(R.color.ink_3));
        dlgSub.setGravity(Gravity.CENTER);
        dlgSub.setVisibility(View.GONE);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(6);
        box.addView(dlgSub, subLp);

        dlgText = new TextView(this);
        dlgText.setTextSize(14);
        dlgText.setTextColor(getColor(R.color.ink_2));
        dlgText.setGravity(Gravity.CENTER);
        dlgText.setText("正在解析.");
        box.addView(dlgText, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        dlgBtn = new FrameLayout(this);
        dlgBtn.setBackgroundResource(R.drawable.bg_parse_btn);
        ImageView arrow = new ImageView(this);
        arrow.setImageResource(R.drawable.ic_arrow_down);
        FrameLayout.LayoutParams ap = new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER);
        arrow.setLayoutParams(ap);
        dlgBtn.addView(arrow);
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(dp(48), dp(48));
        btnLp.topMargin = dp(18);
        dlgBtn.setLayoutParams(btnLp);
        dlgBtn.setContentDescription("下载");
        dlgBtn.setVisibility(View.GONE);
        dlgBtn.setOnTouchListener(new PressScaleListener());
        dlgBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (dlgState != STATE_READY) {
                    return;
                }
                pulse(dlgBtn);
                setStateProgress();   // 立即切换小窗到进度态（视图+状态一起切）
                startDownload();
            }
        });
        box.addView(dlgBtn);

        dlgHint = new TextView(this);
        dlgHint.setTextSize(12);
        dlgHint.setTextColor(getColor(R.color.ink_3));
        dlgHint.setGravity(Gravity.CENTER);
        dlgHint.setText("点击开始下载");
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintLp.topMargin = dp(8);
        dlgHint.setLayoutParams(hintLp);
        dlgHint.setVisibility(View.GONE);
        box.addView(dlgHint);

        dlgBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        dlgBar.setIndeterminate(true);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        barLp.topMargin = dp(14);
        dlgBar.setLayoutParams(barLp);
        dlgBar.setVisibility(View.GONE);
        box.addView(dlgBar);

        jobDialog = new Dialog(this);
        jobDialog.setContentView(box);
        if (jobDialog.getWindow() != null) {
            jobDialog.getWindow().setBackgroundDrawable(
                    new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
            jobDialog.getWindow().setDimAmount(0.42f);
            WindowManager.LayoutParams wlp = jobDialog.getWindow().getAttributes();
            wlp.width = dp(280);
            jobDialog.getWindow().setAttributes(wlp);
        }
        jobDialog.setOnDismissListener(new Dialog.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface d) {
                stopDots();
            }
        });
        jobDialog.show();
        startDots(gen);
    }

    /** "正在解析." 点动画（固定节奏，直到解析态结束） */
    private void startDots(final int gen) {
        stopDots();
        dots = 1;
        dotsRunnable = new Runnable() {
            @Override
            public void run() {
                if (gen != dlgGen || dlgState != STATE_PARSING) {
                    return;
                }
                dots = dots % 3 + 1;
                StringBuilder sb = new StringBuilder("正在解析");
                for (int i = 0; i < dots; i++) {
                    sb.append('.');
                }
                dlgText.setText(sb.toString());
                ui.postDelayed(this, 400);
            }
        };
        ui.postDelayed(dotsRunnable, 400);
    }

    private void stopDots() {
        if (dotsRunnable != null) {
            ui.removeCallbacks(dotsRunnable);
            dotsRunnable = null;
        }
    }

    /** 小窗视图在解析态与就绪态之间的公共显隐切换 */
    private void setBtnAreaVisible(boolean visible) {
        int v = visible ? View.VISIBLE : View.GONE;
        dlgBtn.setVisibility(v);
        dlgHint.setVisibility(v);
    }

    private void setStateReady(final int gen, final String title, final String sub) {
        if (gen != dlgGen || jobDialog == null) {
            return;
        }
        dlgState = STATE_READY;
        stopDots();
        dlgTitle.setVisibility(View.VISIBLE);
        dlgTitle.setText(title);
        dlgSub.setVisibility(View.VISIBLE);
        dlgSub.setText(sub == null ? "已获取下载链接" : sub);
        dlgText.setVisibility(View.GONE);
        dlgBar.setVisibility(View.GONE);
        setBtnAreaVisible(true);
    }

    private void setStateProgress() {
        if (jobDialog == null || dlgState == STATE_DONE) {
            return;
        }
        dlgState = STATE_PROGRESS;
        setBtnAreaVisible(false);
        dlgText.setVisibility(View.VISIBLE);
        dlgText.setText("准备下载…");
        dlgText.setTextColor(getColor(R.color.ink_2));
        dlgBar.setVisibility(View.VISIBLE);
        dlgBar.setIndeterminate(true);
    }

    private void setStateDone(String msg, boolean ok) {
        if (jobDialog == null) {
            return;
        }
        dlgState = STATE_DONE;
        stopDots();
        setBtnAreaVisible(false);
        dlgBar.setVisibility(View.GONE);
        dlgTitle.setVisibility(View.VISIBLE);
        dlgTitle.setText(ok ? "下载完成" : "任务失败");
        dlgSub.setVisibility(View.GONE);
        dlgText.setVisibility(View.VISIBLE);
        dlgText.setText(msg);
        dlgText.setTextColor(ok ? getColor(R.color.ink_2) : 0xFFF57C00);
    }

    // ═══════════════════════════════════════════════════════════════
    //  [UI] 服务回调（DownloadService.Listener）
    // ═══════════════════════════════════════════════════════════════

    @Override
    public void onParsed(final boolean ok, final String title, final String sub, final String error) {
        final int gen = dlgGen;
        // 固定至少展示 3 秒解析动画
        long delay = Math.max(0, 3000 - (System.currentTimeMillis() - dlgShownAt));
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (gen != dlgGen) {
                    return;
                }
                if (ok) {
                    setStateReady(gen, title, sub);
                } else {
                    running = false;
                    parseBtn.setAlpha(1f);
                    setStateDone(error == null ? "解析失败" : error, false);
                }
            }
        }, delay);
    }

    @Override
    public void onProgress(long bytes, long total, int index, int count) {
        if (jobDialog == null || dlgState == STATE_READY) {
            setStateProgress();
        }
        if (dlgState != STATE_PROGRESS && dlgState != STATE_DONE) {
            return;
        }
        if (count > 0) {
            // 图集：图 x/y
            dlgText.setText("图 " + index + "/" + count);
            float frac = (index - 1 + (total > 0 ? (float) bytes / total : 0f)) / count;
            dlgBar.setIndeterminate(false);
            dlgBar.setProgress(Math.round(Math.min(1f, frac) * 100));
        } else if (total > 0) {
            dlgText.setText(DownloadService.human(bytes) + " / " + DownloadService.human(total)
                    + "（" + Math.round(100f * bytes / total) + "%）");
            dlgBar.setIndeterminate(false);
            dlgBar.setProgress((int) Math.min(100, 100L * bytes / total));
        } else {
            dlgText.setText(DownloadService.human(bytes));
        }
    }

    @Override
    public void onFinished(boolean success, String message) {
        running = false;
        parseBtn.setAlpha(1f);
        setStateDone(message, success);
        toast((success ? "✅ " : "❌ ") + message);
        // 完成态不自动关窗：用户点击其他区域才消失
    }

    // ═══════════════════════════════════════════════════════════════
    //  [UI] 关于弹窗
    // ═══════════════════════════════════════════════════════════════

    private void showAbout() {
        final Dialog d = new Dialog(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        int padH = dp(22);
        box.setPadding(padH, dp(26), padH, dp(24));
        box.setBackgroundResource(R.drawable.bg_dialog);

        TextView tv = new TextView(this);
        tv.setText(R.string.about_text);
        tv.setTextSize(15);
        tv.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        tv.setLetterSpacing(0.04f);
        tv.setGravity(Gravity.CENTER);
        tv.setTextColor(getColor(R.color.ink));
        box.addView(tv);

        ImageView qr = new ImageView(this);
        qr.setImageResource(R.drawable.sponsor);
        qr.setAdjustViewBounds(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(168), ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(18);
        qr.setLayoutParams(lp);
        round(qr, 14f);
        box.addView(qr);

        d.setContentView(box);
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawable(
                    new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
            d.getWindow().setDimAmount(0.42f);
            WindowManager.LayoutParams wlp = d.getWindow().getAttributes();
            wlp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
            d.getWindow().setAttributes(wlp);
        }
        d.show();
    }

    // ═══════════════════════════════════════════════════════════════
    //  [UI] 动效与工具
    // ═══════════════════════════════════════════════════════════════

    /** 空输入抖动（对应 shake keyframes） */
    private void shake(View v) {
        ObjectAnimator.ofFloat(v, "translationX", 0, -7, 7, -4, 4, 0)
                .setDuration(400)
                .start();
    }

    /** 点击脉冲 */
    private void pulse(View v) {
        AnimatorSet set = new AnimatorSet();
        ObjectAnimator sx = ObjectAnimator.ofFloat(v, "scaleX", 1f, 0.92f, 1f);
        ObjectAnimator sy = ObjectAnimator.ofFloat(v, "scaleY", 1f, 0.92f, 1f);
        set.playTogether(sx, sy);
        set.setDuration(300);
        set.setInterpolator(new OvershootInterpolator());
        set.start();
    }

    /** 按压缩放（对应 :active { transform: scale(.92) }） */
    private static class PressScaleListener implements View.OnTouchListener {
        @Override
        public boolean onTouch(View v, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(90).start();
                    return false;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
                    return false;
            }
            return false;
        }
    }

    /** 圆角裁切 */
    private void round(View v, float radiusDp) {
        final float r = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                radiusDp, getResources().getDisplayMetrics());
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r);
            }
        });
        v.setClipToOutline(true);
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                v, getResources().getDisplayMetrics());
    }

    private void requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_STORAGE && pendingText != null) {
            String text = pendingText;
            pendingText = null;
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                actuallyStart(text);
            } else {
                toast("没有存储权限，无法保存到下载目录（Android 9 及以下需要）");
            }
        }
    }

    private void toast(String s) {
        Toast t = Toast.makeText(this, s, Toast.LENGTH_SHORT);
        t.setGravity(Gravity.CENTER, 0, 120);
        t.show();
    }
}
