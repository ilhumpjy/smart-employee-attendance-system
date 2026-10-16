package my.edu.uitm.smartattendanceapp;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import org.json.JSONArray;
import org.json.JSONObject;
import org.osmdroid.config.Configuration;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.Polygon;
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider;
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class MainActivity extends AppCompatActivity {

    private TextView     txtGreeting, txtEmployeeName;
    private TextView     txtBannerDate, txtTodayClockIn, txtTodayClockOut;
    private TextView     btnSeeAll;                         // <-- TextView not View
    private LinearLayout weekStrip, layoutHomeRecords;
    private LinearLayout btnQuickClockIn, btnQuickClockOut;
    private ImageView imgProfilePhoto;

    // Live location map
    private MapView              mapEmployeeLocation;
    private View                 progressMapLoading;
    private TextView             txtLocationStatus, txtCurrentCoords, txtMapHint, txtInitial;
    private FrameLayout          frameInitials;
    private MyLocationNewOverlay myLocationOverlay;
    private static final int     REQ_LOCATION_PERMISSION = 1001;

    private final Handler clockHandler = new Handler(Looper.getMainLooper());
    private Runnable      clockRunnable;
    private int           employeeId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        SharedPreferences prefs = getSharedPreferences("AttendanceApp", MODE_PRIVATE);
        employeeId = prefs.getInt("employee_id", -1);
        if (employeeId == -1) {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }

        // osmdroid must be configured BEFORE the layout (and its MapView) is inflated
        Configuration.getInstance().load(getApplicationContext(),
                getSharedPreferences("osmdroid_prefs", MODE_PRIVATE));
        Configuration.getInstance().setUserAgentValue(getPackageName());

        setContentView(R.layout.activity_main);

        bindViews();
        populateHeader(prefs);
        ProfilePhotohelper.load(
                this,
                imgProfilePhoto,
                frameInitials,
                txtInitial
        );
        buildWeekStrip();
        startClock();
        setupQuickActions();
        initLocationMap();
        NavHelper.setup(this, NavHelper.TAB_HOME);
    }

    @Override
    protected void onResume() {
        super.onResume();

        ProfilePhotohelper.load(
                this,
                imgProfilePhoto,
                frameInitials,
                txtInitial
        );

        // Reload records every time screen becomes visible (e.g. returning from CameraActivity)
        loadRecentRecords();

        if (mapEmployeeLocation != null) mapEmployeeLocation.onResume();
        if (myLocationOverlay != null && hasLocationPermission()) {
            myLocationOverlay.enableMyLocation();
            myLocationOverlay.enableFollowLocation();
        }
        checkConnectivityAndWarn();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mapEmployeeLocation != null) mapEmployeeLocation.onPause();
        if (myLocationOverlay != null) myLocationOverlay.disableMyLocation();
    }

    // ── Bind ──────────────────────────────────────────────────────────
    private void bindViews() {
        txtGreeting       = findViewById(R.id.txtGreeting);
        txtEmployeeName   = findViewById(R.id.txtEmployeeName);
        imgProfilePhoto   = findViewById(R.id.imgProfilePhoto);
        frameInitials     = findViewById(R.id.frameInitials);
        txtInitial        = findViewById(R.id.txtInitial);
        txtBannerDate     = findViewById(R.id.txtBannerDate);
        txtTodayClockIn   = findViewById(R.id.txtTodayClockIn);
        txtTodayClockOut  = findViewById(R.id.txtTodayClockOut);
        weekStrip         = findViewById(R.id.weekStrip);
        layoutHomeRecords = findViewById(R.id.layoutHomeRecords);
        btnSeeAll         = findViewById(R.id.btnSeeAll);
        btnQuickClockIn   = findViewById(R.id.btnQuickClockIn);
        btnQuickClockOut  = findViewById(R.id.btnQuickClockOut);

        mapEmployeeLocation = findViewById(R.id.mapEmployeeLocation);
        progressMapLoading  = findViewById(R.id.progressMapLoading);
        txtLocationStatus   = findViewById(R.id.txtLocationStatus);
        txtCurrentCoords    = findViewById(R.id.txtCurrentCoords);
        txtMapHint          = findViewById(R.id.txtMapHint);
    }

    // ── Header ────────────────────────────────────────────────────────
    private void populateHeader(SharedPreferences prefs) {
        String name = prefs.getString("employee_name", "Employee");
        txtEmployeeName.setText(name);
    }

    // ── Live clock + greeting ─────────────────────────────────────────
    private void startClock() {
        clockRunnable = new Runnable() {
            @Override
            public void run() {
                int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
                String greeting = hour < 12 ? "Good morning" :
                        hour < 17 ? "Good afternoon" : "Good evening";
                txtGreeting.setText(greeting);
                txtBannerDate.setText(
                        new SimpleDateFormat("EEEE, dd MMMM yyyy", Locale.getDefault())
                                .format(new Date()));
                clockHandler.postDelayed(this, 60_000);
            }
        };
        clockHandler.post(clockRunnable);
    }

    // ── Week strip ────────────────────────────────────────────────────
    private void buildWeekStrip() {
        weekStrip.removeAllViews();
        String[] dayNames = {"Sun","Mon","Tue","Wed","Thu","Fri","Sat"};
        Calendar today    = Calendar.getInstance();
        Calendar startSun = (Calendar) today.clone();
        startSun.add(Calendar.DAY_OF_WEEK, -(today.get(Calendar.DAY_OF_WEEK) - 1));

        for (int i = 0; i < 7; i++) {
            Calendar day = (Calendar) startSun.clone();
            day.add(Calendar.DAY_OF_YEAR, i);
            boolean isToday = day.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)
                    && day.get(Calendar.YEAR)        == today.get(Calendar.YEAR);

            LinearLayout chip = new LinearLayout(this);
            chip.setOrientation(LinearLayout.VERTICAL);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(4), dp(10), dp(4), dp(10));
            chip.setBackgroundResource(isToday
                    ? R.drawable.bg_day_chip_active
                    : R.drawable.bg_day_chip);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    dp(42), LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(dp(4));
            chip.setLayoutParams(lp);

            TextView tvName = new TextView(this);
            tvName.setText(dayNames[day.get(Calendar.DAY_OF_WEEK) - 1]);
            tvName.setTextSize(10);
            tvName.setTypeface(null, Typeface.BOLD);
            tvName.setTextColor(isToday
                    ? Color.argb(180, 255, 255, 255)
                    : getResources().getColor(R.color.gray));
            tvName.setGravity(Gravity.CENTER);
            chip.addView(tvName);

            TextView tvNum = new TextView(this);
            tvNum.setText(String.valueOf(day.get(Calendar.DAY_OF_MONTH)));
            tvNum.setTextSize(17);
            tvNum.setTypeface(null, Typeface.BOLD);
            tvNum.setTextColor(isToday ? Color.WHITE
                    : getResources().getColor(R.color.black));
            tvNum.setGravity(Gravity.CENTER);
            chip.addView(tvNum);

            // Dot indicator for weekdays that are not today
            if (!isToday && i < 5) {
                View dot = new View(this);
                LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(5), dp(5));
                dotLp.gravity = Gravity.CENTER_HORIZONTAL;
                dotLp.topMargin = dp(3);
                dot.setLayoutParams(dotLp);
                dot.setBackgroundResource(R.drawable.bg_day_chip_active);
                chip.addView(dot);
            }

            chip.setOnClickListener(v -> {
                for (int c = 0; c < weekStrip.getChildCount(); c++)
                    weekStrip.getChildAt(c).setBackgroundResource(R.drawable.bg_day_chip);
                v.setBackgroundResource(R.drawable.bg_day_chip_active);
            });

            weekStrip.addView(chip);
        }
    }

    // ── Live location map ────────────────────────────────────────────
    private void initLocationMap() {

        mapEmployeeLocation.setTileSource(TileSourceFactory.MAPNIK);
        mapEmployeeLocation.setMultiTouchControls(true);
        mapEmployeeLocation.setUseDataConnection(true);

        mapEmployeeLocation.getController().setZoom(18.0);

        if (!hasLocationPermission()) {

            ActivityCompat.requestPermissions(
                    this,
                    new String[]{
                            Manifest.permission.ACCESS_FINE_LOCATION
                    },
                    REQ_LOCATION_PERMISSION
            );

            return;
        }

        myLocationOverlay = new MyLocationNewOverlay(
                new GpsMyLocationProvider(this),
                mapEmployeeLocation
        );
        myLocationOverlay.setDrawAccuracyEnabled(false);

        myLocationOverlay.enableMyLocation();
        myLocationOverlay.enableFollowLocation();

        mapEmployeeLocation.getOverlays().add(myLocationOverlay);

        txtLocationStatus.setText("Locating...");
        txtCurrentCoords.setText("Fetching GPS coordinates...");

        myLocationOverlay.runOnFirstFix(() ->
                runOnUiThread(this::onFirstLocationFix));
    }

    /** Called once GPS gets first fix */
    private void onFirstLocationFix() {

        if (myLocationOverlay == null)
            return;

        GeoPoint location = myLocationOverlay.getMyLocation();

        if (location == null)
            return;

        progressMapLoading.setVisibility(View.GONE);

        mapEmployeeLocation.getController().animateTo(location);
        mapEmployeeLocation.getController().setZoom(18.0);

        txtLocationStatus.setText("Live");
        txtLocationStatus.setTextColor(
                getResources().getColor(R.color.green_text)
        );

        txtCurrentCoords.setText(
                String.format(
                        Locale.getDefault(),
                        "%.5f, %.5f",
                        location.getLatitude(),
                        location.getLongitude()
                )
        );

        mapEmployeeLocation.invalidate();
        loadOfficeLocation();
    }

    private void loadOfficeLocation() {

        OkHttpClient client = new OkHttpClient();

        Request request = new Request.Builder()
                .url(Config.BASE_URL + "/location")
                .get()
                .build();

        client.newCall(request).enqueue(new Callback() {

            @Override
            public void onFailure(Call call, IOException e) {
            }

            @Override
            public void onResponse(Call call, Response response)
                    throws IOException {

                String body = response.body().string();

                runOnUiThread(() -> {

                    try {

                        JSONObject json =
                                new JSONObject(body);

                        JSONObject location =
                                json.getJSONObject("location");

                        double officeLat =
                                location.getDouble("latitude");

                        double officeLng =
                                location.getDouble("longitude");

                        double radius =
                                location.getDouble("radius");

                        showOfficeMarker(
                                officeLat,
                                officeLng,
                                radius
                        );

                    } catch (Exception e) {
                        e.printStackTrace();
                    }

                });
            }
        });
    }

    /**
     * Checks for a *validated* internet connection (i.e. actually reaches the internet,
     * not just connected to wifi). Shows a banner over the map if tiles likely can't load —
     * very common on campus wifi that uses a captive portal.
     */
    private void checkConnectivityAndWarn() {
        if (txtMapHint == null) return;
        boolean hasValidatedInternet = false;
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm != null) {
            Network network = cm.getActiveNetwork();
            if (network != null) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                hasValidatedInternet = caps != null
                        && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
            }
        }
        txtMapHint.setVisibility(hasValidatedInternet ? View.GONE : View.VISIBLE);
    }

    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            @NonNull String[] permissions,
            @NonNull int[] grantResults) {

        super.onRequestPermissionsResult(
                requestCode,
                permissions,
                grantResults
        );

        if (requestCode == REQ_LOCATION_PERMISSION) {

            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {

                initLocationMap();

            } else {

                txtLocationStatus.setText("Location Disabled");

                txtCurrentCoords.setText(
                        "Location permission denied"
                );
            }
        }
    }
    private void showOfficeMarker(
            double lat,
            double lng,
            double radius) {

        GeoPoint office =
                new GeoPoint(lat, lng);

        // Office marker
        Marker officeMarker =
                new Marker(mapEmployeeLocation);

        officeMarker.setPosition(office);
        officeMarker.setTitle("Office");

        mapEmployeeLocation.getOverlays()
                .add(officeMarker);

        // Geofence circle
        Polygon circle = new Polygon();

        circle.setPoints(
                Polygon.pointsAsCircle(
                        office,
                        radius
                )
        );

        circle.setFillColor(0x220000FF);
        circle.setStrokeColor(0xFF0000FF);
        circle.setStrokeWidth(3f);

        mapEmployeeLocation.getOverlays()
                .add(circle);

        mapEmployeeLocation.invalidate();
    }

    // ── Recent records from API ────────────────────────────────────────
    private void loadRecentRecords() {
        // Show a placeholder immediately so the card is never empty
        showPlaceholderRecords();

        OkHttpClient client = new OkHttpClient();
        Request request = new Request.Builder()
                .url(Config.BASE_URL + "/employee/attendance?employee_id=" + employeeId)
                .get()
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                // Keep the placeholder rows visible — don't crash
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String body = response.body().string();
                runOnUiThread(() -> {
                    try {
                        JSONObject json    = new JSONObject(body);
                        JSONArray  records = json.getJSONArray("records");
                        JSONObject today   = json.optJSONObject("today");

                        // Update today clock in / out
                        if (today != null) {
                            String inT  = today.optString("clock_in_time",  "");
                            String outT = today.optString("clock_out_time", "");
                            txtTodayClockIn.setText(formatTime(inT));
                            txtTodayClockOut.setText(
                                    outT.isEmpty() || outT.equals("null")
                                            ? "--:--" : formatTime(outT));
                        }

                        // Render up to 3 recent rows
                        layoutHomeRecords.removeAllViews();
                        int count = Math.min(records.length(), 3);
                        if (count == 0) {
                            showPlaceholderRecords();
                            return;
                        }
                        for (int i = 0; i < count; i++) {
                            JSONObject r = records.getJSONObject(i);
                            addRecordRow(i,
                                    r.optString("attendance_date",   ""),
                                    r.optString("clock_in_time",     ""),
                                    r.optString("clock_out_time",    ""),
                                    r.optString("attendance_status", ""));
                        }
                    } catch (Exception e) {
                        showPlaceholderRecords(); // fallback on parse error
                    }
                });
            }
        });
    }

    /** Shows 3 placeholder rows so the card never looks empty on first launch. */
    private void showPlaceholderRecords() {
        layoutHomeRecords.removeAllViews();
        String[][] samples = {
                {"Today",      "--:--", "--:--", ""},
                {"Yesterday",  "--:--", "--:--", ""},
                {"2 days ago", "--:--", "--:--", ""},
        };
        for (int i = 0; i < samples.length; i++) {
            addRecordRow(i, samples[i][0], samples[i][1], samples[i][2], samples[i][3]);
        }
    }

    private void addRecordRow(int idx, String date, String in, String out, String status) {
        // Divider (not before first row)
        if (idx > 0) {
            View divider = new View(this);
            LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1);
            divLp.setMarginStart(dp(16));
            divider.setLayoutParams(divLp);
            divider.setBackgroundColor(getResources().getColor(R.color.separator));
            layoutHomeRecords.addView(divider);
        }

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(14), dp(16), dp(14));

        // Left: date + times
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT);
        infoLp.weight = 1;
        info.setLayoutParams(infoLp);

        TextView tvDate = new TextView(this);
        tvDate.setText(date.length() > 6 ? formatDate(date) : date); // skip format if already short
        tvDate.setTextSize(15);
        tvDate.setTypeface(null, Typeface.BOLD);
        tvDate.setTextColor(getResources().getColor(R.color.black));
        info.addView(tvDate);

        TextView tvTimes = new TextView(this);
        tvTimes.setText(formatTime(in) + "  →  " + formatTime(out));
        tvTimes.setTextSize(12);
        tvTimes.setTextColor(getResources().getColor(R.color.gray));
        info.addView(tvTimes);

        row.addView(info);

        // Right: status badge
        if (!status.isEmpty()) {
            TextView badge = new TextView(this);
            badge.setText(status);
            badge.setTextSize(11);
            badge.setTypeface(null, Typeface.BOLD);
            badge.setPadding(dp(10), dp(3), dp(10), dp(3));
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

        layoutHomeRecords.addView(row);
    }

    private void setupQuickActions() {
        Intent camera = new Intent(this, CameraActivity.class);
        btnQuickClockIn.setOnClickListener(v -> startActivity(camera));
        btnQuickClockOut.setOnClickListener(v -> startActivity(camera));
        if (btnSeeAll != null) {
            btnSeeAll.setOnClickListener(v ->
                    startActivity(new Intent(this, HistoryActivity.class)));
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────
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

    private int dp(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        clockHandler.removeCallbacks(clockRunnable);
        if (mapEmployeeLocation != null) mapEmployeeLocation.onDetach();
    }
}