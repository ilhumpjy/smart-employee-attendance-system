package my.edu.uitm.smartattendanceapp;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class DashboardActivity extends AppCompatActivity {

    private TextView     txtStatPresent, txtStatLate, txtStatAbsent, txtStatRate;
    private TextView     txtTodayClockIn, txtTodayClockOut;
    private TextView     txtMsgStatus, btnSeeAll, btnViewReplies;   // ← field, not local
    private LinearLayout layoutRecords;
    private EditText     etMessage;
    private Button       btnSendMessage;
    private int          employeeId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dashboard);

        SharedPreferences prefs = getSharedPreferences("AttendanceApp", MODE_PRIVATE);
        employeeId = prefs.getInt("employee_id", -1);
        if (employeeId == -1) {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }

        bindViews();
        NavHelper.setup(this, NavHelper.TAB_DASHBOARD);

        btnSendMessage.setOnClickListener(v -> sendMessage());
        btnSeeAll.setOnClickListener(v -> startActivity(new Intent(this, HistoryActivity.class)));
        btnViewReplies.setOnClickListener(v -> startActivity(new Intent(this, MessageActivity.class)));
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadAttendanceData();
        loadMessageReplyCount();   // ← refresh badge every time screen is shown
    }

    private void bindViews() {
        txtStatPresent   = findViewById(R.id.txtStatPresent);
        txtStatLate      = findViewById(R.id.txtStatLate);
        txtStatAbsent    = findViewById(R.id.txtStatAbsent);
        txtStatRate      = findViewById(R.id.txtStatRate);
        txtTodayClockIn  = findViewById(R.id.txtTodayClockIn);
        txtTodayClockOut = findViewById(R.id.txtTodayClockOut);
        layoutRecords    = findViewById(R.id.layoutRecords);
        etMessage        = findViewById(R.id.etMessage);
        btnSendMessage   = findViewById(R.id.btnSendMessage);
        txtMsgStatus     = findViewById(R.id.txtMsgStatus);
        btnSeeAll        = findViewById(R.id.btnSeeAll);
        btnViewReplies   = findViewById(R.id.btnViewReplies);   // ← assign to field
    }

    // ── Attendance ────────────────────────────────────────────────────────────

    private void loadAttendanceData() {
        OkHttpClient client = new OkHttpClient();
        Request request = new Request.Builder()
                .url(Config.BASE_URL + "/employee/attendance?employee_id=" + employeeId)
                .get()
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {}

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String body = response.body().string();
                runOnUiThread(() -> {
                    try {
                        JSONObject json    = new JSONObject(body);
                        JSONArray  records = json.getJSONArray("records");
                        JSONObject today   = json.optJSONObject("today");
                        JSONObject stats   = json.getJSONObject("stats");

                        txtStatPresent.setText(String.valueOf(stats.getInt("present")));
                        txtStatLate.setText(String.valueOf(stats.getInt("late")));
                        txtStatAbsent.setText(String.valueOf(stats.getInt("absent")));

                        int present = stats.optInt("present", 0);
                        int late    = stats.optInt("late", 0);
                        int absent  = stats.optInt("absent", 0);
                        int total   = present + late + absent;
                        int rate    = total > 0 ? Math.round(present * 100f / total) : 0;
                        txtStatRate.setText(rate + "%");

                        if (today != null) {
                            String inT  = today.optString("clock_in_time",  "");
                            String outT = today.optString("clock_out_time", "");
                            txtTodayClockIn.setText(formatTime(inT));
                            txtTodayClockOut.setText(
                                    outT.isEmpty() || outT.equals("null") ? "--:--" : formatTime(outT));
                        }

                        layoutRecords.removeAllViews();
                        int count = Math.min(records.length(), 5);
                        for (int i = 0; i < count; i++) {
                            JSONObject r = records.getJSONObject(i);
                            addRecordRow(i,
                                    r.optString("attendance_date",   ""),
                                    r.optString("clock_in_time",     ""),
                                    r.optString("clock_out_time",    ""),
                                    r.optString("attendance_status", ""));
                        }
                    } catch (Exception ignored) {}
                });
            }
        });
    }

    private void addRecordRow(int idx, String date, String in, String out, String status) {
        if (idx > 0) {
            View div = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
            lp.setMarginStart(dp(16));
            div.setLayoutParams(lp);
            div.setBackgroundColor(getResources().getColor(R.color.separator));
            layoutRecords.addView(div);
        }

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(16), dp(16), dp(16));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT);
        infoLp.weight = 1;
        info.setLayoutParams(infoLp);

        TextView tvDate = new TextView(this);
        tvDate.setText(formatDate(date));
        tvDate.setTextSize(16);
        tvDate.setTypeface(null, Typeface.BOLD);
        tvDate.setTextColor(getResources().getColor(R.color.black));
        tvDate.setLetterSpacing(-0.02f);
        info.addView(tvDate);

        TextView tvTimes = new TextView(this);
        tvTimes.setText(formatTime(in) + " — " + formatTime(out));
        tvTimes.setTextSize(13);
        tvTimes.setTextColor(getResources().getColor(R.color.gray));
        info.addView(tvTimes);

        row.addView(info);

        if (!status.isEmpty()) {
            TextView badge = new TextView(this);
            badge.setText(status);
            badge.setTextSize(11);
            badge.setTypeface(null, Typeface.BOLD);
            badge.setPadding(dp(12), dp(4), dp(12), dp(4));

            if (status.equalsIgnoreCase("PRESENT")) {
                badge.setBackgroundResource(R.drawable.bg_badge_present);
                badge.setTextColor(getResources().getColor(R.color.green_text));
            } else if (status.equalsIgnoreCase("LATE")) {
                badge.setBackgroundResource(R.drawable.bg_badge_late);
                badge.setTextColor(getResources().getColor(R.color.yellow_text));
            } else {
                badge.setBackgroundResource(R.drawable.bg_badge_absent);
                badge.setTextColor(getResources().getColor(R.color.red_text));
            }
            row.addView(badge);
        }

        layoutRecords.addView(row);
    }

    // ── Messaging ─────────────────────────────────────────────────────────────

    private void sendMessage() {
        String message = etMessage.getText().toString().trim();
        if (message.isEmpty()) return;

        btnSendMessage.setEnabled(false);
        btnSendMessage.setText("Sending...");

        RequestBody body = new FormBody.Builder()
                .add("employee_id", String.valueOf(employeeId))
                .add("message_text", message)
                .build();

        Request request = new Request.Builder()
                .url(Config.BASE_URL + "/send_message")
                .post(body)
                .build();

        new OkHttpClient().newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                runOnUiThread(() -> {
                    btnSendMessage.setEnabled(true);
                    btnSendMessage.setText("Send Message");
                });
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                runOnUiThread(() -> {
                    btnSendMessage.setEnabled(true);
                    btnSendMessage.setText("Send Message");
                    etMessage.setText("");
                    txtMsgStatus.setVisibility(View.VISIBLE);
                    txtMsgStatus.setText("Message sent ✓");
                    txtMsgStatus.setTextColor(getResources().getColor(R.color.green_text));
                    loadMessageReplyCount();   // ← show "View All Messages" after first send
                });
            }
        });
    }

    private void loadMessageReplyCount() {
        Request request = new Request.Builder()
                .url(Config.BASE_URL + "/employee/messages?employee_id=" + employeeId)
                .get()
                .build();

        new OkHttpClient().newCall(request).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {}

            @Override public void onResponse(Call call, Response response) throws IOException {
                String body = response.body().string();
                runOnUiThread(() -> {
                    try {
                        JSONObject json     = new JSONObject(body);
                        JSONArray  messages = json.getJSONArray("messages");

                        int totalMessages = messages.length();
                        int totalReplies  = 0;
                        for (int i = 0; i < totalMessages; i++) {
                            JSONArray replies = messages.getJSONObject(i).optJSONArray("replies");
                            if (replies != null) totalReplies += replies.length();
                        }

                        if (totalMessages > 0) {
                            btnViewReplies.setVisibility(View.VISIBLE);
                            if (totalReplies > 0) {
                                btnViewReplies.setText(
                                        "View Replies (" + totalReplies + ")");
                            } else {
                                btnViewReplies.setText("View All Messages");
                            }
                        } else {
                            btnViewReplies.setVisibility(View.GONE);
                        }
                    } catch (Exception ignored) {}
                });
            }
        });
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private int dp(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    private String formatDate(String d) {
        try {
            Date dt = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(d);
            return new SimpleDateFormat("EEE, d MMM", Locale.getDefault()).format(dt);
        } catch (Exception e) { return d; }
    }

    private String formatTime(String t) {
        if (t == null || t.isEmpty() || t.equals("null") || t.equals("--:--")) return "--:--";
        try {
            Date d = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).parse(t);
            return new SimpleDateFormat("hh:mm a", Locale.getDefault()).format(d);
        } catch (Exception e) { return t; }
    }
}