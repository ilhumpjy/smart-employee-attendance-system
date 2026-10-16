package my.edu.uitm.smartattendanceapp;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class HistoryActivity extends AppCompatActivity {

    private LinearLayout layoutHistory;
    private TextView     tabAll, tabPresent, tabLate, tabAbsent;
    private TextView     txtPillPresent, txtPillLate, txtPillAbsent;

    private int               employeeId;
    private List<JSONObject>  allRecords = new ArrayList<>();
    private String            activeFilter = "ALL";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_history);

        SharedPreferences prefs = getSharedPreferences("AttendanceApp", MODE_PRIVATE);
        employeeId = prefs.getInt("employee_id", -1);

        bindViews();
        NavHelper.setup(this, NavHelper.TAB_HISTORY);
        setupFilterTabs();
        showLoading();
        loadHistory();
    }

    private void bindViews() {
        layoutHistory  = findViewById(R.id.layoutHistory);
        tabAll         = findViewById(R.id.tabAll);
        tabPresent     = findViewById(R.id.tabPresent);
        tabLate        = findViewById(R.id.tabLate);
        tabAbsent      = findViewById(R.id.tabAbsent);
        txtPillPresent = findViewById(R.id.txtPillPresent);
        txtPillLate    = findViewById(R.id.txtPillLate);
        txtPillAbsent  = findViewById(R.id.txtPillAbsent);
    }

    // ── Filter tabs ──────────────────────────────────────────────────
    private void setupFilterTabs() {
        tabAll.setOnClickListener(v     -> applyFilter("ALL",     tabAll));
        tabPresent.setOnClickListener(v -> applyFilter("PRESENT", tabPresent));
        tabLate.setOnClickListener(v    -> applyFilter("LATE",    tabLate));
        tabAbsent.setOnClickListener(v  -> applyFilter("ABSENT",  tabAbsent));
    }

    private void applyFilter(String filter, TextView selected) {
        activeFilter = filter;
        resetTabs();
        selected.setBackgroundResource(R.drawable.bg_filter_tab_active);
        selected.setTextColor(Color.WHITE);

        List<JSONObject> list;
        if (filter.equals("ALL")) {
            list = allRecords;
        } else {
            list = new ArrayList<>();
            for (JSONObject r : allRecords) {
                if (r.optString("attendance_status", "").equalsIgnoreCase(filter))
                    list.add(r);
            }
        }
        renderRecords(list);
    }

    private void resetTabs() {
        for (TextView t : new TextView[]{tabAll, tabPresent, tabLate, tabAbsent}) {
            t.setBackgroundResource(R.drawable.bg_filter_tab_inactive);
            t.setTextColor(getResources().getColor(R.color.text_secondary));
        }
    }

    // ── Load from API ────────────────────────────────────────────────
    private void loadHistory() {
        OkHttpClient client = new OkHttpClient();
        Request request = new Request.Builder()
                .url(Config.BASE_URL + "/employee/attendance?employee_id=" + employeeId)
                .get().build();

        client.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                runOnUiThread(() -> showError("Failed to load. Check connection."));
            }

            @Override public void onResponse(Call call, Response response) throws IOException {
                String body = response.body().string();
                runOnUiThread(() -> {
                    try {
                        JSONObject json    = new JSONObject(body);
                        JSONArray  records = json.getJSONArray("records");
                        JSONObject stats   = json.optJSONObject("stats");

                        if (stats != null) {
                            txtPillPresent.setText(String.valueOf(stats.optInt("present", 0)));
                            txtPillLate.setText(   String.valueOf(stats.optInt("late",    0)));
                            txtPillAbsent.setText( String.valueOf(stats.optInt("absent",  0)));
                        }

                        allRecords.clear();
                        for (int i = 0; i < records.length(); i++)
                            allRecords.add(records.getJSONObject(i));

                        renderRecords(allRecords);
                    } catch (Exception e) {
                        showError("Error parsing data.");
                    }
                });
            }
        });
    }

    // ── Render list ───────────────────────────────────────────────────
    private void renderRecords(List<JSONObject> records) {
        layoutHistory.removeAllViews();
        if (records.isEmpty()) { showEmpty(); return; }
        for (JSONObject r : records) {
            addCard(
                    r.optString("attendance_date",   ""),
                    r.optString("clock_in_time",     ""),
                    r.optString("clock_out_time",    ""),
                    r.optString("attendance_status", ""));
        }
    }

    // ── Card builder ─────────────────────────────────────────────────
    private void addCard(String date, String in, String out, String status) {

        // Outer card
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setBackgroundResource(R.drawable.bg_card);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardLp.setMargins(0, 0, 0, dp(10));
        card.setLayoutParams(cardLp);

        // ── Date badge ──
        String[] parts = splitDate(date);
        boolean  isAbsent = status.equalsIgnoreCase("ABSENT");
        boolean  isLate   = status.equalsIgnoreCase("LATE");

        LinearLayout dateBadge = new LinearLayout(this);
        dateBadge.setOrientation(LinearLayout.VERTICAL);
        dateBadge.setGravity(Gravity.CENTER);
        if (isAbsent)      dateBadge.setBackgroundResource(R.drawable.bg_date_badge_absent);
        else if (isLate)   dateBadge.setBackgroundResource(R.drawable.bg_date_badge_late);
        else               dateBadge.setBackgroundResource(R.drawable.bg_date_badge_present);

        dateBadge.setPadding(dp(10), dp(8), dp(10), dp(8));
        LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(dp(50), dp(58));
        badgeLp.setMarginEnd(dp(12));
        dateBadge.setLayoutParams(badgeLp);

        int dayColor = isAbsent ? getResources().getColor(R.color.red_text)
                : isLate       ? getResources().getColor(R.color.yellow_text)
                :                getResources().getColor(R.color.green_text);

        TextView tvDay = new TextView(this);
        tvDay.setText(parts[0]);
        tvDay.setTextSize(22);
        tvDay.setTypeface(null, Typeface.BOLD);
        tvDay.setTextColor(dayColor);
        tvDay.setGravity(Gravity.CENTER);
        dateBadge.addView(tvDay);

        TextView tvMon = new TextView(this);
        tvMon.setText(parts[1].toUpperCase());
        tvMon.setTextSize(10);
        tvMon.setTypeface(null, Typeface.BOLD);
        tvMon.setTextColor(dayColor);
        tvMon.setGravity(Gravity.CENTER);
        dateBadge.addView(tvMon);

        card.addView(dateBadge);

        // ── Centre info ──
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT);
        infoLp.weight = 1;
        info.setLayoutParams(infoLp);

        // Times row
        LinearLayout timesRow = new LinearLayout(this);
        timesRow.setOrientation(LinearLayout.HORIZONTAL);
        timesRow.setGravity(Gravity.CENTER_VERTICAL);
        timesRow.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        timesRow.addView(makeTimeChip(formatTime(in),  "In"));
        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(dp(20), 1));
        timesRow.addView(spacer);
        timesRow.addView(makeTimeChip(formatTime(out), "Out"));

        // Duration
        String dur = calcDuration(in, out);
        if (!dur.isEmpty()) {
            View sp2 = new View(this);
            sp2.setLayoutParams(new LinearLayout.LayoutParams(dp(12), 1));
            timesRow.addView(sp2);
            TextView tvDur = new TextView(this);
            tvDur.setText(dur);
            tvDur.setTextSize(11);
            tvDur.setTextColor(getResources().getColor(R.color.gray));
            timesRow.addView(tvDur);
        }

        info.addView(timesRow);

        // Status badge
        LinearLayout.LayoutParams badgeRowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        badgeRowLp.topMargin = dp(6);

        TextView statusBadge = new TextView(this);
        statusBadge.setLayoutParams(badgeRowLp);
        statusBadge.setTextSize(11);
        statusBadge.setTypeface(null, Typeface.BOLD);
        statusBadge.setPadding(dp(10), dp(3), dp(10), dp(3));
        statusBadge.setText(status.isEmpty() ? "—" : status);
        if (status.equalsIgnoreCase("PRESENT")) {
            statusBadge.setBackgroundResource(R.drawable.bg_badge_present);
            statusBadge.setTextColor(getResources().getColor(R.color.green_text));
        } else if (status.equalsIgnoreCase("LATE")) {
            statusBadge.setBackgroundResource(R.drawable.bg_badge_late);
            statusBadge.setTextColor(getResources().getColor(R.color.yellow_text));
        } else {
            statusBadge.setBackgroundResource(R.drawable.bg_badge_absent);
            statusBadge.setTextColor(getResources().getColor(R.color.red_text));
        }
        info.addView(statusBadge);

        card.addView(info);
        layoutHistory.addView(card);
    }

    private LinearLayout makeTimeChip(String time, String label) {
        LinearLayout chip = new LinearLayout(this);
        chip.setOrientation(LinearLayout.VERTICAL);

        TextView tvTime = new TextView(this);
        tvTime.setText(time);
        tvTime.setTextSize(15);
        tvTime.setTypeface(null, Typeface.BOLD);
        tvTime.setTextColor(getResources().getColor(R.color.black));

        TextView tvLabel = new TextView(this);
        tvLabel.setText(label);
        tvLabel.setTextSize(11);
        tvLabel.setTextColor(getResources().getColor(R.color.gray));

        chip.addView(tvTime);
        chip.addView(tvLabel);
        return chip;
    }

    // ── State helpers ─────────────────────────────────────────────────
    private void showLoading() {
        layoutHistory.removeAllViews();
        addCentreText("Loading…");
    }

    private void showEmpty() { addCentreText("No records found"); }

    private void showError(String msg) {
        layoutHistory.removeAllViews();
        addCentreText(msg);
    }

    private void addCentreText(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(getResources().getColor(R.color.gray));
        tv.setTextSize(15);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(0, dp(48), 0, dp(48));
        layoutHistory.addView(tv);
    }

    // ── Helpers ───────────────────────────────────────────────────────
    private String[] splitDate(String dateStr) {
        try {
            Date d = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(dateStr);
            return new String[]{
                    new SimpleDateFormat("dd",  Locale.getDefault()).format(d),
                    new SimpleDateFormat("MMM", Locale.getDefault()).format(d)
            };
        } catch (Exception e) { return new String[]{"--", "---"}; }
    }

    private String formatTime(String t) {
        if (t == null || t.isEmpty() || t.equals("null")) return "--:--";
        try {
            Date d = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).parse(t);
            return new SimpleDateFormat("hh:mm a", Locale.getDefault()).format(d);
        } catch (Exception e) { return t; }
    }

    private String calcDuration(String in, String out) {
        try {
            if (in == null || out == null || in.isEmpty() || out.equals("null") || out.isEmpty())
                return "";
            SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
            long diff = sdf.parse(out).getTime() - sdf.parse(in).getTime();
            if (diff <= 0) return "";
            long h = diff / 3_600_000;
            long m = (diff % 3_600_000) / 60_000;
            return h + "h " + m + "m";
        } catch (Exception e) { return ""; }
    }

    private int dp(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }
}