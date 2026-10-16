package my.edu.uitm.smartattendanceapp;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import org.json.JSONObject;
import java.io.IOException;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class ChangePasswordActivity extends AppCompatActivity {

    public static final String EXTRA_ALLOW_BACK = "allow_back";

    private EditText etNewPassword, etConfirmPassword;
    private Button   btnChangePassword;
    private TextView txtChangeStatus;
    private View     btnBack;
    private boolean  allowBack;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_change_password);

        allowBack = getIntent().getBooleanExtra(EXTRA_ALLOW_BACK, false);

        etNewPassword     = findViewById(R.id.etNewPassword);
        etConfirmPassword = findViewById(R.id.etConfirmPassword);
        btnChangePassword = findViewById(R.id.btnUpdate);
        txtChangeStatus   = findViewById(R.id.txtStatus);
        btnBack           = findViewById(R.id.btnBack);

        if (allowBack) {
            btnBack.setOnClickListener(v -> finish());
        } else {
            btnBack.setVisibility(View.GONE);
        }

        btnChangePassword.setOnClickListener(v -> attemptChangePassword());
    }

    private void attemptChangePassword() {
        String newPass     = etNewPassword.getText().toString().trim();
        String confirmPass = etConfirmPassword.getText().toString().trim();

        if (newPass.isEmpty() || confirmPass.isEmpty()) {
            showStatus("Please fill in both fields", false);
            return;
        }
        if (newPass.length() < 6) {
            showStatus("Password must be at least 6 characters", false);
            return;
        }
        if (!newPass.equals(confirmPass)) {
            showStatus("Passwords do not match", false);
            return;
        }

        SharedPreferences prefs    = getSharedPreferences("AttendanceApp", MODE_PRIVATE);
        int               empId    = prefs.getInt("employee_id", -1);

        btnChangePassword.setEnabled(false);
        btnChangePassword.setText("Updating…");

        RequestBody body = new FormBody.Builder()
                .add("employee_id",  String.valueOf(empId))
                .add("new_password", newPass)
                .build();

        Request request = new Request.Builder()
                .url(Config.BASE_URL + "/employee/change-password")
                .post(body)
                .build();

        new OkHttpClient().newCall(request).enqueue(new Callback() {

            @Override
            public void onFailure(Call call, IOException e) {
                runOnUiThread(() -> {
                    btnChangePassword.setEnabled(true);
                    btnChangePassword.setText("Update Password");
                    showStatus("Connection failed", false);
                });
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String responseBody = response.body().string();
                runOnUiThread(() -> {
                    btnChangePassword.setEnabled(true);
                    btnChangePassword.setText("Update Password");
                    try {
                        JSONObject json = new JSONObject(responseBody);
                        if (json.getString("status").equals("success")) {
                            showStatus("Password updated! Redirecting…", true);
                            btnChangePassword.postDelayed(() -> {
                                startActivity(new Intent(ChangePasswordActivity.this,
                                        MainActivity.class));
                                finish();
                            }, 1200);
                        } else {
                            showStatus(json.getString("message"), false);
                        }
                    } catch (Exception e) {
                        showStatus("Unexpected response", false);
                    }
                });
            }
        });
    }

    private void showStatus(String message, boolean isSuccess) {
        txtChangeStatus.setVisibility(View.VISIBLE);
        txtChangeStatus.setText(message);
        txtChangeStatus.setBackgroundResource(
                isSuccess ? R.drawable.bg_status_card_success : R.drawable.bg_status_card_danger);
        txtChangeStatus.setTextColor(getResources().getColor(
                isSuccess ? R.color.green_text : R.color.red_text));
    }

    /** Prevent going back on mandatory first-login password change. */
    @Override
    public void onBackPressed() {
        if (allowBack) {
            super.onBackPressed();
        }
    }
}