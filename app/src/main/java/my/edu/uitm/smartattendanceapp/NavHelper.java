package my.edu.uitm.smartattendanceapp;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.FrameLayout;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.widget.ImageViewCompat;

public final class NavHelper {

    public static final int TAB_HOME = 0;
    public static final int TAB_DASHBOARD = 1;
    public static final int TAB_HISTORY = 2;
    public static final int TAB_PROFILE = 3;

    private NavHelper() {}

    public static void setup(AppCompatActivity activity, int activeTab) {
        LinearLayout navHome      = activity.findViewById(R.id.navHome);
        LinearLayout navDashboard = activity.findViewById(R.id.navDashboard);
        LinearLayout navHistory   = activity.findViewById(R.id.navHistory);
        LinearLayout navProfile   = activity.findViewById(R.id.navProfile);
        View navCamera            = activity.findViewById(R.id.navCamera);

        setTab(activity, navHome,
                activity.findViewById(R.id.navHomeIcon),
                activity.findViewById(R.id.navHomeLabel),
                activeTab == TAB_HOME);

        setTab(activity, navDashboard,
                activity.findViewById(R.id.navDashboardIcon),
                activity.findViewById(R.id.navDashboardLabel),
                activeTab == TAB_DASHBOARD);

        setTab(activity, navHistory,
                activity.findViewById(R.id.navHistoryIcon),
                activity.findViewById(R.id.navHistoryLabel),
                activeTab == TAB_HISTORY);

        setTab(activity, navProfile,
                activity.findViewById(R.id.navProfileIcon),
                activity.findViewById(R.id.navProfileLabel),
                activeTab == TAB_PROFILE);

        if (navHome != null) {
            navHome.setOnClickListener(v -> {
                if (activeTab != TAB_HOME) {
                    activity.startActivity(new Intent(activity, MainActivity.class));
                    activity.finish();
                }
            });
        }

        if (navDashboard != null) {
            navDashboard.setOnClickListener(v -> {
                if (activeTab != TAB_DASHBOARD) {
                    activity.startActivity(new Intent(activity, DashboardActivity.class));
                    if (activeTab == TAB_HOME || activeTab == TAB_HISTORY) {
                        activity.finish();
                    }
                }
            });
        }

        if (navCamera != null) {
            navCamera.setOnClickListener(v ->
                    activity.startActivity(new Intent(activity, CameraActivity.class)));
        }

        if (navHistory != null) {
            navHistory.setOnClickListener(v -> {
                if (activeTab != TAB_HISTORY) {
                    activity.startActivity(new Intent(activity, HistoryActivity.class));
                    if (activeTab == TAB_HOME) {
                        activity.finish();
                    }
                }
            });
        }

        if (navProfile != null) {
            navProfile.setOnClickListener(v -> {
                if (activeTab != TAB_PROFILE) {
                    activity.startActivity(new Intent(activity, ProfileActivity.class));
                    if (activeTab == TAB_HOME || activeTab == TAB_HISTORY) {
                        activity.finish();
                    }
                }
            });
        }
    }

    private static void setTab(AppCompatActivity activity, LinearLayout tab,
                               ImageView icon, TextView label, boolean active) {
        int color = ContextCompat.getColor(activity,
                active ? R.color.nav_active : R.color.nav_inactive);
        if (icon != null) {
            ImageViewCompat.setImageTintList(icon, ColorStateList.valueOf(color));
        }
        if (label != null) {
            label.setTextColor(color);
        }
    }
}
