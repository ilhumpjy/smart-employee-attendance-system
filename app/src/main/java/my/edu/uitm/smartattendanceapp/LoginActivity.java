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

public class LoginActivity extends AppCompatActivity {

    private EditText  etEmail, etPassword;
    private Button   btnLogin;
    private TextView txtLoginStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Skip login if already authenticated
        SharedPreferences prefs = getSharedPreferences("AttendanceApp", MODE_PRIVATE);
        if (prefs.getInt("employee_id", -1) != -1) {
            startActivity(new Intent(this, MainActivity.class));
            finish();
            return;
        }

        setContentView(R.layout.activity_login);

        etEmail        = findViewById(R.id.etEmail);
        etPassword     = findViewById(R.id.etPassword);
        btnLogin       = findViewById(R.id.btnLogin);
        txtLoginStatus = findViewById(R.id.txtLoginStatus);

        btnLogin.setOnClickListener(v -> attemptLogin());
    }

    private void attemptLogin() {
        String email    = etEmail.getText().toString().trim();
        String password = etPassword.getText().toString().trim();

        if (email.isEmpty() || password.isEmpty()) {
            showStatus("Please fill in all fields", false);
            return;
        }

        btnLogin.setEnabled(false);
        btnLogin.setText("Signing in…");
        showStatus("", false);

        OkHttpClient client = new OkHttpClient();

        RequestBody body = new FormBody.Builder()
                .add("email",    email)
                .add("password", password)
                .build();

        Request request = new Request.Builder()
                .url(Config.BASE_URL + "/employee/login")
                .post(body)
                .build();

        client.newCall(request).enqueue(new Callback() {

            @Override
            public void onFailure(Call call, IOException e) {
                runOnUiThread(() -> {
                    btnLogin.setEnabled(true);
                    btnLogin.setText(getString(R.string.sign_in));
                    showStatus("Connection failed. Check server IP.", false);
                });
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String responseBody = response.body().string();
                runOnUiThread(() -> {
                    btnLogin.setEnabled(true);
                    btnLogin.setText(getString(R.string.sign_in));
                    try {
                        JSONObject json   = new JSONObject(responseBody);
                        String     status = json.getString("status");

                        if (status.equals("success")) {
                            SharedPreferences.Editor editor =
                                    getSharedPreferences("AttendanceApp", MODE_PRIVATE).edit();
                            editor.putInt   ("employee_id",      json.getInt("employee_id"));
                            editor.putString("employee_name",    json.getString("employee_name"));
                            editor.putString("employee_email",   json.getString("employee_email"));
                            editor.putString("department",       json.getString("department"));
                            editor.putString("position",         json.getString("position"));
                            editor.apply();

                            boolean mustChange = json.getBoolean("must_change_password");
                            if (mustChange) {
                                startActivity(new Intent(LoginActivity.this, ChangePasswordActivity.class));
                            } else {
                                startActivity(new Intent(LoginActivity.this, MainActivity.class));
                            }
                            finish();
                        } else {
                            showStatus(json.getString("message"), false);
                        }
                    } catch (Exception e) {
                        showStatus("Unexpected server response", false);
                    }
                });
            }
        });
    }

    private void showStatus(String message, boolean isSuccess) {
        if (message.isEmpty()) {
            txtLoginStatus.setVisibility(View.GONE);
            return;
        }
        txtLoginStatus.setVisibility(View.VISIBLE);
        txtLoginStatus.setText(message);
        txtLoginStatus.setBackgroundResource(
                isSuccess ? R.drawable.bg_status_card_success : R.drawable.bg_status_card_danger);
        txtLoginStatus.setTextColor(getResources().getColor(
                isSuccess ? R.color.green_text : R.color.red_text));
    }
}