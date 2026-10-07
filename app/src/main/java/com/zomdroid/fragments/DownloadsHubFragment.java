package com.zomdroid.fragments;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;

import com.zomdroid.R;
import com.zomdroid.databinding.FragmentDownloadsHubBinding;

public class DownloadsHubFragment extends Fragment {
    private FragmentDownloadsHubBinding binding;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentDownloadsHubBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        binding.cardDownloadSteam.setOnClickListener(v ->
                Navigation.findNavController(v).navigate(R.id.action_download_steam));

        binding.cardSteamWorkshop.setOnClickListener(v ->
                Navigation.findNavController(v).navigate(R.id.action_steam_workshop));

        binding.cardDownloadGog.setOnClickListener(v ->
                Navigation.findNavController(v).navigate(R.id.action_download_gog));

        binding.cardInstallMods.setOnClickListener(v ->
                Navigation.findNavController(v).navigate(R.id.action_open_install_mod));

        binding.cardInstallSaves.setOnClickListener(v ->
                Navigation.findNavController(v).navigate(R.id.action_install_saves));
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
