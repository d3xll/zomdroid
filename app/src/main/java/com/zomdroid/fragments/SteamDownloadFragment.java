package com.zomdroid.fragments;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.method.ScrollingMovementMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.slider.Slider;
import com.zomdroid.R;
import com.zomdroid.steam.SteamDownloadState;
import com.zomdroid.steam.SteamGameDownloader;
import com.zomdroid.steam.SteamSessionManager;

import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "Download from Steam" screen. The actual download runs on a background thread tracked by
 * {@link SteamDownloadState}, so leaving and returning keeps the log and "still downloading" state.
 */
public class SteamDownloadFragment extends Fragment implements SteamDownloadState.View {
    public static final String ARG_MACOS_INSTANCE = "macos_instance";
    public static final String ARG_MP_INSTANCE = "mp_instance";
    private String macosInstanceName;
    private String mpInstanceName;

    private EditText etUser, etPass, etManifest;
    private MaterialCheckBox cbRememberSession;
    private View layoutSavedSession, layoutCredentialsInput;
    private TextView tvSavedUser;
    private Button btnSteamLogout;
    private Button btnStart, btnCancel;
    private Slider sliderConnections;
    private TextView tvConnectionsLabel;
    private MaterialCheckBox cbVerifyFiles;
    private TextView tvDlFile;
    private TextView tvDlSpeed;
    private ProgressBar progress;
    private TextView tvStatus;
    private MaterialButtonToggleGroup buildToggle;
    private Context appCtx;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_steam_download, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);
        appCtx = requireContext().getApplicationContext();
        macosInstanceName = getArguments() == null ? null : getArguments().getString(ARG_MACOS_INSTANCE);
        mpInstanceName = getArguments() == null ? null : getArguments().getString(ARG_MP_INSTANCE);
        etUser = v.findViewById(R.id.et_dl_user);
        etPass = v.findViewById(R.id.et_dl_pass);
        cbRememberSession = v.findViewById(R.id.cb_remember_session);
        layoutSavedSession = v.findViewById(R.id.layout_saved_session);
        layoutCredentialsInput = v.findViewById(R.id.layout_credentials_input);
        tvSavedUser = v.findViewById(R.id.tv_saved_user);
        btnSteamLogout = v.findViewById(R.id.btn_steam_logout);
        if (btnSteamLogout != null) {
            btnSteamLogout.setOnClickListener(view -> {
                SteamSessionManager.clearSession(appCtx);
                updateLoginUi();
            });
        }
        updateLoginUi();

        etManifest = v.findViewById(R.id.et_dl_manifest);
        btnStart = v.findViewById(R.id.btn_dl_start);
        btnCancel = v.findViewById(R.id.btn_dl_cancel);
        sliderConnections = v.findViewById(R.id.slider_dl_connections);
        tvConnectionsLabel = v.findViewById(R.id.tv_dl_connections);
        cbVerifyFiles = v.findViewById(R.id.cb_verify_files);
        tvDlFile = v.findViewById(R.id.tv_dl_file);
        tvDlSpeed = v.findViewById(R.id.tv_dl_speed);
        progress = v.findViewById(R.id.progress_dl);
        tvStatus = v.findViewById(R.id.tv_dl_status);

        if (sliderConnections != null) {
            int savedConn = SteamSessionManager.getMaxConnections(appCtx);
            sliderConnections.setValue(savedConn);
            if (tvConnectionsLabel != null) {
                tvConnectionsLabel.setText(getString(R.string.steam_dl_connections_title, savedConn));
            }
            sliderConnections.addOnChangeListener((slider, value, fromUser) -> {
                int count = Math.round(value);
                if (tvConnectionsLabel != null) {
                    tvConnectionsLabel.setText(getString(R.string.steam_dl_connections_title, count));
                }
                SteamSessionManager.setMaxConnections(appCtx, count);
            });
        }

        if (cbVerifyFiles != null) {
            boolean savedVerify = SteamSessionManager.getVerifyFiles(appCtx);
            cbVerifyFiles.setChecked(savedVerify);
            cbVerifyFiles.setOnCheckedChangeListener((btn, isChecked) -> {
                SteamSessionManager.setVerifyFiles(appCtx, isChecked);
            });
        }

        tvStatus.setMovementMethod(new ScrollingMovementMethod());
        btnStart.setOnClickListener(view -> startGame());
        btnCancel.setOnClickListener(view -> confirmCancel());

        buildToggle = v.findViewById(R.id.toggle_dl_build);
        buildToggle.check(R.id.btn_build_41);   // legacy41 is the default selection
        if (macosInstanceName != null || mpInstanceName != null) {
            buildToggle.setVisibility(View.GONE);
            etManifest.setVisibility(View.GONE);
            if (sliderConnections != null) sliderConnections.setVisibility(View.GONE);
            if (tvConnectionsLabel != null) tvConnectionsLabel.setVisibility(View.GONE);
            if (cbVerifyFiles != null) cbVerifyFiles.setVisibility(View.GONE);
            v.findViewById(R.id.tv_slow_warning).setVisibility(View.GONE);
            ((TextView) v.findViewById(R.id.tv_login_note)).setText(getString(
                    mpInstanceName != null ? R.string.mp_libs_login : R.string.macos_libs_login,
                    mpInstanceName != null ? mpInstanceName : macosInstanceName));
            // Hide the Linux manifest instructions while retaining the shared login/progress UI.
            ViewGroup gameControls = (ViewGroup) btnStart.getParent();
            for (int i = 0; i < gameControls.getChildCount(); i++) {
                View child = gameControls.getChildAt(i);
                if (child != btnStart) child.setVisibility(View.GONE);
            }
            btnStart.setText(R.string.macos_libs_download);
        }

        // A pinned manifest fully determines what gets downloaded (see parseManifestId /
        // parseBranchOverride below), so the toggle is redundant — and potentially misleading,
        // since toggling it while a manifest is entered would not actually change anything.
        etManifest.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                boolean pinned = !s.toString().trim().isEmpty();
                buildToggle.setEnabled(!pinned);
                for (int i = 0; i < buildToggle.getChildCount(); i++) {
                    buildToggle.getChildAt(i).setEnabled(!pinned);
                }
            }
        });

        // Re-attach to any in-progress download that outlived a previous view.
        SteamDownloadState.get().setView(this);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        SteamDownloadState.get().clearView(this);
    }

    private static final Pattern MANIFEST_ARG = Pattern.compile("-manifest\\s+(\\d{16,20})");
    private static final Pattern RAW_MANIFEST  = Pattern.compile("^\\s*(\\d{16,20})\\s*$");
    private static final Pattern BETA_ARG      = Pattern.compile("-beta\\s+(\\S+)");

    private static long parseManifestId(String raw) {
        if (raw == null || raw.trim().isEmpty()) return 0L;
        Matcher m = RAW_MANIFEST.matcher(raw);
        if (m.find()) {
            try { return Long.parseLong(m.group(1)); } catch (NumberFormatException ignored) {}
        }
        m = MANIFEST_ARG.matcher(raw);
        if (m.find()) {
            try { return Long.parseLong(m.group(1)); } catch (NumberFormatException ignored) {}
        }
        return 0L;
    }

    private static String parseBranchOverride(String raw) {
        Matcher m = BETA_ARG.matcher(raw);
        return m.find() ? m.group(1) : null;
    }

    private void updateLoginUi() {
        if (layoutSavedSession == null || layoutCredentialsInput == null) return;
        boolean hasSaved = SteamSessionManager.hasSavedSession(appCtx);
        if (hasSaved) {
            String user = SteamSessionManager.getSavedUsername(appCtx);
            if (tvSavedUser != null) {
                tvSavedUser.setText(getString(R.string.steam_dl_logged_in_as, user != null ? user : "Steam"));
            }
            layoutSavedSession.setVisibility(View.VISIBLE);
            layoutCredentialsInput.setVisibility(View.GONE);
        } else {
            layoutSavedSession.setVisibility(View.GONE);
            layoutCredentialsInput.setVisibility(View.VISIBLE);
        }
    }

    // ---- start ----
    private void startGame() {
        if (SteamDownloadState.get().isDownloading()) return;

        boolean hasSaved = SteamSessionManager.hasSavedSession(appCtx);
        String username;
        String password;
        String refreshToken;
        boolean remember;
        if (hasSaved) {
            username = SteamSessionManager.getSavedUsername(appCtx);
            password = null;
            refreshToken = SteamSessionManager.getSavedRefreshToken(appCtx);
            remember = true;
        } else {
            if (text(etUser).isEmpty() || etPass.getText().toString().isEmpty()) {
                Toast.makeText(requireContext(), R.string.steam_dl_required, Toast.LENGTH_SHORT).show();
                return;
            }
            username = text(etUser);
            password = etPass.getText().toString();
            refreshToken = null;
            remember = cbRememberSession != null && cbRememberSession.isChecked();
        }

        if (macosInstanceName == null && mpInstanceName == null && !ensureAllFilesAccess()) return;

        String manifestText = text(etManifest);
        long manifestId = parseManifestId(manifestText);
        String branchOverride = manifestId > 0 ? parseBranchOverride(manifestText) : null;

        String branch;
        String buildLabel;
        if (manifestId > 0) {
            branch = branchOverride != null ? branchOverride : "public";
            buildLabel = branchOverride != null ? branchOverride : "manifest";
        } else {
            boolean is42 = buildToggle.getCheckedButtonId() == R.id.btn_build_42;
            branch = is42 ? "public" : "legacy41";
            buildLabel = is42 ? "42" : "41";
        }

        int maxConn = SteamSessionManager.getMaxConnections(appCtx);
        boolean verify = SteamSessionManager.getVerifyFiles(appCtx);

        SteamDownloadState st = SteamDownloadState.get();
        SteamGameDownloader dl = new SteamGameDownloader(username, password, refreshToken, remember,
                manifestId, branch, buildLabel, maxConn, verify, st);
        if (macosInstanceName != null) {
            com.zomdroid.game.GameInstance instance = com.zomdroid.game.GameInstanceManager.requireSingleton()
                    .getInstanceByName(macosInstanceName);
            if (instance == null || !instance.isBuild4220Plus()) {
                Toast.makeText(appCtx, R.string.macos_libs_invalid_instance, Toast.LENGTH_LONG).show();
                return;
            }
            dl = SteamGameDownloader.macos(username, password, refreshToken, remember,
                    macosInstanceName, new java.io.File(instance.getGamePath()), st);
        }
        if (mpInstanceName != null) {
            com.zomdroid.game.GameInstance instance = com.zomdroid.game.GameInstanceManager.requireSingleton()
                    .getInstanceByName(mpInstanceName);
            if (instance == null || !"Build 41".equals(instance.getPresetName())) {
                Toast.makeText(appCtx, R.string.mp_libs_invalid_instance, Toast.LENGTH_LONG).show();
                return;
            }
            dl = SteamGameDownloader.libraries(username, password, refreshToken, remember,
                    new java.io.File(instance.getGamePath()), com.zomdroid.steam.LibraryPack.B41_MULTIPLAYER, st);
        }
        Thread th = new Thread(dl, "zd-download");
        st.begin(appCtx);
        st.setActive(dl, th);
        beginUi();
        th.start();
    }

    private void beginUi() {
        setControlsEnabled(false);
        btnCancel.setVisibility(View.VISIBLE);
        tvStatus.setText("");
        progress.setVisibility(View.VISIBLE);
        progress.setIndeterminate(true);
        if (tvDlFile != null) {
            tvDlFile.setText("");
            tvDlFile.setVisibility(View.VISIBLE);
        }
        if (tvDlSpeed != null) {
            tvDlSpeed.setText("");
            tvDlSpeed.setVisibility(View.VISIBLE);
        }
        appendLog("Connecting to Steam…");
    }

    private void confirmCancel() {
        new AlertDialog.Builder(requireActivity())
                .setMessage(R.string.steam_dl_cancel_confirm)
                .setPositiveButton(R.string.steam_dl_cancel, (d, w) -> SteamDownloadState.get().cancel())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void setControlsEnabled(boolean enabled) {
        if (etUser != null) etUser.setEnabled(enabled);
        if (etPass != null) etPass.setEnabled(enabled);
        if (cbRememberSession != null) cbRememberSession.setEnabled(enabled);
        if (btnSteamLogout != null) btnSteamLogout.setEnabled(enabled);
        if (etManifest != null) etManifest.setEnabled(enabled);
        if (sliderConnections != null) sliderConnections.setEnabled(enabled);
        if (cbVerifyFiles != null) cbVerifyFiles.setEnabled(enabled);
        if (btnStart != null) btnStart.setEnabled(enabled);
    }

    // ---- All-files access (writes into the public Downloads/zomdroid folder) ----
    private boolean ensureAllFilesAccess() {
        if (Environment.isExternalStorageManager()) return true;
        new AlertDialog.Builder(requireActivity())
                .setTitle(R.string.steam_dl_storage_title)
                .setMessage(R.string.steam_dl_storage_message)
                .setPositiveButton(R.string.steam_dl_grant, (d, w) -> requestAllFilesAccess())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
        return false;
    }

    private void requestAllFilesAccess() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + requireContext().getPackageName())));
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
        }
    }

    // ---- SteamDownloadState.View (always on main thread) ----
    @Override
    public void onSessionChanged() {
        updateLoginUi();
    }

    @Override
    public void onLog(CharSequence fullLog) {
        if (tvStatus == null) return;
        tvStatus.setText(fullLog);
        scrollLogToBottom();
    }

    @Override
    public void onPercent(int p, boolean indet) {
        if (progress == null) return;
        progress.setVisibility(View.VISIBLE);
        progress.setIndeterminate(indet);
        if (!indet) progress.setProgress(p);
    }

    @Override
    public void onFileProgress(String fileName, long speedBytesPerSec, long downloadedBytes, long totalBytes, int percent) {
        if (tvDlFile != null) {
            if (fileName != null && !fileName.isEmpty()) {
                tvDlFile.setVisibility(View.VISIBLE);
                tvDlFile.setText(getString(R.string.steam_dl_file_downloading, fileName));
            } else {
                tvDlFile.setVisibility(View.GONE);
            }
        }
        if (tvDlSpeed != null) {
            if (totalBytes > 0) {
                tvDlSpeed.setVisibility(View.VISIBLE);
                double speedMb = speedBytesPerSec / (1024.0 * 1024.0);
                String dlStr = formatBytes(downloadedBytes);
                String totalStr = formatBytes(totalBytes);
                tvDlSpeed.setText(String.format(java.util.Locale.US, "%.1f MB/s • %s / %s", speedMb, dlStr, totalStr));
            }
        }
        if (progress != null && percent >= 0) {
            progress.setVisibility(View.VISIBLE);
            progress.setIndeterminate(false);
            progress.setProgress(Math.max(0, Math.min(100, percent)));
        }
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024L * 1024L) {
            return String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0);
        } else if (bytes < 1024L * 1024L * 1024L) {
            return String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
        } else {
            return String.format(java.util.Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
        }
    }

    @Override
    public void onFinished(String message) {
        setControlsEnabled(true);
        if (progress != null) progress.setVisibility(View.GONE);
        if (btnCancel != null) btnCancel.setVisibility(View.GONE);
        if (tvDlFile != null) tvDlFile.setVisibility(View.GONE);
        if (tvDlSpeed != null) tvDlSpeed.setVisibility(View.GONE);
        Toast.makeText(appCtx, message, Toast.LENGTH_LONG).show();
    }

    @Override
    public CompletableFuture<String> requestSteamGuardCode(boolean prevWrong, String email) {
        final CompletableFuture<String> fut = new CompletableFuture<>();
        if (!isAdded()) { fut.complete(""); return fut; }
        final EditText etCode = new EditText(requireActivity());
        etCode.setHint(email != null
                ? getString(R.string.steam_dl_guard_email, email)
                : getString(R.string.steam_dl_guard_hint));
        etCode.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        new AlertDialog.Builder(requireActivity())
                .setTitle(prevWrong ? R.string.steam_dl_guard_retry : R.string.steam_dl_guard_title)
                .setView(etCode)
                .setCancelable(false)
                .setPositiveButton(android.R.string.ok,
                        (d, w) -> fut.complete(etCode.getText().toString().trim()))
                .show();
        return fut;
    }

    private void appendLog(String line) {
        if (tvStatus == null) return;
        tvStatus.append(line + "\n");
        scrollLogToBottom();
    }

    private void scrollLogToBottom() {
        if (tvStatus == null) return;
        android.text.Layout layout = tvStatus.getLayout();
        if (layout != null) {
            int y = layout.getLineTop(tvStatus.getLineCount()) - tvStatus.getHeight();
            tvStatus.scrollTo(0, Math.max(0, y));
        }
    }

    private static String text(EditText e) {
        return e.getText() == null ? "" : e.getText().toString().trim();
    }
}
