package com.wpnfa.configscan;

import android.app.Application;

import com.google.android.material.color.DynamicColors;

/**
 * Application entry point.
 * On Android 12+ this applies the system wallpaper color palette
 * (Material You / dynamic color) to the Material components.
 */
public class ConfigScannerApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        DynamicColors.applyToActivitiesIfAvailable(this);
    }
}
