package com.zomdroid.fragments;

import androidx.appcompat.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.method.ScrollingMovementMethod;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.MenuHost;
import androidx.core.view.MenuProvider;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Lifecycle;
import androidx.navigation.Navigation;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.zomdroid.R;
import com.zomdroid.databinding.DialogWorkshopDirectDownloadBinding;
import com.zomdroid.databinding.DialogWorkshopDownloadsBinding;
import com.zomdroid.databinding.DialogWorkshopModDetailBinding;
import com.zomdroid.databinding.FragmentSteamWorkshopBinding;
import com.zomdroid.steam.SteamDownloadState;
import com.zomdroid.steam.SteamSessionManager;
import com.zomdroid.steam.workshop.SteamHtmlDecoder;
import com.zomdroid.steam.workshop.WorkshopDownloadManager;
import com.zomdroid.steam.workshop.WorkshopImageLoader;
import com.zomdroid.steam.workshop.WorkshopItem;
import com.zomdroid.steam.workshop.WorkshopModAdapter;
import com.zomdroid.steam.workshop.WorkshopPage;
import com.zomdroid.steam.workshop.WorkshopQueueAdapter;
import com.zomdroid.steam.workshop.WorkshopRepository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SteamWorkshopFragment extends Fragment
        implements SteamDownloadState.View, WorkshopModAdapter.OnModClickListener, WorkshopDownloadManager.Listener {

    private FragmentSteamWorkshopBinding binding;
    private WorkshopModAdapter adapter;
    private final WorkshopRepository repository = new WorkshopRepository();
    private final ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private Context appCtx;
    private String currentSearchText = "";
    private String currentSort = "trend";
    private int currentTimeWindow = 7; // 1 week
    private int currentPage = 1;
    private boolean hasNextPage = false;
    private boolean isLoading = false;
    private boolean isLogExpanded = false;

    // Active downloads bottom sheet dialog
    private BottomSheetDialog activeDownloadsDialog;
    private DialogWorkshopDownloadsBinding activeDownloadsBinding;
    private WorkshopQueueAdapter dialogQueueAdapter;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentSteamWorkshopBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        appCtx = requireContext().getApplicationContext();

        WorkshopDownloadManager.getInstance().init(appCtx);
        WorkshopDownloadManager.getInstance().addListener(this);

        setupRecyclerView();
        setupSearchAndFilters();
        setupDownloadCard();

        MenuHost menuHost = requireActivity();
        menuHost.addMenuProvider(new MenuProvider() {
            @Override
            public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater menuInflater) {
                menuInflater.inflate(R.menu.menu_steam_workshop, menu);
            }

            @Override
            public boolean onMenuItemSelected(@NonNull MenuItem menuItem) {
                if (menuItem.getItemId() == R.id.action_workshop_downloads) {
                    showActiveDownloadsDialog();
                    return true;
                }
                return false;
            }
        }, getViewLifecycleOwner(), Lifecycle.State.RESUMED);

        // Connect to active download state
        SteamDownloadState.get().setView(this);
        updateDownloadCardVisibility();

        // Load initial popular mods
        loadWorkshopMods(1, false);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        WorkshopDownloadManager.getInstance().removeListener(this);
        SteamDownloadState.get().clearView(this);
        if (activeDownloadsDialog != null && activeDownloadsDialog.isShowing()) {
            activeDownloadsDialog.dismiss();
        }
        activeDownloadsDialog = null;
        activeDownloadsBinding = null;
        dialogQueueAdapter = null;
        binding = null;
    }

    private void setupRecyclerView() {
        adapter = new WorkshopModAdapter(this);
        LinearLayoutManager layoutManager = new LinearLayoutManager(requireContext());
        binding.rvWorkshopMods.setLayoutManager(layoutManager);
        binding.rvWorkshopMods.setAdapter(adapter);

        binding.rvWorkshopMods.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                super.onScrolled(recyclerView, dx, dy);
                if (dy > 0 && !isLoading && hasNextPage) {
                    int visibleItemCount = layoutManager.getChildCount();
                    int totalItemCount = layoutManager.getItemCount();
                    int firstVisibleItemPosition = layoutManager.findFirstVisibleItemPosition();

                    if ((visibleItemCount + firstVisibleItemPosition) >= totalItemCount - 4
                            && firstVisibleItemPosition >= 0) {
                        loadWorkshopMods(currentPage + 1, true);
                    }
                }
            }
        });

        binding.btnRetry.setOnClickListener(v -> loadWorkshopMods(1, false));
    }

    private void setupSearchAndFilters() {
        binding.etWorkshopSearch.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                    (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN)) {
                performSearch();
                return true;
            }
            return false;
        });

        binding.btnDoSearch.setOnClickListener(v -> performSearch());

        binding.btnClearSearch.setOnClickListener(v -> {
            binding.etWorkshopSearch.setText("");
            performSearch();
        });

        binding.etWorkshopSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                binding.btnClearSearch.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);
            }
        });

        binding.btnDirectDownload.setOnClickListener(v -> showDirectDownloadDialog());

        // Sort chips
        binding.chipgroupSort.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.isEmpty()) return;
            int id = checkedIds.get(0);
            if (id == R.id.chip_sort_popular) {
                currentSort = "trend";
                binding.scrollTimeWindow.setVisibility(View.VISIBLE);
            } else if (id == R.id.chip_sort_recent) {
                currentSort = "mostrecent";
                binding.scrollTimeWindow.setVisibility(View.GONE);
            } else if (id == R.id.chip_sort_updated) {
                currentSort = "lastupdated";
                binding.scrollTimeWindow.setVisibility(View.GONE);
            } else if (id == R.id.chip_sort_subscribed) {
                currentSort = "totaluniquesubscribers";
                binding.scrollTimeWindow.setVisibility(View.GONE);
            }
            loadWorkshopMods(1, false);
        });

        // Time window chips
        binding.chipgroupTime.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.isEmpty()) return;
            int id = checkedIds.get(0);
            if (id == R.id.chip_time_today) currentTimeWindow = 1;
            else if (id == R.id.chip_time_week) currentTimeWindow = 7;
            else if (id == R.id.chip_time_month) currentTimeWindow = 30;
            else if (id == R.id.chip_time_three_months) currentTimeWindow = 90;
            else if (id == R.id.chip_time_six_months) currentTimeWindow = 180;
            else if (id == R.id.chip_time_year) currentTimeWindow = 365;
            else if (id == R.id.chip_time_all) currentTimeWindow = -1;

            if ("trend".equals(currentSort)) {
                loadWorkshopMods(1, false);
            }
        });
    }

    private void performSearch() {
        if (binding == null) return;
        currentSearchText = binding.etWorkshopSearch.getText() != null
                ? binding.etWorkshopSearch.getText().toString().trim()
                : "";
        loadWorkshopMods(1, false);
    }

    private void loadWorkshopMods(int page, boolean append) {
        if (isLoading) return;
        isLoading = true;

        if (!append) {
            binding.progressLoading.setVisibility(View.VISIBLE);
            binding.layoutEmptyState.setVisibility(View.GONE);
        }

        final String query = currentSearchText;
        final String sort = currentSort;
        final int timeDays = currentTimeWindow;

        backgroundExecutor.execute(() -> {
            try {
                WorkshopPage resultPage = repository.browseWorkshop(query, sort, timeDays, page);
                mainHandler.post(() -> {
                    if (binding == null) return;
                    isLoading = false;
                    binding.progressLoading.setVisibility(View.GONE);
                    currentPage = resultPage.getPage();
                    hasNextPage = resultPage.hasNextPage();

                    List<WorkshopItem> list = resultPage.getItems();
                    if (append) {
                        adapter.addItems(list);
                    } else {
                        adapter.setItems(list);
                        if (list.isEmpty()) {
                            binding.layoutEmptyState.setVisibility(View.VISIBLE);
                            binding.tvEmptyTitle.setText(R.string.workshop_empty);
                            binding.tvEmptyMessage.setText(R.string.workshop_empty_hint);
                        } else {
                            binding.layoutEmptyState.setVisibility(View.GONE);
                        }
                    }
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    if (binding == null) return;
                    isLoading = false;
                    binding.progressLoading.setVisibility(View.GONE);
                    if (!append) {
                        adapter.setItems(Collections.emptyList());
                        binding.layoutEmptyState.setVisibility(View.VISIBLE);
                        binding.tvEmptyTitle.setText(R.string.workshop_error);
                        binding.tvEmptyMessage.setText(e.getLocalizedMessage() != null ? e.getLocalizedMessage() : "");
                    } else {
                        Toast.makeText(requireContext(), R.string.workshop_error, Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }

    private void setupDownloadCard() {
        binding.tvWorkshopDlLog.setMovementMethod(new ScrollingMovementMethod());
        binding.btnToggleLog.setOnClickListener(v -> {
            isLogExpanded = !isLogExpanded;
            binding.tvWorkshopDlLog.setVisibility(isLogExpanded ? View.VISIBLE : View.GONE);
            binding.btnToggleLog.setText(isLogExpanded ? "Hide Log" : "Log Console");
        });

        binding.btnCardQueue.setOnClickListener(v -> showActiveDownloadsDialog());

        binding.btnWorkshopDlCancel.setOnClickListener(v -> confirmCancel());
    }

    private void confirmCancel() {
        int queueCount = WorkshopDownloadManager.getInstance().getQueueCount();
        if (queueCount > 0) {
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.steam_dl_cancel_confirm)
                    .setMessage(R.string.workshop_cancel_queue_confirm)
                    .setPositiveButton(R.string.workshop_cancel_entire_queue, (d, w) -> {
                        if (binding != null) {
                            binding.tvWorkshopDlTitle.setText(R.string.workshop_download_cancelled);
                            binding.tvWorkshopDlFile.setVisibility(View.GONE);
                            binding.cardDownloadStatus.setVisibility(View.GONE);
                        }
                        WorkshopDownloadManager.getInstance().cancelAll();
                    })
                    .setNeutralButton(R.string.workshop_cancel_only_current, (d, w) -> {
                        if (binding != null) {
                            binding.tvWorkshopDlTitle.setText(R.string.workshop_download_cancelled);
                        }
                        WorkshopDownloadManager.getInstance().cancelCurrent();
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        } else {
            new AlertDialog.Builder(requireActivity())
                    .setTitle(R.string.steam_dl_cancel_confirm)
                    .setMessage(R.string.workshop_cancel_mod_confirm)
                    .setPositiveButton(R.string.steam_dl_cancel, (d, w) -> {
                        if (binding != null) {
                            binding.tvWorkshopDlTitle.setText(R.string.workshop_download_cancelled);
                            binding.tvWorkshopDlFile.setVisibility(View.GONE);
                            binding.cardDownloadStatus.setVisibility(View.GONE);
                        }
                        WorkshopDownloadManager.getInstance().cancelAll();
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        }
    }

    private void updateDownloadCardVisibility() {
        if (binding == null) return;
        boolean downloading = SteamDownloadState.get().isDownloading() || WorkshopDownloadManager.getInstance().isDownloading();
        binding.cardDownloadStatus.setVisibility(downloading ? View.VISIBLE : View.GONE);
        if (downloading) {
            WorkshopDownloadManager.DownloadItem current = WorkshopDownloadManager.getInstance().getCurrentItem();
            int queueCount = WorkshopDownloadManager.getInstance().getQueueCount();
            String title = current != null ? current.title : getString(R.string.workshop_downloading);
            if (queueCount > 0) {
                title += " " + getString(R.string.workshop_queue_more, queueCount);
            }
            binding.tvWorkshopDlTitle.setText(title);

            String curFile = SteamDownloadState.get().getCurrentFile();
            if (curFile != null && !curFile.isEmpty()) {
                binding.tvWorkshopDlFile.setVisibility(View.VISIBLE);
                binding.tvWorkshopDlFile.setText(getString(R.string.steam_dl_file_downloading, curFile));
            } else {
                binding.tvWorkshopDlFile.setVisibility(View.GONE);
            }

            onPercent(SteamDownloadState.get().getPercent(), SteamDownloadState.get().isIndeterminate());
            binding.tvWorkshopDlLog.setText(SteamDownloadState.get().getLog());
        } else {
            binding.progressWorkshopDl.setVisibility(View.GONE);
            binding.tvWorkshopDlSpeed.setText("");
            binding.tvWorkshopDlTitle.setText("");
            binding.tvWorkshopDlFile.setVisibility(View.GONE);
        }
    }

    @Override
    public void onQueueUpdated() {
        updateDownloadCardVisibility();
        refreshActiveDownloadsDialog();
    }

    private void showActiveDownloadsDialog() {
        if (activeDownloadsDialog != null && activeDownloadsDialog.isShowing()) {
            activeDownloadsDialog.dismiss();
        }

        activeDownloadsBinding = DialogWorkshopDownloadsBinding.inflate(getLayoutInflater());
        activeDownloadsDialog = new BottomSheetDialog(requireContext());
        activeDownloadsDialog.setContentView(activeDownloadsBinding.getRoot());

        dialogQueueAdapter = new WorkshopQueueAdapter(id -> {
            WorkshopDownloadManager.getInstance().removeFromQueue(id);
            refreshActiveDownloadsDialog();
        });

        activeDownloadsBinding.rvDialogQueueItems.setLayoutManager(new LinearLayoutManager(requireContext()));
        activeDownloadsBinding.rvDialogQueueItems.setAdapter(dialogQueueAdapter);

        // Threads slider setup
        int initialConn = SteamSessionManager.getMaxConnections(appCtx);
        activeDownloadsBinding.tvDialogConnectionsTitle.setText(
                getString(R.string.steam_dl_connections_title, initialConn));
        activeDownloadsBinding.sliderDialogConnections.setValue(initialConn);
        activeDownloadsBinding.sliderDialogConnections.addOnChangeListener((slider, value, fromUser) -> {
            int count = (int) value;
            SteamSessionManager.setMaxConnections(appCtx, count);
            activeDownloadsBinding.tvDialogConnectionsTitle.setText(
                    getString(R.string.steam_dl_connections_title, count));
        });

        // Cancel buttons
        activeDownloadsBinding.btnDialogCancelCurrent.setOnClickListener(v -> {
            WorkshopDownloadManager.getInstance().cancelCurrent();
            refreshActiveDownloadsDialog();
        });

        activeDownloadsBinding.btnDialogCancelAll.setOnClickListener(v -> {
            WorkshopDownloadManager.getInstance().cancelAll();
            if (binding != null) binding.cardDownloadStatus.setVisibility(View.GONE);
            if (activeDownloadsDialog != null && activeDownloadsDialog.isShowing()) {
                activeDownloadsDialog.dismiss();
            }
        });

        activeDownloadsBinding.btnDialogCloseIcon.setOnClickListener(v -> activeDownloadsDialog.dismiss());
        activeDownloadsBinding.btnDialogClose.setOnClickListener(v -> activeDownloadsDialog.dismiss());

        // Refresh views NOW before show()
        refreshActiveDownloadsDialog();

        activeDownloadsDialog.show();
    }

    private void refreshActiveDownloadsDialog() {
        if (activeDownloadsBinding == null) {
            return;
        }

        boolean isDownloading = SteamDownloadState.get().isDownloading() || WorkshopDownloadManager.getInstance().isDownloading();
        WorkshopDownloadManager.DownloadItem current = WorkshopDownloadManager.getInstance().getCurrentItem();
        List<WorkshopDownloadManager.DownloadItem> queue = WorkshopDownloadManager.getInstance().getQueue();

        if (!isDownloading && queue.isEmpty()) {
            activeDownloadsBinding.layoutDialogQueueEmpty.setVisibility(View.VISIBLE);
            activeDownloadsBinding.cardDialogCurrentDownload.setVisibility(View.GONE);
            activeDownloadsBinding.layoutDialogQueueSection.setVisibility(View.GONE);
            activeDownloadsBinding.btnDialogCancelAll.setVisibility(View.GONE);
            return;
        }

        activeDownloadsBinding.layoutDialogQueueEmpty.setVisibility(View.GONE);
        activeDownloadsBinding.btnDialogCancelAll.setVisibility(View.VISIBLE);

        // Active card
        if (isDownloading && current != null) {
            activeDownloadsBinding.cardDialogCurrentDownload.setVisibility(View.VISIBLE);
            activeDownloadsBinding.tvDialogCurrentTitle.setText(current.title);
            activeDownloadsBinding.tvDialogCurrentId.setText("ID: " + current.id);

            String curFile = SteamDownloadState.get().getCurrentFile();
            if (curFile != null && !curFile.isEmpty()) {
                activeDownloadsBinding.tvDialogCurrentFile.setVisibility(View.VISIBLE);
                activeDownloadsBinding.tvDialogCurrentFile.setText(getString(R.string.steam_dl_file_downloading, curFile));
            } else {
                activeDownloadsBinding.tvDialogCurrentFile.setVisibility(View.GONE);
            }

            long speed = SteamDownloadState.get().getCurrentSpeed();
            long dlBytes = SteamDownloadState.get().getDownloadedBytes();
            long totBytes = SteamDownloadState.get().getTotalBytes();
            int pct = SteamDownloadState.get().getPercent();

            if (totBytes > 0) {
                double speedMb = speed / (1024.0 * 1024.0);
                String dlStr = formatBytes(dlBytes);
                String totStr = formatBytes(totBytes);
                activeDownloadsBinding.tvDialogCurrentSpeed.setText(
                        String.format(Locale.US, "%.1f MB/s • %s / %s (%d%%)", speedMb, dlStr, totStr, pct)
                );
            } else {
                activeDownloadsBinding.tvDialogCurrentSpeed.setText(R.string.workshop_downloading);
            }

            activeDownloadsBinding.progressDialogCurrent.setIndeterminate(SteamDownloadState.get().isIndeterminate());
            if (pct >= 0) {
                activeDownloadsBinding.progressDialogCurrent.setProgress(pct);
            }
        } else {
            activeDownloadsBinding.cardDialogCurrentDownload.setVisibility(View.GONE);
        }

        // Queue section: GONE if empty, VISIBLE with formatted count if not empty
        if (!queue.isEmpty()) {
            activeDownloadsBinding.layoutDialogQueueSection.setVisibility(View.VISIBLE);
            activeDownloadsBinding.tvDialogQueueHeader.setText(
                    getString(R.string.workshop_queue_pending_heading, queue.size()));
            dialogQueueAdapter.setItems(queue);
        } else {
            activeDownloadsBinding.layoutDialogQueueSection.setVisibility(View.GONE);
        }
    }

    @Override
    public void onItemClick(WorkshopItem item) {
        showModDetailDialog(item);
    }

    @Override
    public void onDownloadClick(WorkshopItem item) {
        startDownloadWorkshopItem(item.getPublishedFileId(), item.getTitle());
    }

    private void showModDetailDialog(WorkshopItem item) {
        DialogWorkshopModDetailBinding dialogBinding =
                DialogWorkshopModDetailBinding.inflate(getLayoutInflater());

        dialogBinding.tvDetailTitle.setText(item.getTitle());
        String author = item.getAuthor();
        if (!author.isEmpty()) {
            dialogBinding.tvDetailAuthor.setText(getString(R.string.workshop_by_author, author)
                    + " • ID: " + item.getPublishedFileId());
        } else {
            dialogBinding.tvDetailAuthor.setText("ID: " + item.getPublishedFileId());
        }

        String size = item.getFormattedSize();
        if (size != null) {
            dialogBinding.chipDetailSize.setVisibility(View.VISIBLE);
            dialogBinding.chipDetailSize.setText(size);
        }

        if (item.getSubscriptions() != null && item.getSubscriptions() > 0) {
            dialogBinding.chipDetailSubs.setVisibility(View.VISIBLE);
            dialogBinding.chipDetailSubs.setText(
                    getString(R.string.workshop_stat_subscribers, item.getSubscriptions())
            );
        }

        String updated = item.getFormattedUpdatedDate();
        if (updated != null) {
            dialogBinding.chipDetailUpdated.setVisibility(View.VISIBLE);
            dialogBinding.chipDetailUpdated.setText(
                    getString(R.string.workshop_stat_updated, updated)
            );
        }

        if (item.getTags() != null && !item.getTags().isEmpty()) {
            dialogBinding.tvDetailTags.setVisibility(View.VISIBLE);
            dialogBinding.tvDetailTags.setText(
                    getString(R.string.workshop_tags, String.join(", ", item.getTags()))
            );
        }

        String desc = item.getDescription();
        dialogBinding.tvDetailDescription.setText(
                desc != null && !desc.isEmpty()
                        ? SteamHtmlDecoder.decodeWorkshopDescription(desc)
                        : getString(R.string.workshop_empty)
        );

        WorkshopImageLoader.getInstance(requireContext())
                .load(item.getPreviewUrl(), dialogBinding.ivDetailPreview, R.drawable.ic_mods);

        AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext())
                .setView(dialogBinding.getRoot())
                .create();

        dialogBinding.btnDetailOpenSteam.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(item.getWorkshopUrl())));
            } catch (Exception e) {
                Toast.makeText(requireContext(), item.getWorkshopUrl(), Toast.LENGTH_SHORT).show();
            }
        });

        dialogBinding.btnDetailDownload.setOnClickListener(v -> {
            dialog.dismiss();
            startDownloadWorkshopItem(item.getPublishedFileId(), item.getTitle());
        });

        dialog.show();

        if (item.getSubscriptions() == null) {
            backgroundExecutor.execute(() -> {
                WorkshopItem fullItem = repository.getSingleItemDetail(item.getPublishedFileId());
                if (fullItem != null) {
                    mainHandler.post(() -> {
                        if (!dialog.isShowing()) return;
                        if (fullItem.getFormattedSize() != null) {
                            dialogBinding.chipDetailSize.setVisibility(View.VISIBLE);
                            dialogBinding.chipDetailSize.setText(fullItem.getFormattedSize());
                        }
                        if (fullItem.getSubscriptions() != null && fullItem.getSubscriptions() > 0) {
                            dialogBinding.chipDetailSubs.setVisibility(View.VISIBLE);
                            dialogBinding.chipDetailSubs.setText(
                                    getString(R.string.workshop_stat_subscribers, fullItem.getSubscriptions())
                            );
                        }
                        if (fullItem.getFormattedUpdatedDate() != null) {
                            dialogBinding.chipDetailUpdated.setVisibility(View.VISIBLE);
                            dialogBinding.chipDetailUpdated.setText(
                                    getString(R.string.workshop_stat_updated, fullItem.getFormattedUpdatedDate())
                            );
                        }
                        if (fullItem.getTags() != null && !fullItem.getTags().isEmpty()) {
                            dialogBinding.tvDetailTags.setVisibility(View.VISIBLE);
                            dialogBinding.tvDetailTags.setText(
                                    getString(R.string.workshop_tags, String.join(", ", fullItem.getTags()))
                            );
                        }
                        if (!fullItem.getDescription().isEmpty()) {
                            dialogBinding.tvDetailDescription.setText(
                                    SteamHtmlDecoder.decodeWorkshopDescription(fullItem.getDescription())
                            );
                        }
                    });
                }
            });
        }
    }

    private void showDirectDownloadDialog() {
        DialogWorkshopDirectDownloadBinding directBinding =
                DialogWorkshopDirectDownloadBinding.inflate(getLayoutInflater());

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.workshop_direct_dialog_title)
                .setView(directBinding.getRoot())
                .setPositiveButton(R.string.workshop_download, (d, which) -> {
                    String input = directBinding.etDirectModId.getText() != null
                            ? directBinding.etDirectModId.getText().toString().trim()
                            : "";
                    if (input.isEmpty()) {
                        Toast.makeText(requireContext(), R.string.steam_dl_mods_empty, Toast.LENGTH_SHORT).show();
                        return;
                    }

                    List<Long> ids = WorkshopRepository.parseAllPublishedFileIds(input);
                    if (ids.isEmpty()) {
                        Toast.makeText(requireContext(), R.string.steam_dl_mods_empty, Toast.LENGTH_SHORT).show();
                        return;
                    }

                    if (!ensureAllFilesAccess()) return;

                    List<WorkshopDownloadManager.DownloadItem> items = new ArrayList<>();
                    for (Long id : ids) {
                        items.add(new WorkshopDownloadManager.DownloadItem(id, "Workshop #" + id));
                    }
                    WorkshopDownloadManager.getInstance().enqueueMultiple(requireContext(), items);
                    if (ids.size() == 1) {
                        Toast.makeText(requireContext(), getString(R.string.workshop_download_started, ids.get(0)), Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(requireContext(), getString(R.string.workshop_items_enqueued, ids.size()), Toast.LENGTH_SHORT).show();
                    }
                    updateDownloadCardVisibility();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void startDownloadWorkshopItem(long publishedFileId, String title) {
        if (!ensureAllFilesAccess()) return;

        boolean wasDownloading = WorkshopDownloadManager.getInstance().isDownloading();
        WorkshopDownloadManager.getInstance().enqueue(requireContext(), publishedFileId, title);

        if (wasDownloading) {
            Toast.makeText(requireContext(), getString(R.string.workshop_added_to_queue, title), Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(requireContext(), getString(R.string.workshop_download_started, publishedFileId), Toast.LENGTH_SHORT).show();
        }

        updateDownloadCardVisibility();
    }

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

    // ---- SteamDownloadState.View callbacks ----

    @Override
    public void onLog(CharSequence fullLog) {
        if (binding == null) return;
        binding.tvWorkshopDlLog.setText(fullLog);
        android.text.Layout layout = binding.tvWorkshopDlLog.getLayout();
        if (layout != null) {
            int y = layout.getLineTop(binding.tvWorkshopDlLog.getLineCount()) - binding.tvWorkshopDlLog.getHeight();
            binding.tvWorkshopDlLog.scrollTo(0, Math.max(0, y));
        }
    }

    @Override
    public void onPercent(int percent, boolean indeterminate) {
        if (binding == null) return;
        binding.progressWorkshopDl.setVisibility(View.VISIBLE);
        binding.progressWorkshopDl.setIndeterminate(indeterminate);
        if (!indeterminate && percent >= 0) {
            binding.progressWorkshopDl.setProgress(percent);
        }
        if (activeDownloadsBinding != null) {
            activeDownloadsBinding.progressDialogCurrent.setIndeterminate(indeterminate);
            if (!indeterminate && percent >= 0) {
                activeDownloadsBinding.progressDialogCurrent.setProgress(percent);
            }
        }
    }

    @Override
    public void onFileProgress(String fileName, long speedBytesPerSec, long downloadedBytes, long totalBytes, int percent) {
        if (binding == null) return;
        binding.cardDownloadStatus.setVisibility(View.VISIBLE);

        WorkshopDownloadManager.DownloadItem current = WorkshopDownloadManager.getInstance().getCurrentItem();
        int queueCount = WorkshopDownloadManager.getInstance().getQueueCount();

        // Background pinned card
        String title = current != null ? current.title : getString(R.string.workshop_downloading);
        if (queueCount > 0) {
            title += " " + getString(R.string.workshop_queue_more, queueCount);
        }
        binding.tvWorkshopDlTitle.setText(title);

        if (fileName != null && !fileName.isEmpty()) {
            binding.tvWorkshopDlFile.setVisibility(View.VISIBLE);
            binding.tvWorkshopDlFile.setText(getString(R.string.steam_dl_file_downloading, fileName));
        } else {
            binding.tvWorkshopDlFile.setVisibility(View.GONE);
        }

        double speedMb = speedBytesPerSec / (1024.0 * 1024.0);
        String dlStr = formatBytes(downloadedBytes);
        String totalStr = formatBytes(totalBytes);
        String speedText = (totalBytes > 0)
                ? String.format(Locale.US, "%.1f MB/s • %s / %s (%d%%)", speedMb, dlStr, totalStr, percent)
                : String.format(Locale.US, "%.1f MB/s", speedMb);
        binding.tvWorkshopDlSpeed.setText(speedText);

        onPercent(percent, false);

        // Update BottomSheet views in real time if open
        if (activeDownloadsBinding != null) {
            if (current != null) {
                activeDownloadsBinding.tvDialogCurrentTitle.setText(current.title);
                activeDownloadsBinding.tvDialogCurrentId.setText("ID: " + current.id);
            }
            if (fileName != null && !fileName.isEmpty()) {
                activeDownloadsBinding.tvDialogCurrentFile.setVisibility(View.VISIBLE);
                activeDownloadsBinding.tvDialogCurrentFile.setText(getString(R.string.steam_dl_file_downloading, fileName));
            }
            activeDownloadsBinding.tvDialogCurrentSpeed.setText(speedText);
            activeDownloadsBinding.progressDialogCurrent.setIndeterminate(false);
            activeDownloadsBinding.progressDialogCurrent.setProgress(percent);
        }
    }

    @Override
    public void onFinished(String message) {
        if (binding == null) return;

        boolean moreInQueue = WorkshopDownloadManager.getInstance().getQueueCount() > 0;
        if (!moreInQueue && !WorkshopDownloadManager.getInstance().isDownloading()) {
            // Dismiss card completely on finish or cancel
            binding.cardDownloadStatus.setVisibility(View.GONE);
            binding.progressWorkshopDl.setVisibility(View.GONE);
            binding.tvWorkshopDlSpeed.setText("");
            binding.tvWorkshopDlTitle.setText("");
            binding.tvWorkshopDlFile.setVisibility(View.GONE);

            if (message != null && (message.contains("cancelled") || message.contains("Cancelled"))) {
                Toast.makeText(appCtx, R.string.workshop_download_cancelled, Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(appCtx, message, Toast.LENGTH_LONG).show();
            }

            // Prompt user if they want to install downloaded mods
            if (isAdded() && message != null && message.contains("Mods done")) {
                new MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.workshop_install_prompt_title)
                        .setMessage(R.string.workshop_install_prompt_message)
                        .setPositiveButton(R.string.workshop_go_install, (d, which) -> {
                            try {
                                Navigation.findNavController(binding.getRoot())
                                        .navigate(R.id.action_open_install_mod);
                            } catch (Exception ignored) {}
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            }
        } else {
            // Next item will start, update card
            updateDownloadCardVisibility();
        }

        refreshActiveDownloadsDialog();
    }

    @Override
    public CompletableFuture<String> requestSteamGuardCode(boolean previousWrong, String email) {
        CompletableFuture<String> fut = new CompletableFuture<>();
        fut.complete("");
        return fut;
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024L * 1024L) {
            return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        } else if (bytes < 1024L * 1024L * 1024L) {
            return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
        } else {
            return String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
        }
    }
}
