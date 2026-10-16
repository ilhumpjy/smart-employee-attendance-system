package my.edu.uitm.smartattendanceapp;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import androidx.appcompat.app.AppCompatActivity;

public class SplashActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            SharedPreferences prefs = getSharedPreferences("AttendanceApp", MODE_PRIVATE);
            Intent next;
            if (prefs.getInt("employee_id", -1) != -1) {
                next = new Intent(this, MainActivity.class);
            } else {
                next = new Intent(this, LoginActivity.class);
            }
            startActivity(next);
            finish();
        }, 1800);
    }
}