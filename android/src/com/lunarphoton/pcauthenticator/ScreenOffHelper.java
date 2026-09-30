package com.lunarphoton.pcauthenticator;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

public class ScreenOffHelper {

    public static boolean isDeviceAdminActive(Context context) {
        DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        ComponentName admin = new ComponentName(context, AdminReceiver.class);
        return dpm != null && dpm.isAdminActive(admin);
    }

    public static void requestTurnScreenOff(Activity activity) {
        DevicePolicyManager dpm = (DevicePolicyManager) activity.getSystemService(Context.DEVICE_POLICY_SERVICE);
        ComponentName admin = new ComponentName(activity, AdminReceiver.class);
        if (dpm != null && dpm.isAdminActive(admin)) {
            try {
                dpm.lockNow();
            } catch (Exception e) {
                Toast.makeText(activity, "Failed to lock screen: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        } else {
            showPermissionOrBlackoutDialog(activity);
        }
    }

    public static void showPermissionOrBlackoutDialog(Activity activity) {
        new AlertDialog.Builder(activity)
                .setTitle("📱 Turn Off Phone Screen")
                .setMessage("To turn off and lock the phone screen with one tap, grant the Screen Lock permission.\n\nAlternatively, you can use Blackout Mode (turns screen completely black with zero brightness, saving battery).")
                .setPositiveButton("Enable Screen Lock", (dialog, which) -> {
                    ComponentName admin = new ComponentName(activity, AdminReceiver.class);
                    Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
                    intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin);
                    intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Allows PC Connect to turn off and lock the phone screen.");
                    activity.startActivity(intent);
                })
                .setNeutralButton("Blackout Mode", (dialog, which) -> {
                    showBlackoutOverlay(activity);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    public static void showBlackoutOverlay(Activity activity) {
        Dialog dialog = new Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        FrameLayout layout = new FrameLayout(activity);
        layout.setBackgroundColor(Color.BLACK);
        layout.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView tv = new TextView(activity);
        tv.setText("🌙 Tap anywhere to wake");
        tv.setTextColor(Color.parseColor("#334155"));
        tv.setTextSize(13);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER;
        layout.addView(tv, lp);

        dialog.setContentView(layout);
        Window w = dialog.getWindow();
        if (w != null) {
            WindowManager.LayoutParams params = w.getAttributes();
            params.screenBrightness = 0.0f; // Minimum brightness (AMOLED pixels off)
            w.setAttributes(params);
        }

        layout.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }
}
