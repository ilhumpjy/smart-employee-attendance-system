package my.edu.uitm.smartattendanceapp;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.concurrent.Executors;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class ProfilePhotohelper {

    public static void load(Context context,
                            ImageView imgView,
                            FrameLayout frameInitials,
                            TextView txtInitial) {

        SharedPreferences prefs =
                context.getSharedPreferences("AttendanceApp", Context.MODE_PRIVATE);

        int employeeId = prefs.getInt("employee_id", -1);

        String name = prefs.getString("employee_name", "Employee");

        if (txtInitial != null) {
            txtInitial.setText(
                    name.isEmpty()
                            ? "E"
                            : String.valueOf(name.charAt(0)).toUpperCase()
            );
        }

        if (employeeId == -1)
            return;

        Executors.newSingleThreadExecutor().execute(() -> {

            try {

                OkHttpClient client = new OkHttpClient();

                Request request = new Request.Builder()
                        .url(Config.BASE_URL + "/api/profile/photo/" + employeeId)
                        .build();

                try (Response response = client.newCall(request).execute()) {

                    if (response.isSuccessful() && response.body() != null) {

                        byte[] bytes = response.body().bytes();

                        Bitmap bitmap = BitmapFactory.decodeByteArray(
                                bytes,
                                0,
                                bytes.length
                        );

                        if (bitmap != null) {

                            ((Activity) context).runOnUiThread(() -> {

                                imgView.setImageBitmap(bitmap);
                                imgView.setVisibility(View.VISIBLE);

                                if (frameInitials != null)
                                    frameInitials.setVisibility(View.GONE);

                            });

                        } else {

                            ((Activity) context).runOnUiThread(() -> {

                                imgView.setVisibility(View.GONE);

                                if (frameInitials != null)
                                    frameInitials.setVisibility(View.VISIBLE);

                            });

                        }

                    } else {

                        ((Activity) context).runOnUiThread(() -> {

                            imgView.setVisibility(View.GONE);

                            if (frameInitials != null)
                                frameInitials.setVisibility(View.VISIBLE);

                        });

                    }

                }

            } catch (Exception e) {

                ((Activity) context).runOnUiThread(() -> {

                    imgView.setVisibility(View.GONE);

                    if (frameInitials != null)
                        frameInitials.setVisibility(View.VISIBLE);

                });

            }

        });

    }

    public static void load(Context context, ImageView imgView) {
        load(context, imgView, null, null);
    }
}