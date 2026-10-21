package com.hozinking.notes.ui;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.hozinking.notes.R;
import com.hozinking.notes.data.db.MoodEntry;
import com.hozinking.notes.data.repo.NoteRepository;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * v1.21: Ringkasan mood 30 hari terakhir — rata-rata, streak hari beruntun,
 * diagram batang harian, dan sebaran per nilai mood.
 */
public class MoodStatsActivity extends AppCompatActivity {

    private final Handler main = new Handler(Looper.getMainLooper());

    private TextView tvAvg;
    private TextView tvAvgLabel;
    private TextView tvStreak;
    private TextView tvEmpty;
    private MoodChartView chart;
    private LinearLayout distRow;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_mood_stats);

        tvAvg = findViewById(R.id.mood_avg_value);
        tvAvgLabel = findViewById(R.id.mood_avg_label);
        tvStreak = findViewById(R.id.mood_streak_value);
        tvEmpty = findViewById(R.id.mood_empty);
        chart = findViewById(R.id.mood_chart);
        distRow = findViewById(R.id.mood_dist_row);

        load();
    }

    private void load() {
        List<String> keys = MoodHelper.last30DayKeys();
        String from = keys.get(0);
        String to = keys.get(keys.size() - 1);
        NoteRepository.get(this).moodRange(from, to, entries ->
                main.post(() -> render(keys, entries)));
    }

    private void render(List<String> keys, List<MoodEntry> entries) {
        Map<String, Integer> map = new HashMap<>();
        Set<String> recorded = new HashSet<>();
        int[] counts = new int[6]; // indeks 1..5
        double sum = 0;
        for (MoodEntry e : entries) {
            if (e.mood < MoodHelper.MIN || e.mood > MoodHelper.MAX) continue;
            map.put(e.date, e.mood);
            recorded.add(e.date);
            counts[e.mood]++;
            sum += e.mood;
        }
        int total = entries.size();

        boolean empty = total == 0;
        tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        chart.setVisibility(empty ? View.GONE : View.VISIBLE);

        if (!empty) {
            double avg = sum / total;
            // Format Indonesia: koma sebagai pemisah desimal.
            String avgText = String.format(Locale.forLanguageTag("id-ID"), "%.1f", avg);
            tvAvg.setText(avgText);
            int nearest = (int) Math.round(avg);
            nearest = Math.max(MoodHelper.MIN, Math.min(MoodHelper.MAX, nearest));
            tvAvgLabel.setText(MoodHelper.emoji(nearest) + " " + MoodHelper.label(nearest));

            int streak = MoodHelper.streakDays(recorded);
            tvStreak.setText(streak <= 0
                    ? getString(R.string.mood_streak_none)
                    : getString(R.string.mood_streak_days, streak));

            chart.setData(keys, map);
            renderDistribution(counts, total);
        } else {
            tvAvg.setText("–");
            tvAvgLabel.setText("");
            tvStreak.setText(getString(R.string.mood_streak_none));
        }
    }

    private void renderDistribution(int[] counts, int total) {
        distRow.removeAllViews();
        for (int m = MoodHelper.MIN; m <= MoodHelper.MAX; m++) {
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            col.setLayoutParams(lp);

            TextView emoji = new TextView(this);
            emoji.setText(MoodHelper.emoji(m));
            emoji.setTextSize(26);
            emoji.setGravity(android.view.Gravity.CENTER);
            col.addView(emoji);

            TextView count = new TextView(this);
            int pct = total == 0 ? 0 : Math.round(counts[m] * 100f / total);
            count.setText(counts[m] + " (" + pct + "%)");
            count.setTextSize(12);
            count.setTextColor(0xFF757575);
            count.setGravity(android.view.Gravity.CENTER);
            col.addView(count);

            distRow.addView(col);
        }
    }
}
