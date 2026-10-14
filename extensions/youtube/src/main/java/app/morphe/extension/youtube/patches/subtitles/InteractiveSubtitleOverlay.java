package app.morphe.extension.youtube.patches.subtitles;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
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
    private static final String KEY_API_KEY = "gemini_api_key";
    private static final String KEY_SELECTED_MODEL = "gemini_selected_model";
    private static final String KEY_MODEL_LIST = "gemini_model_list";
    private static final String KEY_FONT_SIZE = "sub_font_size";
    private static final String KEY_SUB_ALPHA = "sub_bg_alpha";
    private static final String KEY_POPUP_ALPHA = "popup_bg_alpha";
    private static final String KEY_POPUP_WIDTH = "popup_width_dp";
    private static final String KEY_BOTTOM_MARGIN = "sub_bottom_margin_dp";
    private static final String KEY_BTN_ALPHA = "corner_btn_alpha";

    private static final String OverlayTag = "MORPHE_INTERACTIVE_SUB_BOX";
    private static final String CornerButtonTag = "MORPHE_CORNER_AI_BUTTON";

    private static final ExecutorService executor = Executors.newCachedThreadPool();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    private static TextView customSubBox;
    private static TextView cornerButton;
    private static Runnable hideButtonRunnable;

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

        subtitleView.setAlpha(0.0f);

        if (rawText == null || rawText.toString().trim().isEmpty()) {
            if (customSubBox != null) customSubBox.setVisibility(View.GONE);
            return;
        }

        String sentence = rawText.toString().trim();
        if (customSubBox != null) {
            applySubtitleBoxStyle(context, customSubBox);
            customSubBox.setVisibility(View.VISIBLE);
            populateClickableWords(customSubBox, sentence);
        } else if (subtitleView instanceof TextView) {
            subtitleView.setAlpha(1.0f);
            TextView tv = (TextView) subtitleView;
            applySubtitleBoxStyle(context, tv);
            populateClickableWords(tv, sentence);
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
        cornerButton.setText("AI EN");
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
        params.setMargins(dpToPx(context, 16), dpToPx(context, 16), dpToPx(context, 18), dpToPx(context, 48));
        root.addView(cornerButton, params);

        root.setOnHierarchyChangeListener(new ViewGroup.OnHierarchyChangeListener() {
            @Override
            public void onChildViewAdded(View parent, View child) {
                onPlayerControlsVisibilityChanged(true);
            }
            @Override
            public void onChildViewRemoved(View parent, View child) {}
        });

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
            Toast.makeText(context, newState ? "تم تفعيل الترجمة التفاعلية (English AI)" : "تم إيقاف الترجمة التفاعلية", Toast.LENGTH_SHORT).show();
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
        int alpha = prefs(context).getInt(KEY_BTN_ALPHA, 210);

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dpToPx(context, 20));
        if (enabled) {
            bg.setColor(Color.argb(alpha, 20, 90, 50));
            bg.setStroke(dpToPx(context, 1), Color.argb(255, 46, 204, 113));
            cornerButton.setTextColor(Color.WHITE);
        } else {
            bg.setColor(Color.argb(alpha, 45, 45, 45));
            bg.setStroke(dpToPx(context, 1), Color.argb(180, 150, 150, 150));
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
        int bgAlpha = sp.getInt(KEY_SUB_ALPHA, 175);
        int bottomMarginDp = sp.getInt(KEY_BOTTOM_MARGIN, 64);

        box.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize);
        int padH = dpToPx(context, 14);
        int padV = dpToPx(context, 6);
        box.setPadding(padH, padV, padH, padV);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(bgAlpha, 15, 15, 15));
        bg.setCornerRadius(dpToPx(context, 10));
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
        int popupAlpha = sp.getInt(KEY_POPUP_ALPHA, 240);
        int popupWidthDp = sp.getInt(KEY_POPUP_WIDTH, 310);

        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = dpToPx(context, 16);
        container.setPadding(pad, pad, pad, pad);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(popupAlpha, 22, 24, 29));
        bg.setCornerRadius(dpToPx(context, 16));
        bg.setStroke(dpToPx(context, 1), Color.argb(200, 46, 204, 113));
        container.setBackground(bg);

        TextView header = new TextView(context);
        header.setText("🇬🇧 " + word);
        header.setTextColor(Color.rgb(46, 204, 113));
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        header.setTypeface(Typeface.DEFAULT_BOLD);
        container.addView(header);

        TextView body = new TextView(context);
        body.setText("⏳ جاري تحليل الكلمة والمعاني الشائعة بالذكاء الاصطناعي...");
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
        popup.setElevation(20f);
        popup.showAtLocation(anchor, Gravity.CENTER, 0, -dpToPx(context, 40));

        String apiKey = sp.getString(KEY_API_KEY, "").trim();
        String model = sp.getString(KEY_SELECTED_MODEL, "gemini-2.5-flash").trim();

        executor.execute(() -> {
            String result;
            if (!apiKey.isEmpty()) {
                result = queryGeminiTranslation(apiKey, model, word, fullSentence);
            } else {
                result = queryFallbackDictionary(word) +
                        "\n\n💡 نصيحة: اضغط مطولاً على زرار (AI EN) في الركن لإضافة مفتاح Gemini API مجاناً والحصول على شرح سياقي كامل.";
            }
            mainHandler.post(() -> body.setText(result));
        });
    }

    private static String queryGeminiTranslation(String apiKey, String model, String word, String sentence) {
        try {
            String cleanModel = model.replace("models/", "").trim();
            String endpoint = "https://generativelanguage.googleapis.com/v1beta/models/"
                    + cleanModel + ":generateContent?key=" + URLEncoder.encode(apiKey, "UTF-8");

            String prompt = "أنت قاموس ذكي لتعليم الإنجليزية. الكلمة المطلوبة هي: \"" + word + "\"\n"
                    + "الجملة التي وردت فيها الكلمة في الفيديو: \"" + sentence + "\"\n"
                    + "أجب باللغة العربية باختصار ووضوح في 3 نقاط محددة:\n"
                    + "1. 🎯 المعنى في السياق: (معنى الكلمة الدقيق داخل هذه الجملة)\n"
                    + "2. 📚 المعاني الشائعة الأخرى: (أهم 3 معاني شائعة للكلمة مع نوعها اسم/فعل/صفة)\n"
                    + "3. 🗣️ النطق ومثال بسيط.";

            JSONObject textPart = new JSONObject().put("text", prompt);
            JSONObject parts = new JSONObject().put("parts", new JSONArray().put(textPart));
            JSONObject payload = new JSONObject().put("contents", new JSONArray().put(parts));

            HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setConnectTimeout(7000);
            conn.setReadTimeout(7000);
            conn.setDoOutput(true);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload.toString().getBytes(StandardCharsets.UTF_8));
            }

            int status = conn.getResponseCode();
            InputStream is = (status >= 200 && status < 300) ? conn.getInputStream() : conn.getErrorStream();
            String responseStr = readStream(is);

            if (status != 200) {
                return "⚠️ خطأ من Gemini (" + status + "):\nتأكد من صحة الـ API Key أو غيّر النموذج من الإعدادات.\n\n"
                        + queryFallbackDictionary(word);
            }

            JSONObject json = new JSONObject(responseStr);
            return json.getJSONArray("candidates")
                    .getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
                    .getJSONObject(0)
                    .getString("text")
                    .trim();
        } catch (Exception e) {
            return queryFallbackDictionary(word);
        }
                         }
      private static String queryFallbackDictionary(String word) {
        try {
            String urlStr = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=en&tl=ar&dt=t&dt=bd&q="
                    + URLEncoder.encode(word, "UTF-8");
            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            String response = readStream(conn.getInputStream());
            JSONArray root = new JSONArray(response);
            String primary = root.getJSONArray(0).getJSONArray(0).getString(0);

            StringBuilder sb = new StringBuilder();
            sb.append("🎯 المعنى الأساسي: ").append(primary);

            if (!root.isNull(1)) {
                JSONArray dict = root.getJSONArray(1);
                sb.append("\n\n📚 المعاني الشائعة:");
                for (int i = 0; i < Math.min(dict.length(), 3); i++) {
                    JSONArray entry = dict.getJSONArray(i);
                    String pos = entry.getString(0);
                    JSONArray terms = entry.getJSONArray(1);
                    List<String> topTerms = new ArrayList<>();
                    for (int j = 0; j < Math.min(terms.length(), 4); j++) {
                        topTerms.add(terms.getString(j));
                    }
                    sb.append("\n• (").append(pos).append("): ").append(joinList(topTerms, "، "));
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return "تعذر الاتصال بالإنترنت لجلب الترجمة.";
        }
    }

    public static void showSettingsDialog(Context context) {
        SharedPreferences sp = prefs(context);

        ScrollView scrollView = new ScrollView(context);
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = dpToPx(context, 18);
        layout.setPadding(pad, pad, pad, pad);
        layout.setBackgroundColor(Color.rgb(24, 26, 31));
        scrollView.addView(layout);

        addSectionTitle(context, layout, "🔑 مفتاح Gemini API المجاني:");
        EditText apiKeyInput = new EditText(context);
        apiKeyInput.setHint("الصق مفتاح Gemini API هنا (AIzaSy...)");
        apiKeyInput.setHintTextColor(Color.GRAY);
        apiKeyInput.setTextColor(Color.WHITE);
        apiKeyInput.setText(sp.getString(KEY_API_KEY, ""));
        layout.addView(apiKeyInput);

        addSectionTitle(context, layout, "🤖 اختيار نموذج Gemini:");
        Spinner modelSpinner = new Spinner(context);
        String savedModelsCsv = sp.getString(KEY_MODEL_LIST, "gemini-2.5-flash,gemini-2.5-pro,gemini-2.0-flash");
        List<String> modelList = new ArrayList<>(Arrays.asList(savedModelsCsv.split(",")));
        ArrayAdapter<String> spinnerAdapter = new ArrayAdapter<>(
                context, android.R.layout.simple_spinner_dropdown_item, modelList
        );
        modelSpinner.setAdapter(spinnerAdapter);
        String currentModel = sp.getString(KEY_SELECTED_MODEL, "gemini-2.5-flash");
        int currentIndex = modelList.indexOf(currentModel);
        if (currentIndex >= 0) modelSpinner.setSelection(currentIndex);
        layout.addView(modelSpinner);

        Button refreshModelsBtn = new Button(context);
        refreshModelsBtn.setText("🔄 تحديث النماذج المتاحة من المفتاح");
        refreshModelsBtn.setOnClickListener(v -> {
            String key = apiKeyInput.getText().toString().trim();
            if (key.isEmpty()) {
                Toast.makeText(context, "أدخل مفتاح Gemini API أولاً ثم اضغط تحديث", Toast.LENGTH_SHORT).show();
                return;
            }
            refreshModelsBtn.setText("⏳ جاري جلب النماذج...");
            refreshModelsBtn.setEnabled(false);

            executor.execute(() -> {
                List<String> fetched = fetchAvailableGeminiModels(key);
                mainHandler.post(() -> {
                    refreshModelsBtn.setEnabled(true);
                    refreshModelsBtn.setText("🔄 تحديث النماذج المتاحة من المفتاح");
                    if (fetched.isEmpty()) {
                        Toast.makeText(context, "تعذر جلب النماذج، تأكد من صحة المفتاح", Toast.LENGTH_LONG).show();
                    } else {
                        modelList.clear();
                        modelList.addAll(fetched);
                        spinnerAdapter.notifyDataSetChanged();
                        sp.edit().putString(KEY_MODEL_LIST, joinList(fetched, ",")).apply();
                        Toast.makeText(context, "✅ تم العثور على " + fetched.size() + " نموذج متاح!", Toast.LENGTH_SHORT).show();
                    }
                });
            });
        });
        layout.addView(refreshModelsBtn);

        int[] fontSizeVal = {sp.getInt(KEY_FONT_SIZE, 18)};
        addSeekBarControl(context, layout, "حجم خط الترجمة الإنجليزية (SP)", 12, 32, fontSizeVal[0], val -> fontSizeVal[0] = val);

        int[] subAlphaVal = {sp.getInt(KEY_SUB_ALPHA, 175)};
        addSeekBarControl(context, layout, "شفافية خلفية الترجمة على الشاشة", 0, 255, subAlphaVal[0], val -> subAlphaVal[0] = val);

        int[] bottomMarginVal = {sp.getInt(KEY_BOTTOM_MARGIN, 64)};
        addSeekBarControl(context, layout, "ارتفاع الترجمة من أسفل المشغل (DP)", 20, 160, bottomMarginVal[0], val -> bottomMarginVal[0] = val);

        int[] popupWidthVal = {sp.getInt(KEY_POPUP_WIDTH, 310)};
        addSeekBarControl(context, layout, "عرض نافذة القاموس المنبثقة (DP)", 220, 380, popupWidthVal[0], val -> popupWidthVal[0] = val);

        int[] popupAlphaVal = {sp.getInt(KEY_POPUP_ALPHA, 240)};
        addSeekBarControl(context, layout, "شفافية نافذة القاموس", 80, 255, popupAlphaVal[0], val -> popupAlphaVal[0] = val);

        int[] btnAlphaVal = {sp.getInt(KEY_BTN_ALPHA, 210)};
        addSeekBarControl(context, layout, "شفافية زرار الركن (AI EN)", 50, 255, btnAlphaVal[0], val -> btnAlphaVal[0] = val);

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle("⚙️ إعدادات ترجمة الذكاء الاصطناعي")
                .setView(scrollView)
                .setPositiveButton("حفظ وتطبيق", (d, which) -> {
                    String selectedModel = modelSpinner.getSelectedItem() != null
                            ? modelSpinner.getSelectedItem().toString()
                            : "gemini-2.5-flash";

                    sp.edit()
                            .putString(KEY_API_KEY, apiKeyInput.getText().toString().trim())
                            .putString(KEY_SELECTED_MODEL, selectedModel)
                            .putInt(KEY_FONT_SIZE, fontSizeVal[0])
                            .putInt(KEY_SUB_ALPHA, subAlphaVal[0])
                            .putInt(KEY_BOTTOM_MARGIN, bottomMarginVal[0])
                            .putInt(KEY_POPUP_WIDTH, popupWidthVal[0])
                            .putInt(KEY_POPUP_ALPHA, popupAlphaVal[0])
                            .putInt(KEY_BTN_ALPHA, btnAlphaVal[0])
                            .apply();

                    if (customSubBox != null) applySubtitleBoxStyle(context, customSubBox);
                    updateCornerButtonAppearance(context);
                    Toast.makeText(context, "✅ تم حفظ الإعدادات وتطبيق الأبعاد الجديدة", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("إلغاء", null)
                .create();

        dialog.show();
    }
      private static List<String> fetchAvailableGeminiModels(String apiKey) {
        List<String> result = new ArrayList<>();
        try {
            String urlStr = "https://generativelanguage.googleapis.com/v1beta/models?key=" + URLEncoder.encode(apiKey, "UTF-8");
            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(6000);

            if (conn.getResponseCode() != 200) return result;

            JSONObject json = new JSONObject(readStream(conn.getInputStream()));
            JSONArray models = json.getJSONArray("models");
            for (int i = 0; i < models.length(); i++) {
                JSONObject m = models.getJSONObject(i);
                String name = m.getString("name").replace("models/", "");
                JSONArray methods = m.optJSONArray("supportedGenerationMethods");
                boolean supportsGenerate = false;
                if (methods != null) {
                    for (int j = 0; j < methods.length(); j++) {
                        if ("generateContent".equals(methods.getString(j))) {
                            supportsGenerate = true;
                            break;
                        }
                    }
                }
                if (supportsGenerate && name.startsWith("gemini")) {
                    result.add(name);
                }
            }
        } catch (Exception ignored) {}
        return result;
    }

    private interface OnValueChange {
        void onChange(int newValue);
    }

    private static void addSectionTitle(Context context, LinearLayout parent, String text) {
        TextView tv = new TextView(context);
        tv.setText(text);
        tv.setTextColor(Color.rgb(46, 204, 113));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setPadding(0, dpToPx(context, 12), 0, dpToPx(context, 4));
        parent.addView(tv);
    }

    private static void addSeekBarControl(Context context, LinearLayout parent, String title, int min, int max, int current, OnValueChange listener) {
        TextView label = new TextView(context);
        label.setText(title + ": " + current);
        label.setTextColor(Color.WHITE);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        label.setPadding(0, dpToPx(context, 10), 0, dpToPx(context, 2));
        parent.addView(label);

        SeekBar seekBar = new SeekBar(context);
        seekBar.setMax(max - min);
        seekBar.setProgress(Math.max(0, current - min));
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                int val = min + progress;
                label.setText(title + ": " + val);
                listener.onChange(val);
            }
            @Override
            public void onStartTrackingTouch(SeekBar s) {}
            @Override
            public void onStopTrackingTouch(SeekBar s) {}
        });
        parent.addView(seekBar);
    }

    private static ViewGroup findPlayerRootContainer(View v) {
        ViewParent parent = v.getParent();
        ViewGroup lastValidGroup = null;
        int depth = 0;
        while (parent instanceof ViewGroup && depth < 5) {
            lastValidGroup = (ViewGroup) parent;
            parent = parent.getParent();
            depth++;
        }
        return lastValidGroup;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static boolean isFeatureEnabled(Context context) {
        return prefs(context).getBoolean(KEY_ENABLED, true);
    }

    private static int dpToPx(Context context, int dp) {
        return Math.round(dp * context.getResources().getDisplayMetrics().density);
    }

    private static String joinList(List<String> list, String delimiter) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(delimiter);
            sb.append(list.get(i));
        }
        return sb.toString();
    }

    private static String readStream(InputStream is) throws Exception {
        if (is == null) return "";
        BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line).append("\n");
        }
        reader.close();
        return sb.toString();
    }
}

  
  
