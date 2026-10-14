package app.morphe.extension.youtube.patches.subtitles;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Layout;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class InteractiveSubtitleOverlay {

    private static final String PREFS_NAME = "morphe_interactive_subtitles_prefs";
    private static final String KEY_ENABLED = "sub_enabled";
    private static final String KEY_PROVIDER = "ai_provider";
    private static final String KEY_GEMINI_KEY = "gemini_api_key";
    private static final String KEY_NVIDIA_KEY = "nvidia_api_key";
    private static final String KEY_SELECTED_MODEL = "selected_ai_model";
    private static final String KEY_MODEL_LIST = "saved_model_list";
    private static final String KEY_CUSTOM_PROMPT = "custom_arabic_prompt";
    private static final String KEY_BG_MODE = "sub_bg_mode";
    private static final String KEY_FONT_SIZE = "sub_font_size";
    private static final String KEY_SUB_ALPHA = "sub_bg_alpha";
    private static final String KEY_POPUP_WIDTH = "popup_width_dp";
    private static final String KEY_BOTTOM_MARGIN = "sub_bottom_margin_dp";

    private static final String DEFAULT_ARABIC_PROMPT =
            "ترجم الكلمة الإنجليزية للغة العربية بدقة حسب سياق الجملة، واكتب تحتها شرحاً مبسطاً جداً ومعانيها الشائعة الأخرى مع طريقة النطق.";

    private static final String OverlayTag = "MORPHE_INTERACTIVE_SUB_BOX";
    private static final String CornerButtonTag = "MORPHE_CORNER_AI_BUTTON";
    private static final String SettingsBannerTag = "MORPHE_SETTINGS_AI_BANNER";

    private static final ExecutorService executor = Executors.newCachedThreadPool();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    private static TextView customSubBox;
    private static TextView cornerButton;
    private static Runnable hideButtonRunnable;
    private static String lastCapturedSentence = "";
    private static boolean loopStarted = false;
    private static boolean lifecycleRegistered = false;

    public static void init() {
        if (loopStarted) return;
        loopStarted = true;
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    tryRegisterLifecycle();
                    scanRunningActivities();
                } catch (Exception ignored) {}
                mainHandler.postDelayed(this, 450);
            }
        });
    }

    public static void onActivityHook(Activity activity) {
        init();
        if (activity != null) {
            mainHandler.post(() -> handleActivity(activity));
        }
    }

    public static void onSubtitleViewLayout(View view) {
        init();
        if (view != null && view.getContext() instanceof Activity) {
            mainHandler.post(() -> handleActivity((Activity) view.getContext()));
        }
    }
    private static void tryRegisterLifecycle() {
        if (lifecycleRegistered) return;
        try {
            Class<?> atClass = Class.forName("android.app.ActivityThread");
            Object appObj = atClass.getMethod("currentApplication").invoke(null);
            if (appObj instanceof Application) {
                lifecycleRegistered = true;
                ((Application) appObj).registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                    @Override public void onActivityResumed(Activity a) { handleActivity(a); }
                    @Override public void onActivityCreated(Activity a, Bundle b) { handleActivity(a); }
                    @Override public void onActivityStarted(Activity a) { handleActivity(a); }
                    @Override public void onActivityPaused(Activity a) {}
                    @Override public void onActivityStopped(Activity a) {}
                    @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
                    @Override public void onActivityDestroyed(Activity a) {}
                });
            }
        } catch (Exception ignored) {}
    }

    private static void scanRunningActivities() {
        try {
            Class<?> atClass = Class.forName("android.app.ActivityThread");
            Object at = atClass.getMethod("currentActivityThread").invoke(null);
            Field f = atClass.getDeclaredField("mActivities");
            f.setAccessible(true);
            Object mapObj = f.get(at);
            if (mapObj instanceof Map) {
                for (Object record : ((Map<?, ?>) mapObj).values()) {
                    Field pausedField = record.getClass().getDeclaredField("paused");
                    pausedField.setAccessible(true);
                    if (!pausedField.getBoolean(record)) {
                        Field actField = record.getClass().getDeclaredField("activity");
                        actField.setAccessible(true);
                        Object act = actField.get(record);
                        if (act instanceof Activity) {
                            handleActivity((Activity) act);
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    private static void handleActivity(Activity activity) {
        if (activity == null || activity.isFinishing() || activity.getWindow() == null) return;
        installTouchInterceptor(activity.getWindow());

        View content = activity.findViewById(android.R.id.content);
        if (!(content instanceof ViewGroup)) return;
        ViewGroup contentGroup = (ViewGroup) content;

        String actName = activity.getClass().getName().toLowerCase(Locale.US);
        if (actName.contains("settings") || actName.contains("license") || actName.contains("preference")) {
            injectSettingsButton(activity, contentGroup);
            return;
        }

        ensurePlayerWidgetsAttached(activity, contentGroup);
        updateSubtitlesFromHierarchy(activity, contentGroup);
    }

    private static void installTouchInterceptor(Window window) {
        try {
            Window.Callback orig = window.getCallback();
            if (orig == null || Proxy.isProxyClass(orig.getClass())) return;
            Window.Callback proxy = (Window.Callback) Proxy.newProxyInstance(
                    Window.Callback.class.getClassLoader(),
                    new Class<?>[]{Window.Callback.class},
                    (p, method, args) -> {
                        if ("dispatchTouchEvent".equals(method.getName()) && args != null && args.length > 0 && args[0] instanceof MotionEvent) {
                            MotionEvent ev = (MotionEvent) args[0];
                            if (ev.getAction() == MotionEvent.ACTION_DOWN) {
                                onPlayerControlsVisibilityChanged(true);
                            }
                        }
                        return method.invoke(orig, args);
                    }
            );
            window.setCallback(proxy);
        } catch (Exception ignored) {}
    }

    private static void injectSettingsButton(Activity activity, ViewGroup contentGroup) {
        View existing = contentGroup.findViewWithTag(SettingsBannerTag);
        if (existing != null) {
            existing.bringToFront();
            return;
        }
        Button btn = new Button(activity);
        btn.setTag(SettingsBannerTag);
        btn.setText("⚙️ إعدادات ترجمة الذكاء الاصطناعي (Morphe AI)");
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        btn.setTypeface(Typeface.DEFAULT_BOLD);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(18, 95, 52));
        bg.setCornerRadius(dpToPx(activity, 12));
        bg.setStroke(dpToPx(activity, 2), Color.rgb(46, 204, 113));
        btn.setBackground(bg);
        btn.setElevation(dpToPx(activity, 100));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL
        );
        int m = dpToPx(activity, 16);
        lp.setMargins(m, m, m, dpToPx(activity, 24));
        btn.setOnClickListener(v -> showSettingsDialog(activity));
        contentGroup.addView(btn, lp);
        btn.bringToFront();
                    }
    private static void updateSubtitlesFromHierarchy(Activity activity, ViewGroup contentGroup) {
        View subView = findSubtitleView(contentGroup);
        int bottomOffsetPx = calculatePlayerBottomOffset(activity, contentGroup, subView);
        updatePositions(activity, bottomOffsetPx);

        if (!isFeatureEnabled(activity)) {
            if (customSubBox != null) customSubBox.setVisibility(View.GONE);
            if (subView != null) subView.setAlpha(1.0f);
            return;
        }

        CharSequence extracted = subView != null ? extractAllSubtitleText(subView) : null;
        if (extracted != null && extracted.toString().trim().length() > 0) {
            if (subView != null) subView.setAlpha(0.0f);
            String sentence = extracted.toString().trim();
            if (!sentence.equals(lastCapturedSentence) || customSubBox.getVisibility() != View.VISIBLE) {
                lastCapturedSentence = sentence;
                applySubtitleBoxStyle(activity, customSubBox, bottomOffsetPx);
                customSubBox.setVisibility(View.VISIBLE);
                customSubBox.bringToFront();
                populateClickableWords(customSubBox, sentence);
            }
        } else if (lastCapturedSentence.isEmpty() && customSubBox != null) {
            String starter = "Tap CC in player for live subtitles or click any word here to test AI";
            lastCapturedSentence = starter;
            applySubtitleBoxStyle(activity, customSubBox, bottomOffsetPx);
            customSubBox.setVisibility(View.VISIBLE);
            customSubBox.bringToFront();
            populateClickableWords(customSubBox, starter);
        }
    }

    private static int calculatePlayerBottomOffset(Activity activity, ViewGroup contentGroup, View playerNode) {
        int w = contentGroup.getWidth();
        int h = contentGroup.getHeight();
        if (w > h) return 0; // وضع ملء الشاشة الأفقي

        if (playerNode != null && playerNode.getHeight() > dpToPx(activity, 80)) {
            int[] loc = new int[2];
            int[] cLoc = new int[2];
            playerNode.getLocationInWindow(loc);
            contentGroup.getLocationInWindow(cLoc);
            int pBottom = loc[1] + playerNode.getHeight();
            int cBottom = cLoc[1] + h;
            if (cBottom > pBottom) return cBottom - pBottom;
        }
        int estimatedPlayerHeight = Math.round(w * 9f / 16f) + dpToPx(activity, 48);
        return Math.max(0, h - estimatedPlayerHeight);
    }

    private static View findSubtitleView(View v) {
        if (v == null || v == customSubBox || v == cornerButton) return null;
        String cls = v.getClass().getName();
        String id = getResIdName(v);
        if (cls.contains("SubtitleWindow") || id.contains("subtitle_window") || id.contains("subtitles")) {
            return v;
        }
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) {
                View found = findSubtitleView(vg.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static CharSequence extractAllSubtitleText(View v) {
        if (v == null || v == customSubBox || v == cornerButton || v.getVisibility() != View.VISIBLE) return null;

        if (v instanceof TextView) {
            CharSequence t = ((TextView) v).getText();
            if (isValidSubtitle(t)) return t;
        }
        if (isValidSubtitle(v.getContentDescription())) return v.getContentDescription();

        try {
            AccessibilityNodeInfo info = AccessibilityNodeInfo.obtain();
            v.onInitializeAccessibilityNodeInfo(info);
            CharSequence accText = info.getText();
            info.recycle();
            if (isValidSubtitle(accText)) return accText;
        } catch (Exception ignored) {}

        CharSequence reflected = extractViaFields(v);
        if (isValidSubtitle(reflected)) return reflected;

        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < vg.getChildCount(); i++) {
                CharSequence childText = extractAllSubtitleText(vg.getChildAt(i));
                if (isValidSubtitle(childText)) {
                    if (sb.length() > 0) sb.append(" ");
                    sb.append(childText.toString().trim());
                }
            }
            if (sb.length() > 0) return sb.toString();
        }
        return null;
    }
    private static CharSequence extractViaFields(Object obj) {
        if (obj == null) return null;
        Class<?> cls = obj.getClass();
        int depth = 0;
        while (cls != null && cls != View.class && cls != Object.class && depth < 3) {
            for (Field f : cls.getDeclaredFields()) {
                try {
                    f.setAccessible(true);
                    Object val = f.get(obj);
                    if (val instanceof Layout) {
                        CharSequence lt = ((Layout) val).getText();
                        if (isValidSubtitle(lt)) return lt;
                    } else if (val instanceof CharSequence) {
                        if (isValidSubtitle((CharSequence) val)) return (CharSequence) val;
                    } else if (val instanceof Iterable<?>) {
                        StringBuilder listSb = new StringBuilder();
                        for (Object item : (Iterable<?>) val) {
                            if (item instanceof CharSequence && isValidSubtitle((CharSequence) item)) {
                                listSb.append(item).append(" ");
                            } else if (item instanceof Layout && isValidSubtitle(((Layout) item).getText())) {
                                listSb.append(((Layout) item).getText()).append(" ");
                            }
                        }
                        if (listSb.length() > 0) return listSb.toString().trim();
                    }
                } catch (Exception ignored) {}
            }
            cls = cls.getSuperclass();
            depth++;
        }
        return null;
    }

    private static boolean isValidSubtitle(CharSequence cs) {
        if (cs == null) return false;
        String s = cs.toString().trim();
        return s.length() > 1 && !s.startsWith("com.google.") && !s.startsWith("android.");
    }

    private static void ensurePlayerWidgetsAttached(Activity activity, ViewGroup contentGroup) {
        View existingBtn = contentGroup.findViewWithTag(CornerButtonTag);
        if (existingBtn instanceof TextView) {
            cornerButton = (TextView) existingBtn;
        } else {
            cornerButton = new TextView(activity);
            cornerButton.setTag(CornerButtonTag);
            cornerButton.setText("AI Sub");
            cornerButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            cornerButton.setTypeface(Typeface.DEFAULT_BOLD);
            cornerButton.setGravity(Gravity.CENTER);
            int pH = dpToPx(activity, 12);
            int pV = dpToPx(activity, 6);
            cornerButton.setPadding(pH, pV, pH, pV);
            cornerButton.setElevation(dpToPx(activity, 95));
            updateCornerButtonAppearance(activity);

            FrameLayout.LayoutParams btnLp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM | Gravity.END
            );
            contentGroup.addView(cornerButton, btnLp);

            cornerButton.setOnClickListener(v -> {
                boolean next = !isFeatureEnabled(activity);
                prefs(activity).edit().putBoolean(KEY_ENABLED, next).apply();
                updateCornerButtonAppearance(activity);
                if (!next && customSubBox != null) customSubBox.setVisibility(View.GONE);
                Toast.makeText(activity, next ? "✅ تم تفعيل ترجمة الذكاء الاصطناعي" : "⛔ تم إيقاف الترجمة", Toast.LENGTH_SHORT).show();
                scheduleButtonAutoHide();
            });

            cornerButton.setOnLongClickListener(v -> {
                showSettingsDialog(activity);
                return true;
            });
            scheduleButtonAutoHide();
        }

        View existingSub = contentGroup.findViewWithTag(OverlayTag);
        if (existingSub instanceof TextView) {
            customSubBox = (TextView) existingSub;
        } else {
            customSubBox = new TextView(activity);
            customSubBox.setTag(OverlayTag);
            customSubBox.setGravity(Gravity.CENTER);
            customSubBox.setElevation(dpToPx(activity, 90));
            FrameLayout.LayoutParams subLp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL
            );
            contentGroup.addView(customSubBox, subLp);
        }
        cornerButton.bringToFront();
            }
    private static void updatePositions(Context context, int playerBottomOffsetPx) {
        if (cornerButton != null && cornerButton.getLayoutParams() instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) cornerButton.getLayoutParams();
            int targetBottom = playerBottomOffsetPx + dpToPx(context, 14);
            if (Math.abs(lp.bottomMargin - targetBottom) > 6) {
                lp.bottomMargin = targetBottom;
                lp.rightMargin = dpToPx(context, 16);
                cornerButton.setLayoutParams(lp);
            }
        }
        if (customSubBox != null) {
            applySubtitleBoxStyle(context, customSubBox, playerBottomOffsetPx);
        }
    }

    public static void onPlayerControlsVisibilityChanged(boolean visible) {
        mainHandler.post(() -> {
            if (cornerButton == null) return;
            cornerButton.bringToFront();
            if (visible) {
                cornerButton.setVisibility(View.VISIBLE);
                cornerButton.animate().alpha(1.0f).setDuration(180).start();
                scheduleButtonAutoHide();
            }
        });
    }

    private static void scheduleButtonAutoHide() {
        if (hideButtonRunnable != null) mainHandler.removeCallbacks(hideButtonRunnable);
        hideButtonRunnable = () -> {
            if (cornerButton != null && cornerButton.getVisibility() == View.VISIBLE) {
                cornerButton.animate().alpha(0.25f).setDuration(300).start();
            }
        };
        mainHandler.postDelayed(hideButtonRunnable, 4000);
    }

    private static void updateCornerButtonAppearance(Context context) {
        if (cornerButton == null) return;
        boolean enabled = isFeatureEnabled(context);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dpToPx(context, 18));
        if (enabled) {
            bg.setColor(Color.argb(225, 18, 95, 52));
            bg.setStroke(dpToPx(context, 1), Color.rgb(46, 204, 113));
            cornerButton.setTextColor(Color.WHITE);
        } else {
            bg.setColor(Color.argb(200, 40, 40, 40));
            bg.setStroke(dpToPx(context, 1), Color.GRAY);
            cornerButton.setTextColor(Color.LTGRAY);
        }
        cornerButton.setBackground(bg);
    }

    private static void applySubtitleBoxStyle(Context context, TextView box, int playerBottomOffsetPx) {
        SharedPreferences sp = prefs(context);
        int fontSize = sp.getInt(KEY_FONT_SIZE, 17);
        int bgMode = sp.getInt(KEY_BG_MODE, 0);
        int bgAlpha = sp.getInt(KEY_SUB_ALPHA, 185);
        int userMarginDp = sp.getInt(KEY_BOTTOM_MARGIN, 44);

        box.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize);
        box.setShadowLayer(5f, 0f, 2f, Color.BLACK);
        int padH = dpToPx(context, 12);
        int padV = dpToPx(context, 5);
        box.setPadding(padH, padV, padH, padV);

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dpToPx(context, 10));
        if (bgMode == 2) {
            bg.setColor(Color.TRANSPARENT);
        } else if (bgMode == 1) {
            bg.setColor(Color.argb(255, 0, 0, 0));
        } else {
            bg.setColor(Color.argb(bgAlpha, 12, 12, 12));
        }
        box.setBackground(bg);

        if (box.getLayoutParams() instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams mlp = (FrameLayout.LayoutParams) box.getLayoutParams();
            mlp.bottomMargin = playerBottomOffsetPx + dpToPx(context, userMarginDp);
            mlp.leftMargin = dpToPx(context, 20);
            mlp.rightMargin = dpToPx(context, 75);
            box.setLayoutParams(mlp);
        }
    }

    private static void populateClickableWords(TextView textView, String sentence) {
        SpannableString spannable = new SpannableString(sentence);
        BreakIterator iterator = BreakIterator.getWordInstance(Locale.ENGLISH);
        iterator.setText(sentence);
        for (int start = iterator.first(), end = iterator.next(); end != BreakIterator.DONE; start = end, end = iterator.next()) {
            String word = sentence.substring(start, end).trim();
            if (!word.isEmpty() && Character.isLetterOrDigit(word.charAt(0))) {
                final String clickedWord = word;
                spannable.setSpan(new ClickableSpan() {
                    @Override
                    public void onClick(View widget) {
                        showAiTranslationPopup(widget, clickedWord, sentence);
                    }
                    @Override
                    public void updateDrawState(TextPaint ds) {
                        super.updateDrawState(ds);
                        ds.setColor(Color.WHITE);
                        ds.setUnderlineText(false);
                    }
                }, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        textView.setText(spannable);
        textView.setMovementMethod(LinkMovementMethod.getInstance());
        textView.setHighlightColor(Color.argb(130, 46, 204, 113));
    }
    private static void showAiTranslationPopup(View anchor, String word, String fullSentence) {
        Context context = anchor.getContext();
        SharedPreferences sp = prefs(context);
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dpToPx(context, 16);
        box.setPadding(pad, pad, pad, pad);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(245, 20, 22, 28));
        bg.setCornerRadius(dpToPx(context, 16));
        bg.setStroke(dpToPx(context, 1), Color.rgb(46, 204, 113));
        box.setBackground(bg);

        TextView title = new TextView(context);
        title.setText("🇬🇧 " + word);
        title.setTextColor(Color.rgb(46, 204, 113));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(title);

        TextView body = new TextView(context);
        body.setText("⏳ جاري الترجمة وكتابة الشرح المبسط...");
        body.setTextColor(Color.WHITE);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        body.setPadding(0, dpToPx(context, 8), 0, 0);
        box.addView(body);

        PopupWindow popup = new PopupWindow(box, dpToPx(context, sp.getInt(KEY_POPUP_WIDTH, 320)), ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setElevation(30f);
        popup.showAtLocation(anchor, Gravity.CENTER, 0, 0);

        String provider = sp.getString(KEY_PROVIDER, "GEMINI");
        String gKey = sp.getString(KEY_GEMINI_KEY, "").trim();
        String nKey = sp.getString(KEY_NVIDIA_KEY, "").trim();
        String model = sp.getString(KEY_SELECTED_MODEL, "gemini-2.5-flash").trim();
        String promptCfg = sp.getString(KEY_CUSTOM_PROMPT, DEFAULT_ARABIC_PROMPT).trim();

        executor.execute(() -> {
            String fullPrompt = promptCfg + "\nالكلمة: \"" + word + "\"\nالجملة: \"" + fullSentence + "\"";
            String res = ("NVIDIA".equals(provider) && !nKey.isEmpty()) ? callAiApi(true, nKey, model, fullPrompt, word)
                    : (!gKey.isEmpty() ? callAiApi(false, gKey, model, fullPrompt, word) : queryFallbackDictionary(word));
            mainHandler.post(() -> body.setText(res));
        });
    }

    private static String callAiApi(boolean isNvidia, String key, String model, String prompt, String word) {
        try {
            String url = isNvidia ? "https://integrate.api.nvidia.com/v1/chat/completions"
                    : "https://generativelanguage.googleapis.com/v1beta/models/" + (model.contains("/") ? "gemini-2.5-flash" : model.replace("models/", "")) + ":generateContent?key=" + URLEncoder.encode(key, "UTF-8");
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            if (isNvidia) c.setRequestProperty("Authorization", "Bearer " + key);
            c.setConnectTimeout(8000);
            c.setDoOutput(true);

            JSONObject payload = isNvidia
                    ? new JSONObject().put("model", model.contains("/") ? model : "meta/llama-3.3-70b-instruct").put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", prompt))).put("max_tokens", 350)
                    : new JSONObject().put("contents", new JSONArray().put(new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", prompt)))));

            try (OutputStream os = c.getOutputStream()) { os.write(payload.toString().getBytes(StandardCharsets.UTF_8)); }
            int code = c.getResponseCode();
            String resp = readStream(code == 200 ? c.getInputStream() : c.getErrorStream());
            if (code != 200) return "⚠️ خطأ API (" + code + ")\n" + queryFallbackDictionary(word);
            JSONObject j = new JSONObject(resp);
            return isNvidia ? j.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim()
                    : j.getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text").trim();
        } catch (Exception e) { return queryFallbackDictionary(word); }
    }

    private static String queryFallbackDictionary(String word) {
        try {
            String u = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=en&tl=ar&dt=t&dt=bd&q=" + URLEncoder.encode(word, "UTF-8");
            JSONArray r = new JSONArray(readStream(new URL(u).openStream()));
            StringBuilder sb = new StringBuilder("🎯 المعنى: ").append(r.getJSONArray(0).getJSONArray(0).getString(0));
            if (!r.isNull(1)) {
                JSONArray d = r.getJSONArray(1);
                sb.append("\n\n📚 معاني وشرح إضافي:");
                for (int i = 0; i < Math.min(d.length(), 3); i++) {
                    JSONArray terms = d.getJSONArray(i).getJSONArray(1);
                    List<String> top = new ArrayList<>();
                    for (int j = 0; j < Math.min(terms.length(), 4); j++) top.add(terms.getString(j));
                    sb.append("\n• (").append(d.getJSONArray(i).getString(0)).append("): ").append(joinList(top, "، "));
                }
            }
            return sb.toString();
        } catch (Exception e) { return "تعذر الاتصال بالقاموس."; }
    }

    public static void showSettingsDialog(Context ctx) {
        SharedPreferences sp = prefs(ctx);
        ScrollView sc = new ScrollView(ctx);
        LinearLayout lay = new LinearLayout(ctx);
        lay.setOrientation(LinearLayout.VERTICAL);
        int p = dpToPx(ctx, 16);
        lay.setPadding(p, p, p, p);
        lay.setBackgroundColor(Color.rgb(22, 24, 30));
        sc.addView(lay);

        addTitle(ctx, lay, "🌐 مزود الذكاء الاصطناعي (Gemini / Nvidia):");
        Spinner provSp = new Spinner(ctx);
        provSp.setAdapter(new ArrayAdapter<>(ctx, android.R.layout.simple_spinner_dropdown_item, Arrays.asList("GEMINI (Google AI)", "NVIDIA (Free NIM API)")));
        provSp.setSelection("NVIDIA".equals(sp.getString(KEY_PROVIDER, "GEMINI")) ? 1 : 0);
        lay.addView(provSp);

        addTitle(ctx, lay, "🔑 مفتاح Gemini API:");
        EditText gIn = new EditText(ctx); gIn.setTextColor(Color.WHITE); gIn.setText(sp.getString(KEY_GEMINI_KEY, "")); lay.addView(gIn);

        addTitle(ctx, lay, "🟢 مفتاح Nvidia API المجاني:");
        EditText nIn = new EditText(ctx); nIn.setTextColor(Color.WHITE); nIn.setText(sp.getString(KEY_NVIDIA_KEY, "")); lay.addView(nIn);

        addTitle(ctx, lay, "🤖 اختر النموذج:");
        Spinner modSp = new Spinner(ctx);
        List<String> mods = new ArrayList<>(Arrays.asList(sp.getString(KEY_MODEL_LIST, "gemini-2.5-flash,gemini-2.5-pro,meta/llama-3.3-70b-instruct,deepseek-ai/deepseek-r1").split(",")));
        ArrayAdapter<String> modAd = new ArrayAdapter<>(ctx, android.R.layout.simple_spinner_dropdown_item, mods);
        modSp.setAdapter(modAd);
        int idx = mods.indexOf(sp.getString(KEY_SELECTED_MODEL, "gemini-2.5-flash"));
        if (idx >= 0) modSp.setSelection(idx);
        lay.addView(modSp);

        Button refBtn = new Button(ctx);
        refBtn.setText("🔄 تحديث النماذج المتاحة");
        refBtn.setOnClickListener(v -> executor.execute(() -> {
            boolean nv = provSp.getSelectedItemPosition() == 1;
            List<String> f = fetchModels(nv, (nv ? nIn : gIn).getText().toString().trim());
            mainHandler.post(() -> {
                if (!f.isEmpty()) { mods.clear(); mods.addAll(f); modAd.notifyDataSetChanged(); sp.edit().putString(KEY_MODEL_LIST, joinList(f, ",")).apply(); }
                Toast.makeText(ctx, f.isEmpty() ? "⚠️ تأكد من المفتاح" : "✅ تم جلب " + f.size() + " نموذج", Toast.LENGTH_SHORT).show();
            });
        }));
        lay.addView(refBtn);

        addTitle(ctx, lay, "📝 صيغة الطلب بالعربي للذكاء الاصطناعي:");
        EditText prIn = new EditText(ctx); prIn.setTextColor(Color.WHITE); prIn.setText(sp.getString(KEY_CUSTOM_PROMPT, DEFAULT_ARABIC_PROMPT)); lay.addView(prIn);

        addTitle(ctx, lay, "🎨 خلفية الترجمة (شفافة / سوداء):");
        Spinner bgSp = new Spinner(ctx);
        bgSp.setAdapter(new ArrayAdapter<>(ctx, android.R.layout.simple_spinner_dropdown_item, Arrays.asList("نصف شفافة", "سوداء معتمة", "شفافة تماماً")));
        bgSp.setSelection(sp.getInt(KEY_BG_MODE, 0));
        lay.addView(bgSp);

        int[] fs = {sp.getInt(KEY_FONT_SIZE, 17)}, sa = {sp.getInt(KEY_SUB_ALPHA, 185)}, bm = {sp.getInt(KEY_BOTTOM_MARGIN, 44)};
        addSeek(ctx, lay, "حجم الخط (تكبير/تصغير)", 12, 34, fs[0], v -> fs[0] = v);
        addSeek(ctx, lay, "درجة الشفافية", 0, 255, sa[0], v -> sa[0] = v);
        addSeek(ctx, lay, "ارتفاع الترجمة", 10, 160, bm[0], v -> bm[0] = v);

        new AlertDialog.Builder(ctx).setTitle("⚙️ إعدادات الترجمة").setView(sc).setPositiveButton("حفظ", (d, w) -> {
            sp.edit().putString(KEY_PROVIDER, provSp.getSelectedItemPosition() == 1 ? "NVIDIA" : "GEMINI")
                    .putString(KEY_GEMINI_KEY, gIn.getText().toString().trim()).putString(KEY_NVIDIA_KEY, nIn.getText().toString().trim())
                    .putString(KEY_SELECTED_MODEL, modSp.getSelectedItem() != null ? modSp.getSelectedItem().toString() : "gemini-2.5-flash")
                    .putString(KEY_CUSTOM_PROMPT, prIn.getText().toString().trim()).putInt(KEY_BG_MODE, bgSp.getSelectedItemPosition())
                    .putInt(KEY_FONT_SIZE, fs[0]).putInt(KEY_SUB_ALPHA, sa[0]).putInt(KEY_BOTTOM_MARGIN, bm[0]).apply();
        }).setNegativeButton("إلغاء", null).show();
    }

    private static List<String> fetchModels(boolean nv, String key) {
        List<String> list = new ArrayList<>();
        try {
            String u = nv ? "https://integrate.api.nvidia.com/v1/models" : "https://generativelanguage.googleapis.com/v1beta/models?key=" + URLEncoder.encode(key, "UTF-8");
            HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
            if (nv && !key.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + key);
            JSONArray arr = new JSONObject(readStream(c.getInputStream())).getJSONArray(nv ? "data" : "models");
            for (int i = 0; i < arr.length(); i++) list.add(arr.getJSONObject(i).getString(nv ? "id" : "name").replace("models/", ""));
        } catch (Exception ignored) {}
        return list;
    }

    private interface ValCb { void onVal(int v); }
    private static void addTitle(Context c, LinearLayout p, String t) { TextView v = new TextView(c); v.setText(t); v.setTextColor(Color.rgb(46, 204, 113)); v.setPadding(0, dpToPx(c, 10), 0, 4); p.addView(v); }
    private static void addSeek(Context c, LinearLayout p, String t, int min, int max, int cur, ValCb cb) {
        TextView v = new TextView(c); v.setText(t + ": " + cur); v.setTextColor(Color.WHITE); p.addView(v);
        SeekBar s = new SeekBar(c); s.setMax(max - min); s.setProgress(Math.max(0, cur - min));
        s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar b, int pr, boolean u) { int val = min + pr; v.setText(t + ": " + val); cb.onVal(val); }
            @Override public void onStartTrackingTouch(SeekBar b) {}
            @Override public void onStopTrackingTouch(SeekBar b) {}
        });
        p.addView(s);
    }
    private static String getResIdName(View v) { try { return v.getId() != View.NO_ID ? v.getResources().getResourceEntryName(v.getId()).toLowerCase(Locale.US) : ""; } catch (Exception e) { return ""; } }
    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE); }
    private static boolean isFeatureEnabled(Context c) { return prefs(c).getBoolean(KEY_ENABLED, true); }
    private static int dpToPx(Context c, int dp) { return Math.round(dp * c.getResources().getDisplayMetrics().density); }
    private static String joinList(List<String> l, String d) { StringBuilder s = new StringBuilder(); for (int i = 0; i < l.size(); i++) { if (i > 0) s.append(d); s.append(l.get(i)); } return s.toString(); }
    private static String readStream(InputStream is) throws Exception { if (is == null) return ""; BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8)); StringBuilder s = new StringBuilder(); String l; while ((l = r.readLine()) != null) s.append(l).append("\n"); r.close(); return s.toString(); }
            }
