package my.edu.uitm.smartattendanceapp;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class ProfileActivity extends AppCompatActivity {

    // ── Views ──────────────────────────────────────────────────────────────
    private TextView     txtFullName, txtInitial;
    private TextView     txtProfileName, txtProfileDept, txtEmail, txtDeptPosition, txtPosition;
    private EditText     etName, etDepartment, etPosition;
    private Button       btnSave;
    private LinearLayout btnLogout, btnChangePassword;

    // Profile photo
    private FrameLayout  frameProfilePhoto;
    private FrameLayout  frameInitials;
    private de.hdodenhof.circleimageview.CircleImageView imgProfilePhoto;

    private static final String BASE_URL = "https://gallows-bloated-triceps.ngrok-free.dev";

    private final ExecutorService executor    = Executors.newSingleThreadExecutor();
    private final Handler         mainHandler = new Handler(Looper.getMainLooper());

    // Camera temp file URI
    private Uri          cameraImageUri;

    // ── Activity result launchers ──────────────────────────────────────────
    private final ActivityResultLauncher<Intent> galleryLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Uri selectedUri = result.getData().getData();
                    if (selectedUri != null) {
                        applyPhoto(selectedUri);
                    }
                }
            });

    private final ActivityResultLauncher<Intent> cameraLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && cameraImageUri != null) {
                    applyPhoto(cameraImageUri);
                }
            });

    // ── Lifecycle ──────────────────────────────────────────────────────────
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_profile);

        bindViews();
        populateFromPrefs();
        loadSavedPhoto();
        setupListeners();
        NavHelper.setup(this, NavHelper.TAB_PROFILE);
    }

    // ── Bind ───────────────────────────────────────────────────────────────
    private void bindViews() {
        txtFullName       = findViewById(R.id.txtFullName);
        txtInitial        = findViewById(R.id.txtInitial);
        txtProfileName    = findViewById(R.id.txtProfileName);
        txtProfileDept    = findViewById(R.id.txtProfileDept);
        txtEmail          = findViewById(R.id.txtEmail);
        txtDeptPosition   = findViewById(R.id.txtDeptPosition);
        txtPosition       = findViewById(R.id.txtPosition);
        etName            = findViewById(R.id.etName);
        etDepartment      = findViewById(R.id.etDepartment);
        etPosition        = findViewById(R.id.etPosition);
        btnSave           = findViewById(R.id.btnSave);
        btnLogout         = findViewById(R.id.btnLogout);
        btnChangePassword = findViewById(R.id.btnChangePassword);
        frameProfilePhoto = findViewById(R.id.frameProfilePhoto);
        frameInitials     = findViewById(R.id.frameInitials);
        imgProfilePhoto = (de.hdodenhof.circleimageview.CircleImageView) findViewById(R.id.imgProfilePhoto);
    }

    // ── Populate ───────────────────────────────────────────────────────────
    private void populateFromPrefs() {
        SharedPreferences prefs = getSharedPreferences("AttendanceApp", MODE_PRIVATE);
        String name     = prefs.getString("employee_name",  "Employee");
        String dept     = prefs.getString("department",     "Department");
        String position = prefs.getString("position",       "Position");
        String email    = prefs.getString("employee_email", "employee@uitm.edu.my");

        txtFullName.setText(name);
        txtInitial.setText(name.isEmpty() ? "E" :
                String.valueOf(name.charAt(0)).toUpperCase());
        txtProfileName.setText(name);
        txtProfileDept.setText(dept + " · " + position);
        txtEmail.setText(email);
        if (txtDeptPosition != null) txtDeptPosition.setText(dept);
        if (txtPosition     != null) txtPosition.setText(position);

        etName.setText(name);
        etDepartment.setText(dept);
        etPosition.setText(position);
    }

    private void loadSavedPhoto() {
        SharedPreferences prefs = getSharedPreferences("AttendanceApp", MODE_PRIVATE);
        int empId = prefs.getInt("employee_id", -1);
        if (empId == -1) return;

        executor.execute(() -> {
            try {
                OkHttpClient client = new OkHttpClient();
                Request request = new Request.Builder()
                        .url(BASE_URL + "/api/profile/photo/" + empId)
                        .build();

                Response response = client.newCall(request).execute();
                if (response.isSuccessful() && response.body() != null) {
                    byte[] bytes = response.body().bytes();
                    Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                    if (bmp != null) {
                        mainHandler.post(() -> showPhoto(bmp));
                    }
                }
            } catch (IOException e) {

            }
        });
    }

    // ── Listeners ──────────────────────────────────────────────────────────
    private void setupListeners() {
        btnSave.setOnClickListener(v -> saveProfile());
        btnLogout.setOnClickListener(v -> logout());
        btnChangePassword.setOnClickListener(v -> {
            Intent intent = new Intent(this, ChangePasswordActivity.class);
            intent.putExtra(ChangePasswordActivity.EXTRA_ALLOW_BACK, true);
            startActivity(intent);
        });
        frameProfilePhoto.setOnClickListener(v -> showPhotoPickerDialog());
    }

    // ── Photo picker dialog ────────────────────────────────────────────────
    private void showPhotoPickerDialog() {
        String[] options = {"Choose from Gallery", "Take Photo", "Remove Photo"};
        new AlertDialog.Builder(this)
                .setTitle("Profile Photo")
                .setItems(options, (dialog, which) -> {
                    if (which == 0) openGallery();
                    else if (which == 1) openCamera();
                    else removePhoto();
                })
                .show();
    }

    private void openGallery() {
        Intent intent = new Intent(Intent.ACTION_PICK,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        intent.setType("image/*");
        galleryLauncher.launch(intent);
    }

    private void openCamera() {
        try {
            File imageFile = File.createTempFile("profile_", ".jpg", getCacheDir());
            cameraImageUri = FileProvider.getUriForFile(
                    this,
                    getPackageName() + ".fileprovider",
                    imageFile);
            Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            intent.putExtra(MediaStore.EXTRA_OUTPUT, cameraImageUri);
            cameraLauncher.launch(intent);
        } catch (IOException e) {
            Toast.makeText(this, "Unable to open camera.", Toast.LENGTH_SHORT).show();
        }
    }

    private void removePhoto() {
        getSharedPreferences("PhotoPrefs", MODE_PRIVATE).edit()
                .remove("profile_photo_b64").apply();
        imgProfilePhoto.setVisibility(View.GONE);
        frameInitials.setVisibility(View.VISIBLE);
        Toast.makeText(this, "Photo removed.", Toast.LENGTH_SHORT).show();
    }

    // ── Apply selected/captured photo ──────────────────────────────────────
    private void applyPhoto(Uri uri) {
        executor.execute(() -> {
            try (InputStream is = getContentResolver().openInputStream(uri)) {
                Bitmap scaled = scaleBitmap(BitmapFactory.decodeStream(is), 512);
                mainHandler.post(() -> showPhoto(scaled));
                savePhotoToPrefs(scaled);
                uploadPhotoToServer(scaled);
            } catch (IOException e) {
                mainHandler.post(() ->
                        Toast.makeText(this, "Could not load image.", Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void uploadPhotoToServer(Bitmap bmp) {
        SharedPreferences prefs = getSharedPreferences("AttendanceApp", MODE_PRIVATE);

        int empIdInt = prefs.getInt("employee_id", -1);
        if (empIdInt == -1) {
            mainHandler.post(() -> Toast.makeText(this,
                    "employee_id not found — saved locally only.", Toast.LENGTH_SHORT).show());
            return;
        }
        String employeeId = String.valueOf(empIdInt);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, baos);

        RequestBody body = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("employee_id", employeeId)
                .addFormDataPart("photo", "profile.jpg",
                        RequestBody.create(baos.toByteArray(), MediaType.parse("image/jpeg")))
                .build();

        Request req = new Request.Builder()
                .url(BASE_URL + "/api/profile/photo")
                .post(body)
                .build();

        try (Response res = new OkHttpClient().newCall(req).execute()) {
            boolean ok = res.isSuccessful();
            mainHandler.post(() -> Toast.makeText(this,
                    ok ? "Photo synced to server ✓" : "Saved locally. Server sync failed.",
                    Toast.LENGTH_SHORT).show());
        } catch (IOException e) {
            mainHandler.post(() -> Toast.makeText(this,
                    "No connection. Photo saved locally.", Toast.LENGTH_SHORT).show());
        }
    }

    private Bitmap scaleBitmap(Bitmap src, int maxSide) {
        int w = src.getWidth(), h = src.getHeight();
        if (w <= maxSide && h <= maxSide) return src;
        float ratio = (float) maxSide / Math.max(w, h);
        return Bitmap.createScaledBitmap(src, (int)(w * ratio), (int)(h * ratio), true);
    }

    private void showPhoto(Bitmap bmp) {
        imgProfilePhoto.setImageBitmap(bmp);
        imgProfilePhoto.setVisibility(View.VISIBLE);
        frameInitials.setVisibility(View.GONE);
    }

    private void savePhotoToPrefs(Bitmap bmp) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.JPEG, 80, baos);
        String b64 = Base64.encodeToString(baos.toByteArray(), Base64.DEFAULT);

        getSharedPreferences("PhotoPrefs", MODE_PRIVATE).edit()
                .putString("profile_photo_b64", b64).apply();

        mainHandler.post(() -> Toast.makeText(this, "Photo updated.", Toast.LENGTH_SHORT).show());
    }

    // ── Save profile text ──────────────────────────────────────────────────
    private void saveProfile() {
        String updatedName     = etName.getText().toString().trim();
        String updatedDept     = etDepartment.getText().toString().trim();
        String updatedPosition = etPosition.getText().toString().trim();

        getSharedPreferences("AttendanceApp", MODE_PRIVATE).edit()
                .putString("employee_name", updatedName)
                .putString("department",    updatedDept)
                .putString("position",      updatedPosition)
                .apply();

        txtFullName.setText(updatedName);
        txtInitial.setText(updatedName.isEmpty() ? "E" :
                String.valueOf(updatedName.charAt(0)).toUpperCase());
        txtProfileName.setText(updatedName);
        txtProfileDept.setText(updatedDept + " · " + updatedPosition);
        if (txtDeptPosition != null) txtDeptPosition.setText(updatedDept);
        if (txtPosition     != null) txtPosition.setText(updatedPosition);

        Toast.makeText(this, getString(R.string.profile_updated), Toast.LENGTH_SHORT).show();
    }

    // ── Logout ─────────────────────────────────────────────────────────────
    private void logout() {
        getSharedPreferences("AttendanceApp", MODE_PRIVATE).edit().clear().apply();
        startActivity(new Intent(this, LoginActivity.class));
        finishAffinity();
    }
}