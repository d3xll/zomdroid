package com.zomdroid.steam.workshop;

import java.util.List;

public class WorkshopPage {
    private final List<WorkshopItem> items;
    private final int page;
    private final boolean hasNextPage;

    public WorkshopPage(List<WorkshopItem> items, int page, boolean hasNextPage) {
        this.items = items;
        this.page = page;
        this.hasNextPage = hasNextPage;
    }

    public List<WorkshopItem> getItems() {
        return items;
    }

    public int getPage() {
        return page;
    }

    public boolean hasNextPage() {
        return hasNextPage;
    }
}
