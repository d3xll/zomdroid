package com.zomdroid.steam.workshop;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.zomdroid.R;
import com.zomdroid.databinding.ItemWorkshopModBinding;

import java.util.ArrayList;
import java.util.List;

public class WorkshopModAdapter extends RecyclerView.Adapter<WorkshopModAdapter.ModViewHolder> {

    public interface OnModClickListener {
        void onItemClick(WorkshopItem item);
        void onDownloadClick(WorkshopItem item);
    }

    private final List<WorkshopItem> items = new ArrayList<>();
    private final OnModClickListener listener;

    public WorkshopModAdapter(OnModClickListener listener) {
        this.listener = listener;
    }

    public void setItems(List<WorkshopItem> newItems) {
        items.clear();
        if (newItems != null) {
            items.addAll(newItems);
        }
        notifyDataSetChanged();
    }

    public void addItems(List<WorkshopItem> moreItems) {
        if (moreItems == null || moreItems.isEmpty()) return;
        int startPos = items.size();
        items.addAll(moreItems);
        notifyItemRangeInserted(startPos, moreItems.size());
    }

    @NonNull
    @Override
    public ModViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemWorkshopModBinding binding = ItemWorkshopModBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false
        );
        return new ModViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull ModViewHolder holder, int position) {
        holder.bind(items.get(position));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    class ModViewHolder extends RecyclerView.ViewHolder {
        private final ItemWorkshopModBinding binding;

        ModViewHolder(ItemWorkshopModBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(WorkshopItem item) {
            binding.tvModTitle.setText(item.getTitle());

            String author = item.getAuthor();
            if (author.isEmpty()) {
                binding.tvModAuthor.setText("ID: " + item.getPublishedFileId());
            } else {
                binding.tvModAuthor.setText(
                        binding.getRoot().getContext().getString(R.string.workshop_by_author, author)
                );
            }

            String size = item.getFormattedSize();
            if (size != null && !size.isEmpty()) {
                binding.tvModSize.setVisibility(View.VISIBLE);
                binding.tvModSize.setText(size);
            } else {
                binding.tvModSize.setVisibility(View.GONE);
            }

            String desc = item.getDescription();
            if (desc != null && !desc.isEmpty()) {
                binding.tvModDescription.setVisibility(View.VISIBLE);
                binding.tvModDescription.setText(desc);
            } else {
                binding.tvModDescription.setVisibility(View.GONE);
            }

            WorkshopImageLoader.getInstance(binding.getRoot().getContext())
                    .load(item.getPreviewUrl(), binding.ivModPreview, R.drawable.ic_mods);

            binding.getRoot().setOnClickListener(v -> {
                if (listener != null) listener.onItemClick(item);
            });

            binding.btnModDownload.setOnClickListener(v -> {
                if (listener != null) listener.onDownloadClick(item);
            });
        }
    }
}
