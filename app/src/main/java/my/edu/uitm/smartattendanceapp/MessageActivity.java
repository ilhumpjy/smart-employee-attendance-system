package my.edu.uitm.smartattendanceapp;

import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
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

public class MessageActivity extends AppCompatActivity {

    private LinearLayout layoutMessages;
    private ScrollView   scrollMessages;
    private TextView     txtEmpty,txtMsgStatus;
    private int          employeeId;
    private ImageButton btnSendMessage;
    private EditText etMessage;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_message);

        SharedPreferences prefs = getSharedPreferences("AttendanceApp", MODE_PRIVATE);
        employeeId = prefs.getInt("employee_id", -1);
        if (employeeId == -1) { finish(); return; }

        layoutMessages = findViewById(R.id.layoutMessages);
        scrollMessages = findViewById(R.id.scrollMessages);
        txtEmpty       = findViewById(R.id.txtEmpty);

        etMessage = findViewById(R.id.etMessage);
        btnSendMessage = findViewById(R.id.btnSendMessage);
        txtMsgStatus = findViewById(R.id.txtMsgStatus);

        btnSendMessage.setOnClickListener(v -> sendMessage());

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        loadMessages();
    }

    // ── Messaging ─────────────────────────────────────────────────────────────

    private void sendMessage() {
        String message = etMessage.getText().toString().trim();
        if (message.isEmpty()) return;

        btnSendMessage.setEnabled(false);

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
                });
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                runOnUiThread(() -> {
                    btnSendMessage.setEnabled(true);
                    etMessage.setText("");
                    loadMessages();
                    txtMsgStatus.setVisibility(View.VISIBLE);
                    txtMsgStatus.setText("Message sent ✓");
                    txtMsgStatus.setTextColor(getResources().getColor(R.color.green_text));
                });
            }
        });
    }

    private void loadMessages() {
        Request request = new Request.Builder()
                .url(Config.BASE_URL + "/employee/messages?employee_id=" + employeeId)
                .get()
                .build();

        new OkHttpClient().newCall(request).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                runOnUiThread(() -> showEmpty("Could not load messages."));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {

                String body = response.body().string();

                android.util.Log.d("MESSAGE_JSON", body);

                runOnUiThread(() -> {
                    try {

                        JSONObject json = new JSONObject(body);
                        JSONArray messages = json.getJSONArray("messages");

                        android.util.Log.d(
                                "MESSAGE_COUNT",
                                String.valueOf(messages.length())
                        );

                        layoutMessages.removeAllViews();

                        for (int i = 0; i < messages.length(); i++) {

                            JSONObject msg = messages.getJSONObject(i);

                            addMessageThread(msg, i > 0);
                        }

                        scrollMessages.post(() ->
                                scrollMessages.fullScroll(View.FOCUS_DOWN));

                    } catch (Exception e) {
                        android.util.Log.e(
                                "MESSAGE_ERROR",
                                "Failed parsing",
                                e
                        );
                    }
                });
            }
        });
    }

    private void addMessageThread(JSONObject msg, boolean addDivider) {

        try {

            JSONArray replies = msg.optJSONArray("replies");

            android.util.Log.d(
                    "REPLY_DEBUG",
                    "msg=" + msg.optString("message_text") +
                            " replies=" +
                            (replies == null ? "NULL" : replies.length())
            );

            // Divider between message threads
            if (addDivider) {
                View div = new View(this);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(1)
                );
                lp.setMargins(0, dp(8), 0, dp(8));
                div.setLayoutParams(lp);
                div.setBackgroundColor(
                        ContextCompat.getColor(this, R.color.separator)
                );
                layoutMessages.addView(div);
            }

            // Employee message
            String msgText = msg.optString("message_text", "");
            String sentAt  = msg.optString("sent_at", "");

            addBubble(
                    msgText,
                    formatDateTime(sentAt),
                    true,
                    null
            );

            // Admin replies
            if (replies != null) {

                for (int i = 0; i < replies.length(); i++) {

                    JSONObject reply = replies.getJSONObject(i);

                    android.util.Log.d(
                            "REPLY_TEXT",
                            reply.optString("reply_text")
                    );

                    addBubble(
                            reply.optString("reply_text", ""),
                            formatDateTime(
                                    reply.optString("replied_at", "")
                            ),
                            false,
                            reply.optString(
                                    "admin_name",
                                    "Admin"
                            )
                    );
                }
            }

        } catch (Exception e) {

            android.util.Log.e(
                    "MESSAGE_THREAD",
                    "Failed rendering thread",
                    e
            );
        }
    }

    private void addBubble(String text,
                           String timestamp,
                           boolean fromEmployee,
                           String senderName) {

        // Wrapper
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);

        LinearLayout.LayoutParams wrapperParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                );

        wrapperParams.setMargins(0, dp(6), 0, 0);
        wrapper.setLayoutParams(wrapperParams);

        // Admin name
        if (!fromEmployee &&
                senderName != null &&
                !senderName.isEmpty()) {

            TextView tvSender = new TextView(this);
            tvSender.setText(senderName);
            tvSender.setTextSize(11);
            tvSender.setTypeface(null, Typeface.BOLD);
            tvSender.setTextColor(
                    ContextCompat.getColor(this, R.color.primary)
            );

            LinearLayout.LayoutParams senderParams =
                    new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                    );

            senderParams.setMargins(dp(4), 0, 0, dp(2));
            tvSender.setLayoutParams(senderParams);

            wrapper.addView(tvSender);
        }

        // Row
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        row.setLayoutParams(
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                )
        );

        row.setGravity(
                fromEmployee ? Gravity.END : Gravity.START
        );

        // Bubble max width = 70% screen
        int maxWidth = (int) (
                getResources().getDisplayMetrics().widthPixels * 0.7f
        );

        // Bubble
        LinearLayout bubble = new LinearLayout(this);
        bubble.setOrientation(LinearLayout.VERTICAL);

        bubble.setPadding(
                dp(14),
                dp(10),
                dp(14),
                dp(10)
        );

        LinearLayout.LayoutParams bubbleParams =
                new LinearLayout.LayoutParams(
                        maxWidth,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                );

        bubble.setLayoutParams(bubbleParams);

        try {
            bubble.setBackgroundResource(
                    fromEmployee
                            ? R.drawable.bg_bubble_employee
                            : R.drawable.bg_bubble_admin
            );
        } catch (Exception e) {
            bubble.setBackgroundColor(
                    fromEmployee
                            ? ContextCompat.getColor(this, R.color.primary)
                            : ContextCompat.getColor(this, R.color.white)
            );
        }

        // Message text
        TextView tvText = new TextView(this);

        tvText.setText(text);
        tvText.setTextSize(14);

        tvText.setMaxWidth(maxWidth - dp(28));

        tvText.setSingleLine(false);
        tvText.setHorizontallyScrolling(false);

        tvText.setTextColor(
                fromEmployee
                        ? ContextCompat.getColor(this, R.color.white)
                        : ContextCompat.getColor(this, R.color.black)
        );

        bubble.addView(tvText);

        // Timestamp
        TextView tvTime = new TextView(this);

        tvTime.setText(timestamp);
        tvTime.setTextSize(10);

        LinearLayout.LayoutParams timeParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                );

        timeParams.setMargins(0, dp(4), 0, 0);
        timeParams.gravity =
                fromEmployee ? Gravity.END : Gravity.START;

        tvTime.setLayoutParams(timeParams);

        tvTime.setTextColor(
                fromEmployee
                        ? 0xB3FFFFFF
                        : ContextCompat.getColor(
                        this,
                        R.color.text_secondary
                )
        );

        bubble.addView(tvTime);

        row.addView(bubble);
        wrapper.addView(row);

        layoutMessages.addView(wrapper);
    }

    private void showEmpty(String msg) {
        layoutMessages.setVisibility(View.GONE);
        txtEmpty.setVisibility(View.VISIBLE);
        txtEmpty.setText(msg);
    }

    private int dp(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    private String formatDateTime(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        try {
            Date d = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).parse(raw);
            return new SimpleDateFormat("d MMM, hh:mm a", Locale.getDefault()).format(d);
        } catch (Exception e) {
            return raw;
        }
    }
}