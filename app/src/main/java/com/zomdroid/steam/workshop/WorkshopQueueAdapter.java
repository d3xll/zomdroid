package com.zomdroid.steam.workshop;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.zomdroid.databinding.ItemWorkshopQueueBinding;

import java.util.ArrayList;
import java.util.List;

public class WorkshopQueueAdapter extends RecyclerView.Adapter<WorkshopQueueAdapter.QueueViewHolder> {

    public interface OnRemoveClickListener {
        void onRemove(long id);
    }

    private final List<WorkshopDownloadManager.DownloadItem> items = new ArrayList<>();
    private final OnRemoveClickListener removeListener;

    public WorkshopQueueAdapter(OnRemoveClickListener removeListener) {
        this.removeListener = removeListener;
    }

    public void setItems(List<WorkshopDownloadManager.DownloadItem> newItems) {
        items.clear();
        if (newItems != null) {
            items.addAll(newItems);
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public QueueViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemWorkshopQueueBinding binding = ItemWorkshopQueueBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new QueueViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull QueueViewHolder holder, int position) {
        WorkshopDownloadManager.DownloadItem item = items.get(position);
        holder.binding.tvQueueModTitle.setText(item.title);
        holder.binding.tvQueueModId.setText("ID: " + item.id);
        holder.binding.btnQueueRemove.setOnClickListener(v -> {
            if (removeListener != null) {
                removeListener.onRemove(item.id);
            }
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class QueueViewHolder extends RecyclerView.ViewHolder {
        final ItemWorkshopQueueBinding binding;

        QueueViewHolder(@NonNull ItemWorkshopQueueBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
