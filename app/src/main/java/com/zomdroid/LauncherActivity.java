package com.zomdroid;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.method.LinkMovementMethod;
import android.text.util.Linkify;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;
import androidx.navigation.ui.AppBarConfiguration;
import androidx.navigation.ui.NavigationUI;

import com.google.android.material.badge.BadgeDrawable;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.zomdroid.databinding.ActivityLauncherBinding;
import com.zomdroid.game.GameInstance;
import com.zomdroid.game.GameInstanceManager;
import com.zomdroid.input.GamepadManager;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class LauncherActivity extends AppCompatActivity {
    private static final String LOG_TAG = LauncherActivity.class.getName();
    ActivityLauncherBinding binding;
    private NavController navController;
    private AppBarConfiguration appBarConfiguration;
    private int systemBottomInset = 0;

    private static final Set<Integer> TOP_LEVEL_DESTINATIONS = new HashSet<>(Arrays.asList(
            R.id.launcher_fragment,
            R.id.controls_hub_fragment,
            R.id.downloads_hub_fragment,
            R.id.settings_hub_fragment
    ));

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        EdgeToEdge.enable(this);

        super.onCreate(savedInstanceState);

        binding = ActivityLauncherBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // >>> ensure custom mapping is loaded at app start <<<
        GamepadManager.loadCustomMapping(this);

        setSupportActionBar(binding.appbar);

        NavHostFragment navHostFragment = (NavHostFragment) getSupportFragmentManager()
                .findFragmentById(R.id.nav_host_fragment);
        if (navHostFragment != null) {
            navController = navHostFragment.getNavController();
        }

        appBarConfiguration = new AppBarConfiguration.Builder(TOP_LEVEL_DESTINATIONS).build();

        if (navController != null) {
            NavigationUI.setupActionBarWithNavController(this, navController, appBarConfiguration);
            NavigationUI.setupWithNavController(binding.bottomNavigation, navController);

            navController.addOnDestinationChangedListener((controller, destination, arguments) -> {
                boolean isTopLevel = TOP_LEVEL_DESTINATIONS.contains(destination.getId());
                binding.bottomNavigation.setVisibility(isTopLevel ? View.VISIBLE : View.GONE);
                updateBottomPadding();
            });
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.getRoot(), (v, windowInsets) -> {
            Insets systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            systemBottomInset = systemBars.bottom;
            binding.appbarLayout.setPadding(0, systemBars.top, 0, 0);
            binding.bottomNavigation.setPadding(0, 0, 0, systemBars.bottom);
            updateBottomPadding();
            return windowInsets;
        });

        maybeDailyUpdateCheck();
    }

    private void updateBottomPadding() {
        if (binding == null) return;
        if (binding.bottomNavigation.getVisibility() == View.VISIBLE) {
            binding.navHostFragment.setPadding(0, 0, 0, 0);
        } else {
            binding.navHostFragment.setPadding(0, 0, 0, systemBottomInset);
        }
    }

    public void showDonateDialog() {
        final SpannableString s = new SpannableString(getString(R.string.donate_message));
        Linkify.addLinks(s, Linkify.WEB_URLS);
        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dialog_title_donate)
                .setMessage(s)
                .setPositiveButton(getString(R.string.dialog_button_ok), null)
                .create();
        dialog.show();
        TextView messageView = dialog.findViewById(android.R.id.message);
        if (messageView != null) messageView.setMovementMethod(LinkMovementMethod.getInstance());
    }

    public void showRimDroidDialog() {
        final SpannableString s = new SpannableString(getString(R.string.rimdroid_dialog_message));
        Linkify.addLinks(s, Linkify.WEB_URLS);
        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.rimdroid_dialog_title)
                .setMessage(s)
                .setPositiveButton(getString(R.string.dialog_button_ok), null)
                .create();
        dialog.show();
        TextView messageView = dialog.findViewById(android.R.id.message);
        if (messageView != null) messageView.setMovementMethod(LinkMovementMethod.getInstance());
    }

    public void showRedditDialog() {
        final SpannableString s = new SpannableString(getString(R.string.reddit_message));
        Linkify.addLinks(s, Linkify.WEB_URLS);
        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.reddit_dialog_title)
                .setMessage(s)
                .setPositiveButton(getString(R.string.dialog_button_ok), null)
                .create();
        dialog.show();
        TextView messageView = dialog.findViewById(android.R.id.message);
        if (messageView != null) messageView.setMovementMethod(LinkMovementMethod.getInstance());
    }

    @Override
    public boolean onSupportNavigateUp() {
        return NavigationUI.navigateUp(navController, appBarConfiguration)
                || super.onSupportNavigateUp();
    }

    public void checkForUpdate() {
        new Thread(() -> {
            try {
                JSONObject json = fetchLatestRelease();
                String latestTag = json.getString("tag_name"); // "v1.4.1"
                String releaseUrl = json.getString("html_url");
                String latest = latestTag.startsWith("v") ? latestTag.substring(1) : latestTag;
                String current = BuildConfig.VERSION_NAME;

                // Keep the daily-check badge state in sync with whatever the manual check just saw.
                LauncherPreferences.requireSingleton().setLatestSeenTag(latestTag);

                runOnUiThread(() -> { showVersionDialog(current, latest, releaseUrl); refreshUpdateBadge(); });

            } catch (Exception e) {
                runOnUiThread(() -> showVersionDialog(BuildConfig.VERSION_NAME, null, null));
            }
        }).start();
    }

    private static JSONObject fetchLatestRelease() throws Exception {
        URL url = new URL("https://api.github.com/repos/udarmolota/zomdroid/releases/latest");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestProperty("Accept", "application/vnd.github+json");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);

        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
        }
        return new JSONObject(sb.toString());
    }

    public boolean updateAvailable() {
        String tag = LauncherPreferences.requireSingleton().getLatestSeenTag();
        if (tag == null || tag.trim().isEmpty()) return false;
        return compareVersions(tag.replaceFirst("^[vV]", ""), BuildConfig.VERSION_NAME) > 0;
    }

    private static int compareVersions(String a, String b) {
        String[] pa = a.split("[.\\-+ ]"), pb = b.split("[.\\-+ ]");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            int x = i < pa.length ? parseIntSafe(pa[i]) : 0;
            int y = i < pb.length ? parseIntSafe(pb[i]) : 0;
            if (x != y) return Integer.compare(x, y);
        }
        return 0;
    }

    private static int parseIntSafe(String s) {
        if (s == null) return 0;
        String t = s.trim();
        int end = 0;
        while (end < t.length() && Character.isDigit(t.charAt(end))) end++;
        if (end == 0) return 0;
        try { return Integer.parseInt(t.substring(0, end)); } catch (Exception e) { return 0; }
    }

    public void refreshUpdateBadge() {
        if (binding == null) return;
        boolean hasUpdate = updateAvailable();
        BadgeDrawable badge = binding.bottomNavigation.getOrCreateBadge(R.id.settings_hub_fragment);
        badge.setVisible(hasUpdate);
    }

    private void maybeDailyUpdateCheck() {
        refreshUpdateBadge();
        LauncherPreferences lp = LauncherPreferences.requireSingleton();
        String today = new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
                .format(new java.util.Date());
        if (today.equals(lp.getUpdateCheckDay())) return;
        lp.setUpdateCheckDay(today);

        new Thread(() -> {
            try {
                String tag = fetchLatestRelease().getString("tag_name");
                lp.setLatestSeenTag(tag);
                runOnUiThread(this::refreshUpdateBadge);
            } catch (Exception ignored) {
            }
        }, "zd-daily-update-check").start();
    }

    public void sendBugReport() {
        final java.util.Date now = new java.util.Date();
        final String date = new java.text.SimpleDateFormat("ddMMyyyy", java.util.Locale.US).format(now);
        final String device = "Device: " + Build.MANUFACTURER + " " + Build.MODEL
                + "\nAndroid: " + Build.VERSION.RELEASE
                + "\nZomdroid: " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")";

        java.util.List<GameInstance> instances = GameInstanceManager.requireSingleton().getInstances();
        if (instances.isEmpty()) { buildAndSendBugReport(date, device, null); return; }

        if (instances.size() > 1) {
            String[] names = new String[instances.size()];
            for (int i = 0; i < instances.size(); i++) names[i] = instances.get(i).getName();
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.bug_report_pick_instance)
                    .setItems(names, (d, which) ->
                            buildAndSendBugReport(date, device, instances.get(which)))
                    .setNegativeButton(R.string.dialog_button_cancel, null)
                    .show();
            return;
        }
        buildAndSendBugReport(date, device, instances.get(0));
    }

    private void buildAndSendBugReport(String date, String device, GameInstance instance) {
        final String timestamp = new java.text.SimpleDateFormat("ddMMyyyy_HHmm", java.util.Locale.US).format(new java.util.Date());
        Toast.makeText(this, R.string.bug_report_preparing, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            Uri attach = null;
            try {
                File dir = new File(getCacheDir(), "reports");
                if (dir.isDirectory() || dir.mkdirs()) {
                    File zip = new File(dir, "zomdroid_report_" + timestamp + ".zip");
                    try (OutputStream out = new FileOutputStream(zip)) {
                        InstallerService.writeLogReportZip(instance, out);
                    }
                    if (zip.length() > 0)
                        attach = androidx.core.content.FileProvider.getUriForFile(
                                this, "com.zomdroid.fileprovider", zip);
                }
            } catch (Throwable t) { attach = null; }
            final Uri fAttach = attach;
            runOnUiThread(() -> startBugReportEmail(date, device, fAttach));
        }).start();
    }

    private void startBugReportEmail(String date, String device, Uri attachment) {
        String[] to = { getString(R.string.bug_report_email) };
        String subject = getString(R.string.bug_report_subject, date);
        String body = getString(R.string.bug_report_body, device);
        Intent i;
        if (attachment != null) {
            i = new Intent(Intent.ACTION_SEND);
            i.setType("application/zip");
            i.putExtra(Intent.EXTRA_EMAIL, to);
            i.putExtra(Intent.EXTRA_SUBJECT, subject);
            i.putExtra(Intent.EXTRA_TEXT, body);
            i.putExtra(Intent.EXTRA_STREAM, attachment);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try { startActivity(Intent.createChooser(i, getString(R.string.nav_bug_report))); return; }
            catch (android.content.ActivityNotFoundException ignored) { }
        }
        i = new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"));
        i.putExtra(Intent.EXTRA_EMAIL, to);
        i.putExtra(Intent.EXTRA_SUBJECT, subject);
        i.putExtra(Intent.EXTRA_TEXT, body);
        try {
            startActivity(i);
        } catch (android.content.ActivityNotFoundException e) {
            Toast.makeText(this, R.string.bug_report_no_mail, Toast.LENGTH_LONG).show();
        }
    }

    private void showVersionDialog(String current, String latest, String releaseUrl) {
        String message;
        if (latest == null) {
            message = getString(R.string.version_check_error, current);
        } else {
            int cmp = compareVersions(latest, current);
            if (cmp > 0) {
                message = getString(R.string.version_check_update_available, current, latest, releaseUrl);
            } else if (cmp == 0) {
                message = getString(R.string.version_check_up_to_date, current);
            } else {
                message = getString(R.string.version_check_ahead_of_release, current, latest);
            }
        }

        message += "\n\n" + getString(R.string.credits_third_party);

        SpannableString s = new SpannableString(message);
        Linkify.addLinks(s, Linkify.WEB_URLS);
        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.version_check_title)
                .setMessage(s)
                .setPositiveButton(R.string.dialog_button_ok, null)
                .create();
        dialog.show();
        TextView messageView = dialog.findViewById(android.R.id.message);
        if (messageView != null) {
            messageView.setMovementMethod(LinkMovementMethod.getInstance());
        }
    }
}
