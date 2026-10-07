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
import com.zomdroid.databinding.FragmentControlsHubBinding;

public class ControlsHubFragment extends Fragment {
    private FragmentControlsHubBinding binding;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentControlsHubBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        binding.cardControlsEditor.setOnClickListener(v ->
                Navigation.findNavController(v).navigate(R.id.action_open_controls_editor_launch));

        binding.cardGamepadMapper.setOnClickListener(v ->
                Navigation.findNavController(v).navigate(R.id.action_open_gamepad_mapper));

        binding.cardInstallControls.setOnClickListener(v ->
                Navigation.findNavController(v).navigate(R.id.action_install_controls));
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
