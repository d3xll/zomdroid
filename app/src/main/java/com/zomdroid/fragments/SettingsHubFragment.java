package com.zomdroid.fragments;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;

import com.zomdroid.BuildConfig;
import com.zomdroid.LauncherActivity;
import com.zomdroid.R;
import com.zomdroid.databinding.FragmentSettingsHubBinding;

public class SettingsHubFragment extends Fragment {
    private FragmentSettingsHubBinding binding;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentSettingsHubBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        LauncherActivity activity = (LauncherActivity) getActivity();

        // Preferences
        binding.cardAppSettings.setOnClickListener(v ->
                Navigation.findNavController(v).navigate(R.id.action_open_settings_fragment));

        binding.cardGameSettings.setOnClickListener(v ->
                Navigation.findNavController(v).navigate(R.id.action_game_settings));

        binding.cardOptimization.setOnClickListener(v ->
                Navigation.findNavController(v).navigate(R.id.action_open_optimization));

        // Tools & Diagnostics
        binding.cardDriver.setOnClickListener(v ->
                Navigation.findNavController(v).navigate(R.id.action_install_driver));

        binding.cardLogs.setOnClickListener(v ->
                Navigation.findNavController(v).navigate(R.id.action_export_log));

        binding.cardBugReport.setOnClickListener(v -> {
            if (activity != null) {
                activity.sendBugReport();
            }
        });

        // About & Community
        binding.cardWiki.setOnClickListener(v ->
                Navigation.findNavController(v).navigate(R.id.action_open_wiki_fragment));

        binding.tvVersionSubtitle.setText(getString(R.string.nav_menu_version) + " \u2022 v" + BuildConfig.VERSION_NAME);

        binding.cardGithub.setOnClickListener(v -> {
            if (activity != null) {
                activity.checkForUpdate();
            }
        });

        binding.cardDonate.setOnClickListener(v -> {
            if (activity != null) {
                activity.showDonateDialog();
            }
        });

        binding.cardReddit.setOnClickListener(v -> {
            if (activity != null) {
                activity.showRedditDialog();
            }
        });

        binding.cardRimdroid.setOnClickListener(v -> {
            if (activity != null) {
                activity.showRimDroidDialog();
            }
        });

        refreshBadge();
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshBadge();
    }

    private void refreshBadge() {
        if (binding == null) return;
        LauncherActivity activity = (LauncherActivity) getActivity();
        if (activity != null) {
            binding.updateBadgeSettings.setVisibility(
                    activity.updateAvailable() ? View.VISIBLE : View.GONE);
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
