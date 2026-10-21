package com.hozinking.notes.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * v1.21: diagram batang 30 hari mood. Tiap batang = satu hari,
 * tinggi & warna mengikuti nilai mood (1..5); hari tanpa catatan
 * digambar sebagai batang abu-abu pendek. Label tanggal tiap 5 hari.
 */
public class MoodChartView extends View {
    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF barRect = new RectF();

    private List<String> dayKeys = new ArrayList<>();
    private Map<String, Integer> moods = new HashMap<>();

    public MoodChartView(Context context) {
        super(context);
        init();
    }

    public MoodChartView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        labelPaint.setColor(0xFF9E9E9E);
        labelPaint.setTextSize(sp(10));
        labelPaint.setTextAlign(Paint.Align.CENTER);
    }

    private float sp(float v) {
        return v * getResources().getDisplayMetrics().scaledDensity;
    }

    /** Set data: kunci tanggal (urut menaik) + peta tanggal->mood. */
    public void setData(List<String> keys, Map<String, Integer> moodMap) {
        dayKeys = keys == null ? new ArrayList<>() : keys;
        moods = moodMap == null ? new HashMap<>() : moodMap;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int n = dayKeys.size();
        if (n == 0) return;

        float padL = dp(8), padR = dp(8), padT = dp(8), padB = dp(26);
        float w = getWidth() - padL - padR;
        float h = getHeight() - padT - padB;
        if (w <= 0 || h <= 0) return;

        float slot = w / n;
        float barW = Math.max(dp(4), slot * 0.55f);
        float radius = Math.min(dp(4), barW / 2f);

        for (int i = 0; i < n; i++) {
            String key = dayKeys.get(i);
            Integer m = moods.get(key);
            float cx = padL + slot * i + slot / 2f;
            float frac = (m == null) ? 0.08f : (m / 5f);
            float top = padT + h * (1f - frac);
            float bottom = padT + h;
            barRect.set(cx - barW / 2f, top, cx + barW / 2f, bottom);
            barPaint.setColor(m == null ? 0xFFE0E0E0 : MoodHelper.color(m));
            canvas.drawRoundRect(barRect, radius, radius, barPaint);

            // Label tanggal (dd) tiap 5 hari + hari terakhir.
            if (i % 5 == 4 || i == n - 1) {
                String dd = key.length() >= 10 ? key.substring(8, 10) : "";
                canvas.drawText(dd, cx, padT + h + dp(16), labelPaint);
            }
        }
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
