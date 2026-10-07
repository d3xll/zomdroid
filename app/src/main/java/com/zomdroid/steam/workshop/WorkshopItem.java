package com.zomdroid.steam.workshop;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class WorkshopItem {
    private final long publishedFileId;
    private final int appId;
    private String title;
    private String author;
    private String previewUrl;
    private String description;
    private Long fileSize;
    private Long timeUpdated;
    private Long subscriptions;
    private Long favorites;
    private Long views;
    private List<String> tags;

    public WorkshopItem(long publishedFileId, int appId, String title, String author,
                        String previewUrl, String description) {
        this.publishedFileId = publishedFileId;
        this.appId = appId;
        this.title = title != null ? title : "";
        this.author = author != null ? author : "";
        this.previewUrl = previewUrl != null ? previewUrl : "";
        this.description = description != null ? description : "";
        this.tags = new ArrayList<>();
    }

    public long getPublishedFileId() {
        return publishedFileId;
    }

    public int getAppId() {
        return appId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title != null ? title : "";
    }

    public String getAuthor() {
        return author;
    }

    public void setAuthor(String author) {
        this.author = author != null ? author : "";
    }

    public String getPreviewUrl() {
        return previewUrl;
    }

    public void setPreviewUrl(String previewUrl) {
        this.previewUrl = previewUrl != null ? previewUrl : "";
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description != null ? description : "";
    }

    public Long getFileSize() {
        return fileSize;
    }

    public void setFileSize(Long fileSize) {
        this.fileSize = fileSize;
    }

    public Long getTimeUpdated() {
        return timeUpdated;
    }

    public void setTimeUpdated(Long timeUpdated) {
        this.timeUpdated = timeUpdated;
    }

    public Long getSubscriptions() {
        return subscriptions;
    }

    public void setSubscriptions(Long subscriptions) {
        this.subscriptions = subscriptions;
    }

    public Long getFavorites() {
        return favorites;
    }

    public void setFavorites(Long favorites) {
        this.favorites = favorites;
    }

    public Long getViews() {
        return views;
    }

    public void setViews(Long views) {
        this.views = views;
    }

    public List<String> getTags() {
        return tags;
    }

    public void setTags(List<String> tags) {
        this.tags = tags != null ? tags : new ArrayList<>();
    }

    public String getWorkshopUrl() {
        return "https://steamcommunity.com/sharedfiles/filedetails/?id=" + publishedFileId;
    }

    public String getFormattedSize() {
        if (fileSize == null || fileSize <= 0) return null;
        if (fileSize < 1024L * 1024L) {
            return String.format(Locale.US, "%.1f KB", fileSize / 1024.0);
        } else if (fileSize < 1024L * 1024L * 1024L) {
            return String.format(Locale.US, "%.1f MB", fileSize / (1024.0 * 1024.0));
        } else {
            return String.format(Locale.US, "%.2f GB", fileSize / (1024.0 * 1024.0 * 1024.0));
        }
    }

    public String getFormattedUpdatedDate() {
        if (timeUpdated == null || timeUpdated <= 0) return null;
        try {
            Date date = new Date(timeUpdated * 1000L);
            return DateFormat.getDateInstance(DateFormat.MEDIUM).format(date);
        } catch (Exception e) {
            return null;
        }
    }
}
