package my.edu.uitm.smartattendanceapp;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.common.util.concurrent.ListenableFuture;
import org.json.JSONObject;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class CameraActivity extends AppCompatActivity {

    private static final String CLOCK_IN_URL  = Config.BASE_URL + "/clock_in";
    private static final String CLOCK_OUT_URL = Config.BASE_URL + "/clock_out";

    private PreviewView   previewView;
    private TextView      txtCamStatus;
    private LinearLayout  btnClockIn, btnClockOut;
    private View          btnBack;

    private ImageCapture imageCapture;
    private FusedLocationProviderClient fusedLocationClient;

    public static final String EXTRA_RETURN_TO = "return_to";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera);

        previewView   = findViewById(R.id.previewView);
        txtCamStatus  = findViewById(R.id.txtCamStatus);
        btnClockIn    = findViewById(R.id.btnClockIn);
        btnClockOut   = findViewById(R.id.btnClockOut);
        btnBack       = findViewById(R.id.btnBack);

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        requestPermissions();
        startCamera();

        btnBack.setOnClickListener(v -> finish());
        btnClockIn.setOnClickListener(v  -> checkLocationThenCapture(CLOCK_IN_URL));
        btnClockOut.setOnClickListener(v -> checkLocationThenCapture(CLOCK_OUT_URL));
    }

    private void requestPermissions() {
        ActivityCompat.requestPermissions(this,
                new String[]{
                        Manifest.permission.CAMERA,
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.INTERNET
                }, 100);
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future =
                ProcessCameraProvider.getInstance(this);

        future.addListener(() -> {
            try {
                ProcessCameraProvider provider = future.get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                imageCapture = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build();

                provider.unbindAll();
                provider.bindToLifecycle(
                        this,
                        CameraSelector.DEFAULT_FRONT_CAMERA,
                        preview,
                        imageCapture);

            } catch (Exception e) {
                showStatus("Camera error: " + e.getMessage(), "error");
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void checkLocationThenCapture(String url) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            showStatus("Location permission required", "error");
            return;
        }

        setButtonsEnabled(false);
        showStatus("Getting your location…", "info");

        fusedLocationClient.getLastLocation()
                .addOnSuccessListener(location -> {
                    if (location == null) {
                        showStatus("Unable to get GPS location. Try again.", "error");
                        setButtonsEnabled(true);
                        return;
                    }
                    showStatus("Location found. Capturing face…", "info");
                    captureImageAndUpload(url, location.getLatitude(), location.getLongitude());
                })
                .addOnFailureListener(e -> {
                    showStatus("Location error: " + e.getMessage(), "error");
                    setButtonsEnabled(true);
                });
    }

    private void captureImageAndUpload(String url, double lat, double lon) {
        if (imageCapture == null) {
            showStatus("Camera not ready", "error");
            setButtonsEnabled(true);
            return;
        }

        File photoFile = new File(getExternalFilesDir(null), "face_capture.jpg");
        ImageCapture.OutputFileOptions options =
                new ImageCapture.OutputFileOptions.Builder(photoFile).build();

        imageCapture.takePicture(options,
                ContextCompat.getMainExecutor(this),
                new ImageCapture.OnImageSavedCallback() {
                    @Override
                    public void onImageSaved(@NonNull ImageCapture.OutputFileResults output) {
                        showStatus("Verifying identity…", "info");
                        uploadImage(photoFile, url, lat, lon);
                    }

                    @Override
                    public void onError(@NonNull ImageCaptureException e) {
                        showStatus("Capture failed: " + e.getMessage(), "error");
                        setButtonsEnabled(true);
                    }
                });
    }

    private void uploadImage(File imageFile, String url, double lat, double lon) {
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(30,    java.util.concurrent.TimeUnit.SECONDS)
                .build();

        SharedPreferences prefs = getSharedPreferences("AttendanceApp", MODE_PRIVATE);
        int employeeId = prefs.getInt("employee_id", -1);

        RequestBody requestBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("employee_id", String.valueOf(employeeId))
                .addFormDataPart("latitude",    String.valueOf(lat))
                .addFormDataPart("longitude",   String.valueOf(lon))
                .addFormDataPart("image", imageFile.getName(),
                        RequestBody.create(imageFile, MediaType.parse("image/jpeg")))
                .build();

        Request request = new Request.Builder()
                .url(url)
                .post(requestBody)
                .build();

        client.newCall(request).enqueue(new Callback() {

            @Override
            public void onFailure(Call call, IOException e) {
                runOnUiThread(() -> {
                    showStatus("Upload failed: " + e.getMessage(), "error");
                    setButtonsEnabled(true);
                });
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String body = response.body().string();
                runOnUiThread(() -> {
                    setButtonsEnabled(true);
                    try {
                        JSONObject json    = new JSONObject(body);
                        String     status  = json.getString("status");
                        String     message = json.getString("message");

                        if (status.equals("success")) {
                            showStatus(message, "success");
                            txtCamStatus.postDelayed(() -> finish(), 1400);
                        } else {
                            showStatus(message, "error");
                        }
                    } catch (Exception e) {
                        showStatus("Invalid server response", "error");
                    }
                });
            }
        });
    }

    private void showStatus(String message, String type) {
        txtCamStatus.setVisibility(View.VISIBLE);
        txtCamStatus.setText(message);
        switch (type) {
            case "success":
                txtCamStatus.setBackgroundResource(R.drawable.bg_badge_present);
                txtCamStatus.setTextColor(getResources().getColor(R.color.green_text));
                break;
            case "error":
                txtCamStatus.setBackgroundResource(R.drawable.bg_badge_absent);
                txtCamStatus.setTextColor(getResources().getColor(R.color.red_text));
                break;
            default: // info
                txtCamStatus.setBackgroundResource(R.drawable.bg_ios_card);
                txtCamStatus.setTextColor(getResources().getColor(R.color.black));
                break;
        }
    }

    private void setButtonsEnabled(boolean enabled) {
        btnClockIn.setEnabled(enabled);
        btnClockOut.setEnabled(enabled);
        btnClockIn.setAlpha(enabled  ? 1.0f : 0.5f);
        btnClockOut.setAlpha(enabled ? 1.0f : 0.5f);
    }

    @Override
    public void onBackPressed() {
        finish();
    }
}