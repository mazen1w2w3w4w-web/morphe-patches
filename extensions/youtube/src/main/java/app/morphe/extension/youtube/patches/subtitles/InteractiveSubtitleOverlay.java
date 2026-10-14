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
import android.view.ViewParent;
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
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
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
    private static final String KEY_POPUP_ALPHA = "popup_bg_alpha";
    private static final String KEY_POPUP_WIDTH = "popup_width_dp";
    private static final String KEY_BOTTOM_MARGIN = "sub_bottom_margin_dp";
    private static final String KEY_BTN_ALPHA = "corner_btn_alpha";

    private static final String DEFAULT_ARABIC_PROMPT =
            "ترجم الكلمة الإنجليزية للغة العربية بدقة حسب سياق الجملة، واكتب تحتها المعاني الشائعة الأخرى وشرحاً مبسطاً جداً لكيفية استخدامها مع النطق.";

    private static final String OverlayTag = "MORPHE_INTERACTIVE_SUB_BOX";
    private static final String CornerButtonTag = "MORPHE_CORNER_AI_BUTTON";
    private static final String SettingsBannerTag = "MORPHE_SETTINGS_AI_BANNER";

    private static final ExecutorService executor = Executors.newCachedThreadPool();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    private static TextView customSubBox;
    private static TextView cornerButton;
    private static Runnable hideButtonRunnable;
    private static String lastCapturedSentence = "";
    private static boolean lifecycleRegistered = false;
    private static long lastScanTime = 0L;

    static {
        init();
    }

    public static void init() {
        if (lifecycleRegistered) return;
        try {
            Class<?> atClass = Class.forName("android.app.ActivityThread");
            Object appObj = atClass.getMethod("currentApplication").invoke(null);
            if (appObj instanceof Application) {
                registerLifecycle((Application) appObj);
            }
        } catch (Exception ignored) {}
    }

    private static void registerLifecycle(Application app) {
        if (lifecycleRegistered || app == null) return;
        lifecycleRegistered = true;
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override
            public void onActivityResumed(Activity activity) {
                hookActivityWindow(activity);
            }
            @Override public void onActivityCreated(Activity a, Bundle b) { hookActivityWindow(a); }
            @Override public void onActivityStarted(Activity a) {}
            @Override public void onActivityPaused(Activity a) {}
            @Override public void onActivityStopped(Activity a) {}
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
            @Override public void onActivityDestroyed(Activity a) {}
        });
    }
        private static void hookActivityWindow(final Activity activity) {
        if (activity == null || activity.getWindow() == null) return;
        final View decor = activity.getWindow().getDecorView();
        if (decor == null) return;

        String actName = activity.getClass().getName();
        if (actName.contains("Settings") || actName.contains("License")) {
            decor.postDelayed(() -> injectSettingsEntryIfNeeded(activity, decor), 350);
        }

        decor.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            long now = System.currentTimeMillis();
            if (now - lastScanTime < 250) return;
            lastScanTime = now;
            try {
                if (actName.contains("Settings") || actName.contains("License")) {
                    injectSettingsEntryIfNeeded(activity, decor);
                } else {
                    scanPlayerHierarchy(decor);
                }
            } catch (Exception ignored) {}
        });
    }

    private static void injectSettingsEntryIfNeeded(Activity activity, View root) {
        if (!(root instanceof ViewGroup)) return;
        ViewGroup decorGroup = (ViewGroup) root;
        if (decorGroup.findViewWithTag(SettingsBannerTag) != null) return;

        View content = activity.findViewById(android.R.id.content);
        if (!(content instanceof ViewGroup)) return;
        ViewGroup contentGroup = (ViewGroup) content;

        Button aiSettingsBtn = new Button(activity);
        aiSettingsBtn.setTag(SettingsBannerTag);
        aiSettingsBtn.setText("⚙️ إعدادات ترجمة الذكاء الاصطناعي (Morphe AI)");
        aiSettingsBtn.setTextColor(Color.WHITE);
        aiSettingsBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        aiSettingsBtn.setTypeface(Typeface.DEFAULT_BOLD);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(20, 90, 50));
        bg.setCornerRadius(dpToPx(activity, 12));
        bg.setStroke(dpToPx(activity, 1), Color.rgb(46, 204, 113));
        aiSettingsBtn.setBackground(bg);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL
        );
        int m = dpToPx(activity, 14);
        lp.setMargins(m, m, m, dpToPx(activity, 20));
        aiSettingsBtn.setOnClickListener(v -> showSettingsDialog(activity));
        contentGroup.addView(aiSettingsBtn, lp);
    }

    public static void onSubtitleViewLayout(View view) {
        if (view == null) return;
        if (!lifecycleRegistered && view.getContext() instanceof Activity) {
            Application app = ((Activity) view.getContext()).getApplication();
            registerLifecycle(app);
        }
        mainHandler.post(() -> {
            try {
                CharSequence extracted = extractSubtitleText(view);
                attachInteractiveSubtitles(view, extracted);
            } catch (Exception ignored) {}
        });
    }

    private static void scanPlayerHierarchy(View root) {
        View subtitleView = findSubtitleOrPlayerView(root);
        if (subtitleView != null) {
            CharSequence text = extractSubtitleText(subtitleView);
            attachInteractiveSubtitles(subtitleView, text);
        }
    }

    private static View findSubtitleOrPlayerView(View v) {
        if (v == null) return null;
        String idName = getResIdName(v);
        String clsName = v.getClass().getName();
        if (idName.contains("subtitle") || clsName.contains("SubtitleWindow")) {
            return v;
        }
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) {
                View found = findSubtitleOrPlayerView(vg.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static String getResIdName(View v) {
        try {
            if (v.getId() != View.NO_ID && v.getResources() != null) {
                return v.getResources().getResourceEntryName(v.getId()).toLowerCase(Locale.US);
            }
        } catch (Exception ignored) {}
        return "";
    }
        private static CharSequence extractSubtitleText(View v) {
        if (v == null || v == cornerButton || v == customSubBox) return null;
        if (CornerButtonTag.equals(v.getTag()) || OverlayTag.equals(v.getTag())) return null;

        String clsName = v.getClass().getName();
        String idName = getResIdName(v);
        boolean isSubNode = clsName.contains("Subtitle") || idName.contains("subtitle") || idName.contains("caption");

        if (isSubNode) {
            if (v instanceof TextView) {
                CharSequence t = ((TextView) v).getText();
                if (t != null && t.length() > 0) return t;
            }
            CharSequence desc = v.getContentDescription();
            if (desc != null && desc.length() > 0) return desc;

            CharSequence reflected = extractTextViaReflection(v);
            if (reflected != null && reflected.length() > 0) return reflected;
        }

        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < vg.getChildCount(); i++) {
                View child = vg.getChildAt(i);
                if (child == cornerButton || child == customSubBox) continue;
                CharSequence sub = extractSubtitleText(child);
                if (sub != null && sub.length() > 0) {
                    if (sb.length() > 0) sb.append(" ");
                    sb.append(sub);
                }
            }
            if (sb.length() > 0) return sb.toString();
        }
        return null;
    }

    private static CharSequence extractTextViaReflection(Object obj) {
        if (obj == null) return null;
        Class<?> cls = obj.getClass();
        int depth = 0;
        while (cls != null && cls != View.class && cls != Object.class && depth < 3) {
            for (Field f : cls.getDeclaredFields()) {
                try {
                    f.setAccessible(true);
                    Object val = f.get(obj);
                    if (val instanceof CharSequence) {
                        CharSequence cs = (CharSequence) val;
                        if (cs.length() > 1 && !cs.toString().startsWith("com.")) return cs;
                    }
                } catch (Exception ignored) {}
            }
            cls = cls.getSuperclass();
            depth++;
        }
        return null;
    }

    public static void attachInteractiveSubtitles(View subtitleView, CharSequence rawText) {
        if (subtitleView == null) return;
        Context context = subtitleView.getContext();
        ViewGroup playerRoot = findPlayerRootContainer(subtitleView);
        if (playerRoot != null) {
            ensureCornerButtonAttached(context, playerRoot);
            ensureCustomSubtitleBoxAttached(context, playerRoot);
        }

        if (!isFeatureEnabled(context)) {
            if (customSubBox != null) customSubBox.setVisibility(View.GONE);
            subtitleView.setAlpha(1.0f);
            return;
        }

        if (rawText == null || rawText.toString().trim().isEmpty()) {
            if (customSubBox != null) customSubBox.setVisibility(View.GONE);
            return;
        }

        subtitleView.setAlpha(0.0f);
        String sentence = rawText.toString().trim();
        if (sentence.equals(lastCapturedSentence) && customSubBox != null && customSubBox.getVisibility() == View.VISIBLE) {
            return;
        }
        lastCapturedSentence = sentence;

        if (customSubBox != null) {
            applySubtitleBoxStyle(context, customSubBox);
            customSubBox.setVisibility(View.VISIBLE);
            populateClickableWords(customSubBox, sentence);
        }
    }

    public static void onPlayerControlsVisibilityChanged(boolean visible) {
        mainHandler.post(() -> {
            if (cornerButton == null) return;
            if (visible) {
                cornerButton.setVisibility(View.VISIBLE);
                cornerButton.animate().alpha(1.0f).setDuration(200).start();
                scheduleButtonAutoHide();
            } else {
                cornerButton.animate().alpha(0.0f).setDuration(200).withEndAction(() -> {
                    if (cornerButton != null) cornerButton.setVisibility(View.GONE);
                }).start();
            }
        });
    }

    private static void scheduleButtonAutoHide() {
        if (hideButtonRunnable != null) mainHandler.removeCallbacks(hideButtonRunnable);
        hideButtonRunnable = () -> {
            if (cornerButton != null && cornerButton.getVisibility() == View.VISIBLE) {
                cornerButton.animate().alpha(0.0f).setDuration(300).withEndAction(() -> {
                    if (cornerButton != null) cornerButton.setVisibility(View.GONE);
                }).start();
            }
        };
        mainHandler.postDelayed(hideButtonRunnable, 4000);
    }
        private static void ensureCornerButtonAttached(Context context, ViewGroup root) {
        View existing = root.findViewWithTag(CornerButtonTag);
        if (existing instanceof TextView) {
            cornerButton = (TextView) existing;
            return;
        }

        cornerButton = new TextView(context);
        cornerButton.setTag(CornerButtonTag);
        cornerButton.setText("AI Sub");
        cornerButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        cornerButton.setTypeface(Typeface.DEFAULT_BOLD);
        cornerButton.setGravity(Gravity.CENTER);
        int padH = dpToPx(context, 12);
        int padV = dpToPx(context, 6);
        cornerButton.setPadding(padH, padV, padH, padV);

        updateCornerButtonAppearance(context);

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.END
        );
        params.setMargins(dpToPx(context, 16), dpToPx(context, 16), dpToPx(context, 18), dpToPx(context, 46));
        root.addView(cornerButton, params);

        root.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                onPlayerControlsVisibilityChanged(true);
            }
            return false;
        });

        cornerButton.setOnClickListener(v -> {
            boolean newState = !isFeatureEnabled(context);
            prefs(context).edit().putBoolean(KEY_ENABLED, newState).apply();
            updateCornerButtonAppearance(context);
            if (!newState && customSubBox != null) {
                customSubBox.setVisibility(View.GONE);
            }
            Toast.makeText(context, newState ? "✅ تم تشغيل ترجمة الذكاء الاصطناعي" : "⛔ تم إيقاف ترجمة الذكاء الاصطناعي", Toast.LENGTH_SHORT).show();
            scheduleButtonAutoHide();
        });

        cornerButton.setOnLongClickListener(v -> {
            showSettingsDialog(context);
            return true;
        });

        scheduleButtonAutoHide();
    }

    private static void updateCornerButtonAppearance(Context context) {
        if (cornerButton == null) return;
        boolean enabled = isFeatureEnabled(context);
        int alpha = prefs(context).getInt(KEY_BTN_ALPHA, 220);

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dpToPx(context, 18));
        if (enabled) {
            bg.setColor(Color.argb(alpha, 18, 95, 52));
            bg.setStroke(dpToPx(context, 1), Color.rgb(46, 204, 113));
            cornerButton.setTextColor(Color.WHITE);
        } else {
            bg.setColor(Color.argb(alpha, 40, 40, 40));
            bg.setStroke(dpToPx(context, 1), Color.GRAY);
            cornerButton.setTextColor(Color.LTGRAY);
        }
        cornerButton.setBackground(bg);
    }

    private static void ensureCustomSubtitleBoxAttached(Context context, ViewGroup root) {
        View existing = root.findViewWithTag(OverlayTag);
        if (existing instanceof TextView) {
            customSubBox = (TextView) existing;
            return;
        }

        customSubBox = new TextView(context);
        customSubBox.setTag(OverlayTag);
        customSubBox.setGravity(Gravity.CENTER);
        customSubBox.setVisibility(View.GONE);

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL
        );
        root.addView(customSubBox, params);
        applySubtitleBoxStyle(context, customSubBox);
    }

    private static void applySubtitleBoxStyle(Context context, TextView box) {
        SharedPreferences sp = prefs(context);
        int fontSize = sp.getInt(KEY_FONT_SIZE, 18);
        int bgMode = sp.getInt(KEY_BG_MODE, 0);
        int bgAlpha = sp.getInt(KEY_SUB_ALPHA, 180);
        int bottomMarginDp = sp.getInt(KEY_BOTTOM_MARGIN, 62);

        box.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize);
        box.setShadowLayer(4f, 0f, 2f, Color.BLACK);
        int padH = dpToPx(context, 14);
        int padV = dpToPx(context, 6);
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

        if (box.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) box.getLayoutParams();
            mlp.bottomMargin = dpToPx(context, bottomMarginDp);
            mlp.leftMargin = dpToPx(context, 20);
            mlp.rightMargin = dpToPx(context, 20);
            box.setLayoutParams(mlp);
        }
    }
      private static void populateClickableWords(TextView textView, String sentence) {
        SpannableString spannable = new SpannableString(sentence);
        BreakIterator iterator = BreakIterator.getWordInstance(Locale.ENGLISH);
        iterator.setText(sentence);
        int start = iterator.first();

        for (int end = iterator.next(); end != BreakIterator.DONE; start = end, end = iterator.next()) {
            String word = sentence.substring(start, end).trim();
            if (!word.isEmpty() && Character.isLetterOrDigit(word.charAt(0))) {
                final String clickedWord = word;
                ClickableSpan span = new ClickableSpan() {
                    @Override
                    public void onClick(View widget) {
                        if (widget.getParent() != null) {
                            widget.getParent().requestDisallowInterceptTouchEvent(true);
                        }
                        showAiTranslationPopup(widget, clickedWord, sentence);
                    }

                    @Override
                    public void updateDrawState(TextPaint ds) {
                        super.updateDrawState(ds);
                        ds.setColor(Color.WHITE);
                        ds.setUnderlineText(false);
                    }
                };
                spannable.setSpan(span, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }

        textView.setText(spannable);
        textView.setMovementMethod(LinkMovementMethod.getInstance());
        textView.setHighlightColor(Color.argb(130, 46, 204, 113));
    }

    private static void showAiTranslationPopup(View anchor, String word, String fullSentence) {
        Context context = anchor.getContext();
        SharedPreferences sp = prefs(context);
        int popupAlpha = sp.getInt(KEY_POPUP_ALPHA, 242);
        int popupWidthDp = sp.getInt(KEY_POPUP_WIDTH, 320);

        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = dpToPx(context, 16);
        container.setPadding(pad, pad, pad, pad);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(popupAlpha, 20, 22, 28));
        bg.setCornerRadius(dpToPx(context, 16));
        bg.setStroke(dpToPx(context, 1), Color.rgb(46, 204, 113));
        container.setBackground(bg);

        TextView header = new TextView(context);
        header.setText("🇬🇧 " + word);
        header.setTextColor(Color.rgb(46, 204, 113));
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        header.setTypeface(Typeface.DEFAULT_BOLD);
        container.addView(header);

        TextView body = new TextView(context);
        body.setText("⏳ جاري ترجمة الكلمة وكتابة الشرح المبسط...");
        body.setTextColor(Color.WHITE);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        body.setPadding(0, dpToPx(context, 8), 0, 0);
        container.addView(body);

        PopupWindow popup = new PopupWindow(
                container,
                dpToPx(context, popupWidthDp),
                ViewGroup.LayoutParams.WRAP_CONTENT,
                true
        );
        popup.setElevation(22f);
        popup.showAtLocation(anchor, Gravity.CENTER, 0, -dpToPx(context, 40));

        String provider = sp.getString(KEY_PROVIDER, "GEMINI");
        String geminiKey = sp.getString(KEY_GEMINI_KEY, "").trim();
        String nvidiaKey = sp.getString(KEY_NVIDIA_KEY, "").trim();
        String model = sp.getString(KEY_SELECTED_MODEL, "gemini-2.5-flash").trim();
        String customPrompt = sp.getString(KEY_CUSTOM_PROMPT, DEFAULT_ARABIC_PROMPT).trim();

        executor.execute(() -> {
            String fullPrompt = customPrompt + "\n\nالكلمة المطلوبة: \"" + word + "\"\nالجملة في الفيديو: \"" + fullSentence + "\"";
            String result;
            if ("NVIDIA".equals(provider) && !nvidiaKey.isEmpty()) {
                result = queryNvidiaApi(nvidiaKey, model, fullPrompt, word);
            } else if (!geminiKey.isEmpty()) {
                result = queryGeminiApi(geminiKey, model, fullPrompt, word);
            } else {
                result = queryFallbackDictionary(word) +
                        "\n\n💡 اضغط مطولاً على زرار (AI Sub) لإدخال مفتاح Gemini أو Nvidia المجاني.";
            }
            mainHandler.post(() -> body.setText(result));
        });
    }

    private static String queryGeminiApi(String apiKey, String model, String prompt, String word) {
        try {
            String cleanModel = model.replace("models/", "").trim();
            if (cleanModel.isEmpty() || cleanModel.contains("/")) cleanModel = "gemini-2.5-flash";
            String url = "https://generativelanguage.googleapis.com/v1beta/models/"
                    + cleanModel + ":generateContent?key=" + URLEncoder.encode(apiKey, "UTF-8");

            JSONObject part = new JSONObject().put("text", prompt);
            JSONObject content = new JSONObject().put("parts", new JSONArray().put(part));
            JSONObject payload = new JSONObject().put("contents", new JSONArray().put(content));

            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            String resp = readStream(code == 200 ? conn.getInputStream() : conn.getErrorStream());
            if (code != 200) return "⚠️ خطأ Gemini (" + code + ")\n" + queryFallbackDictionary(word);

            return new JSONObject(resp).getJSONArray("candidates").getJSONObject(0)
                    .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text").trim();
        } catch (Exception e) {
            return queryFallbackDictionary(word);
        }
    }

    private static String queryNvidiaApi(String apiKey, String model, String prompt, String word) {
        try {
            String cleanModel = model.contains("/") ? model : "meta/llama-3.3-70b-instruct";
            String url = "https://integrate.api.nvidia.com/v1/chat/completions";

            JSONObject msg = new JSONObject().put("role", "user").put("content", prompt);
            JSONObject payload = new JSONObject()
                    .put("model", cleanModel)
                    .put("messages", new JSONArray().put(msg))
                    .put("max_tokens", 350)
                    .put("temperature", 0.3);

            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setConnectTimeout(9000);
            conn.setReadTimeout(9000);
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            String resp = readStream(code == 200 ? conn.getInputStream() : conn.getErrorStream());
            if (code != 200) return "⚠️ خطأ Nvidia API (" + code + ")\n" + queryFallbackDictionary(word);

            return new JSONObject(resp).getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").getString("content").trim();
        } catch (Exception e) {
            return queryFallbackDictionary(word);
        }
    }
      private static String queryFallbackDictionary(String word) {
        try {
            String urlStr = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=en&tl=ar&dt=t&dt=bd&q="
                    + URLEncoder.encode(word, "UTF-8");
            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(5000);
            JSONArray root = new JSONArray(readStream(conn.getInputStream()));
            String primary = root.getJSONArray(0).getJSONArray(0).getString(0);
            StringBuilder sb = new StringBuilder("🎯 الترجمة: ").append(primary);
            if (!root.isNull(1)) {
                JSONArray dict = root.getJSONArray(1);
                sb.append("\n\n📚 المعاني الشائعة:");
                for (int i = 0; i < Math.min(dict.length(), 3); i++) {
                    JSONArray entry = dict.getJSONArray(i);
                    JSONArray terms = entry.getJSONArray(1);
                    List<String> top = new ArrayList<>();
                    for (int j = 0; j < Math.min(terms.length(), 4); j++) top.add(terms.getString(j));
                    sb.append("\n• (").append(entry.getString(0)).append("): ").append(joinList(top, "، "));
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return "تعذر الاتصال بالقاموس.";
        }
    }

    public static void showSettingsDialog(Context context) {
        SharedPreferences sp = prefs(context);
        ScrollView scroll = new ScrollView(context);
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = dpToPx(context, 16);
        layout.setPadding(pad, pad, pad, pad);
        layout.setBackgroundColor(Color.rgb(22, 24, 30));
        scroll.addView(layout);

        addSectionTitle(context, layout, "🌐 اختر مزود الذكاء الاصطناعي:");
        Spinner providerSpinner = new Spinner(context);
        List<String> providers = Arrays.asList("GEMINI (Google AI)", "NVIDIA (Free NIM API)");
        providerSpinner.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item, providers));
        providerSpinner.setSelection("NVIDIA".equals(sp.getString(KEY_PROVIDER, "GEMINI")) ? 1 : 0);
        layout.addView(providerSpinner);

        addSectionTitle(context, layout, "🔑 مفتاح Gemini API المجاني:");
        EditText geminiInput = new EditText(context);
        geminiInput.setHint("AIzaSy...");
        geminiInput.setTextColor(Color.WHITE);
        geminiInput.setHintTextColor(Color.GRAY);
        geminiInput.setText(sp.getString(KEY_GEMINI_KEY, ""));
        layout.addView(geminiInput);

        addSectionTitle(context, layout, "🟢 مفتاح NVIDIA API المجاني:");
        EditText nvidiaInput = new EditText(context);
        nvidiaInput.setHint("nvapi-...");
        nvidiaInput.setTextColor(Color.WHITE);
        nvidiaInput.setHintTextColor(Color.GRAY);
        nvidiaInput.setText(sp.getString(KEY_NVIDIA_KEY, ""));
        layout.addView(nvidiaInput);

        addSectionTitle(context, layout, "🤖 النموذج المتاح (اضغط تحديث لجلب النماذج):");
        Spinner modelSpinner = new Spinner(context);
        String savedModels = sp.getString(KEY_MODEL_LIST, "gemini-2.5-flash,gemini-2.5-pro,meta/llama-3.3-70b-instruct,deepseek-ai/deepseek-r1");
        List<String> modelList = new ArrayList<>(Arrays.asList(savedModels.split(",")));
        ArrayAdapter<String> modelAdapter = new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item, modelList);
        modelSpinner.setAdapter(modelAdapter);
        int idx = modelList.indexOf(sp.getString(KEY_SELECTED_MODEL, "gemini-2.5-flash"));
        if (idx >= 0) modelSpinner.setSelection(idx);
        layout.addView(modelSpinner);

        Button refreshBtn = new Button(context);
        refreshBtn.setText("🔄 تحديث النماذج المتاحة من المزود المختار");
        refreshBtn.setOnClickListener(v -> {
            boolean isNvidia = providerSpinner.getSelectedItemPosition() == 1;
            String key = (isNvidia ? nvidiaInput : geminiInput).getText().toString().trim();
            refreshBtn.setText("⏳ جاري جلب النماذج...");
            executor.execute(() -> {
                List<String> fetched = isNvidia ? fetchNvidiaModels(key) : fetchGeminiModels(key);
                mainHandler.post(() -> {
                    refreshBtn.setText("🔄 تحديث النماذج المتاحة من المزود المختار");
                    if (fetched.isEmpty()) {
                        Toast.makeText(context, "⚠️ تعذر جلب النماذج، تأكد من المفتاح", Toast.LENGTH_LONG).show();
                    } else {
                        modelList.clear();
                        modelList.addAll(fetched);
                        modelAdapter.notifyDataSetChanged();
                        sp.edit().putString(KEY_MODEL_LIST, joinList(fetched, ",")).apply();
                        Toast.makeText(context, "✅ تم جلب " + fetched.size() + " نموذج!", Toast.LENGTH_SHORT).show();
                    }
                });
            });
        });
        layout.addView(refreshBtn);

        addSectionTitle(context, layout, "📝 أوامر الذكاء الاصطناعي بالعربي (الترجمة والشرح):");
        EditText promptInput = new EditText(context);
        promptInput.setTextColor(Color.WHITE);
        promptInput.setText(sp.getString(KEY_CUSTOM_PROMPT, DEFAULT_ARABIC_PROMPT));
        layout.addView(promptInput);

        addSectionTitle(context, layout, "🎨 شكل خلفية الترجمة:");
        Spinner bgModeSpinner = new Spinner(context);
        List<String> bgModes = Arrays.asList("خلفية نصف شفافة", "خلفية سوداء داكنة", "خلفية شفافة تماماً");
        bgModeSpinner.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item, bgModes));
        bgModeSpinner.setSelection(sp.getInt(KEY_BG_MODE, 0));
        layout.addView(bgModeSpinner);

        int[] fontSize = {sp.getInt(KEY_FONT_SIZE, 18)};
        addSeekBarControl(context, layout, "تكبير وتصغير خط الترجمة (SP)", 12, 36, fontSize[0], val -> fontSize[0] = val);

        int[] subAlpha = {sp.getInt(KEY_SUB_ALPHA, 180)};
        addSeekBarControl(context, layout, "درجة شفافية خلفية الترجمة", 0, 255, subAlpha[0], val -> subAlpha[0] = val);

        int[] bottomMargin = {sp.getInt(KEY_BOTTOM_MARGIN, 62)};
        addSeekBarControl(context, layout, "ارتفاع الترجمة من الأسفل (DP)", 20, 180, bottomMargin[0], val -> bottomMargin[0] = val);

        int[] popupWidth = {sp.getInt(KEY_POPUP_WIDTH, 320)};
        addSeekBarControl(context, layout, "عرض نافذة الشرح (DP)", 220, 390, popupWidth[0], val -> popupWidth[0] = val);

        new AlertDialog.Builder(context)
                .setTitle("⚙️ إعدادات ترجمة الذكاء الاصطناعي")
                .setView(scroll)
                .setPositiveButton("حفظ وتطبيق", (d, w) -> {
                    sp.edit()
                            .putString(KEY_PROVIDER, providerSpinner.getSelectedItemPosition() == 1 ? "NVIDIA" : "GEMINI")
                            .putString(KEY_GEMINI_KEY, geminiInput.getText().toString().trim())
                            .putString(KEY_NVIDIA_KEY, nvidiaInput.getText().toString().trim())
                            .putString(KEY_SELECTED_MODEL, modelSpinner.getSelectedItem() != null ? modelSpinner.getSelectedItem().toString() : "gemini-2.5-flash")
                            .putString(KEY_CUSTOM_PROMPT, promptInput.getText().toString().trim())
                            .putInt(KEY_BG_MODE, bgModeSpinner.getSelectedItemPosition())
                            .putInt(KEY_FONT_SIZE, fontSize[0])
                            .putInt(KEY_SUB_ALPHA, subAlpha[0])
                            .putInt(KEY_BOTTOM_MARGIN, bottomMargin[0])
                            .putInt(KEY_POPUP_WIDTH, popupWidth[0])
                            .apply();
                    if (customSubBox != null) applySubtitleBoxStyle(context, customSubBox);
                    updateCornerButtonAppearance(context);
                    Toast.makeText(context, "✅ تم حفظ الإعدادات بنجاح", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("إلغاء", null)
                .show();
    }

    private static List<String> fetchGeminiModels(String apiKey) {
        List<String> list = new ArrayList<>();
        try {
            String url = "https://generativelanguage.googleapis.com/v1beta/models?key=" + URLEncoder.encode(apiKey, "UTF-8");
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            if (c.getResponseCode() != 200) return list;
            JSONArray models = new JSONObject(readStream(c.getInputStream())).getJSONArray("models");
            for (int i = 0; i < models.length(); i++) {
                String name = models.getJSONObject(i).getString("name").replace("models/", "");
                if (name.startsWith("gemini")) list.add(name);
            }
        } catch (Exception ignored) {}
        return list;
    }

    private static List<String> fetchNvidiaModels(String apiKey) {
        List<String> list = new ArrayList<>();
        try {
            HttpURLConnection c = (HttpURLConnection) new URL("https://integrate.api.nvidia.com/v1/models").openConnection();
            if (!apiKey.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + apiKey);
            if (c.getResponseCode() != 200) return list;
            JSONArray data = new JSONObject(readStream(c.getInputStream())).getJSONArray("data");
            for (int i = 0; i < data.length(); i++) {
                String id = data.getJSONObject(i).optString("id", "");
                if (!id.isEmpty()) list.add(id);
            }
        } catch (Exception ignored) {}
        return list;
    }

    private interface OnValueChange { void onChange(int newValue); }

    private static void addSectionTitle(Context ctx, LinearLayout p, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextColor(Color.rgb(46, 204, 113));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setPadding(0, dpToPx(ctx, 12), 0, dpToPx(ctx, 4));
        p.addView(tv);
    }

    private static void addSeekBarControl(Context ctx, LinearLayout p, String title, int min, int max, int cur, OnValueChange l) {
        TextView tv = new TextView(ctx);
        tv.setText(title + ": " + cur);
        tv.setTextColor(Color.WHITE);
        p.addView(tv);
        SeekBar sb = new SeekBar(ctx);
        sb.setMax(max - min);
        sb.setProgress(Math.max(0, cur - min));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int pr, boolean f) { int v = min + pr; tv.setText(title + ": " + v); l.onChange(v); }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        p.addView(sb);
    }

    private static ViewGroup findPlayerRootContainer(View v) {
        ViewParent p = v.getParent();
        ViewGroup last = null;
        int d = 0;
        while (p instanceof ViewGroup && d < 5) { last = (ViewGroup) p; p = p.getParent(); d++; }
        return last;
    }

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE); }
    private static boolean isFeatureEnabled(Context c) { return prefs(c).getBoolean(KEY_ENABLED, true); }
    private static int dpToPx(Context c, int dp) { return Math.round(dp * c.getResources().getDisplayMetrics().density); }

    private static String joinList(List<String> list, String del) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) { if (i > 0) sb.append(del); sb.append(list.get(i)); }
        return sb.toString();
    }

    private static String readStream(InputStream is) throws Exception {
        if (is == null) return "";
        BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) sb.append(line).append("\n");
        r.close();
        return sb.toString();
    }
}
