package com.zomdroid.steam;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.zomdroid.C;

/**
 * Manages persistent Steam login sessions. Stores and retrieves the Steam username
 * and OAuth refresh token across app restarts.
 */
public final class SteamSessionManager {

    private SteamSessionManager() {}

    private static SharedPreferences getPrefs(@NonNull Context context) {
        return context.getSharedPreferences(C.shprefs.NAME, Context.MODE_PRIVATE);
    }

    public static boolean hasSavedSession(@NonNull Context context) {
        String token = getSavedRefreshToken(context);
        return token != null && !token.trim().isEmpty();
    }

    @Nullable
    public static String getSavedUsername(@NonNull Context context) {
        return getPrefs(context).getString(C.shprefs.keys.STEAM_USERNAME, null);
    }

    @Nullable
    public static String getSavedRefreshToken(@NonNull Context context) {
        return getPrefs(context).getString(C.shprefs.keys.STEAM_REFRESH_TOKEN, null);
    }

    public static void saveSession(@NonNull Context context, @NonNull String username, @NonNull String refreshToken) {
        getPrefs(context).edit()
                .putString(C.shprefs.keys.STEAM_USERNAME, username)
                .putString(C.shprefs.keys.STEAM_REFRESH_TOKEN, refreshToken)
                .apply();
    }

    public static void clearSession(@NonNull Context context) {
        getPrefs(context).edit()
                .remove(C.shprefs.keys.STEAM_USERNAME)
                .remove(C.shprefs.keys.STEAM_REFRESH_TOKEN)
                .apply();
    }
}
