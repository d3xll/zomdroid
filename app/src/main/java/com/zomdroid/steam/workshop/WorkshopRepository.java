package com.zomdroid.steam.workshop;

import android.net.Uri;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class WorkshopRepository {
    private static final String TAG = "Zomdroid/WorkshopRepo";
    public static final int DEFAULT_APP_ID = 108600;

    private static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36";
    private static final String ACCEPT_HEADER =
            "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8";

    // Current Steam Community SSR data
    private static final Pattern VALVE_SSR_DATA_PATTERN = Pattern.compile(
            "<script type=\"application/json\" id=\"valve-ssr-data\"[^>]*>(.*?)</script>",
            Pattern.DOTALL
    );

    // Legacy HTML scraping patterns
    private static final Pattern ITEM_BLOCK_PATTERN = Pattern.compile(
            "<div\\b[^>]*class=\"workshopItem\"[^>]*>(.*?<div class=\"workshopItemAuthorName ellipsis\">.*?</div>.*?)</div>",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE
    );
    private static final Pattern ITEM_HEADER_PATTERN = Pattern.compile(
            "<a\\b[^>]*href=\"[^\"]*?id=(\\d+)[^\"]*\"[^>]*class=\"ugc\"[^>]*data-appid=\"(\\d+)\"[^>]*data-publishedfileid=\"\\d+\"[^>]*>",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE
    );
    private static final Pattern ITEM_PREVIEW_PATTERN = Pattern.compile(
            "class=\"workshopItemPreviewImage[^\"]*\"\\s+src=\"([^\"]+)\"",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE
    );
    private static final Pattern ITEM_TITLE_PATTERN = Pattern.compile(
            "class=\"workshopItemTitle ellipsis\">(.*?)</div>",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE
    );
    private static final Pattern ITEM_AUTHOR_PATTERN = Pattern.compile(
            "class=\"workshopItemAuthorName ellipsis\">.*?<a\\b[^>]*>(.*?)</a>",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE
    );
    private static final Pattern HOVER_PATTERN = Pattern.compile(
            "SharedFileBindMouseHover\\(\\s*\"sharedfile_(\\d+)\"\\s*,\\s*false\\s*,\\s*(\\{.*?\\})\\s*\\);",
            Pattern.DOTALL
    );
    private static final Pattern SSR_PREFIX_PATTERN = Pattern.compile(
            "window\\.SSR\\.renderContext\\s*=\\s*JSON\\.parse\\(\""
    );

    public WorkshopPage browseWorkshop(String searchText, String sortOption, int timeWindowDays, int page)
            throws Exception {
        String query = searchText != null ? searchText.trim() : "";
        Long directId = parsePublishedFileId(query);
        if (directId != null && directId > 0) {
            WorkshopItem single = getSingleItemDetail(directId);
            if (single != null) {
                return new WorkshopPage(Collections.singletonList(single), 1, false);
            }
        }

        StringBuilder urlBuilder = new StringBuilder("https://steamcommunity.com/workshop/browse/?");
        urlBuilder.append("appid=").append(DEFAULT_APP_ID);
        urlBuilder.append("&searchtext=").append(URLEncoder.encode(query, "UTF-8"));
        urlBuilder.append("&childpublishedfileid=0");
        urlBuilder.append("&browsesort=").append(sortOption != null ? sortOption : "trend");
        urlBuilder.append("&section=readytouseitems");
        urlBuilder.append("&actualsort=").append(sortOption != null ? sortOption : "trend");
        urlBuilder.append("&p=").append(page);
        urlBuilder.append("&numperpage=30");
        if ("trend".equalsIgnoreCase(sortOption) && timeWindowDays != 0) {
            urlBuilder.append("&days=").append(timeWindowDays);
        }
        urlBuilder.append("&l=").append(getSteamLanguage());

        URL url = new URL(urlBuilder.toString());
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", USER_AGENT);
        conn.setRequestProperty("Accept", ACCEPT_HEADER);
        conn.setConnectTimeout(12000);
        conn.setReadTimeout(18000);

        int responseCode = conn.getResponseCode();
        if (responseCode != HttpURLConnection.HTTP_OK) {
            throw new Exception("HTTP request failed with code: " + responseCode);
        }

        String payload = readStreamToString(conn.getInputStream());
        WorkshopPage resultPage = parseBrowseResponse(payload, page);

        // Enrich details only if needed (e.g. legacy HTML scrape missing file sizes)
        boolean needsEnrich = false;
        for (WorkshopItem it : resultPage.getItems()) {
            if (it.getFileSize() == null) {
                needsEnrich = true;
                break;
            }
        }
        if (needsEnrich && !resultPage.getItems().isEmpty()) {
            enrichItemsWithDetails(resultPage.getItems());
        }

        return resultPage;
    }

    private WorkshopPage parseBrowseResponse(String payload, int page) {
        // 1. First try modern Valve SSR Data script tag (<script type="application/json" id="valve-ssr-data">)
        WorkshopPage valveSsrPage = tryParseValveSsrData(payload, page);
        if (valveSsrPage != null && !valveSsrPage.getItems().isEmpty()) {
            return valveSsrPage;
        }

        // 2. Try window.SSR.renderContext
        WorkshopPage ssrPage = tryParseSsrRenderContext(payload, page);
        if (ssrPage != null && !ssrPage.getItems().isEmpty()) {
            return ssrPage;
        }

        // 3. Fallback to HTML scraping
        Map<Long, String> descriptions = new HashMap<>();
        Matcher hoverMatcher = HOVER_PATTERN.matcher(payload);
        while (hoverMatcher.find()) {
            try {
                long fileId = Long.parseLong(hoverMatcher.group(1));
                String jsonStr = hoverMatcher.group(2);
                JSONObject obj = new JSONObject(jsonStr);
                String desc = obj.optString("description", "");
                descriptions.put(fileId, SteamHtmlDecoder.stripTagsAndDecode(desc));
            } catch (Exception ignored) {}
        }

        List<WorkshopItem> items = new ArrayList<>();
        Matcher blockMatcher = ITEM_BLOCK_PATTERN.matcher(payload);
        while (blockMatcher.find()) {
            String block = blockMatcher.group(1);
            Matcher headerMatcher = ITEM_HEADER_PATTERN.matcher(block);
            if (!headerMatcher.find()) continue;

            long fileId;
            int appId;
            try {
                fileId = Long.parseLong(headerMatcher.group(1));
                appId = Integer.parseInt(headerMatcher.group(2));
            } catch (Exception e) {
                continue;
            }

            String previewUrl = "";
            Matcher previewMatcher = ITEM_PREVIEW_PATTERN.matcher(block);
            if (previewMatcher.find()) {
                previewUrl = previewMatcher.group(1);
            }

            String title = "";
            Matcher titleMatcher = ITEM_TITLE_PATTERN.matcher(block);
            if (titleMatcher.find()) {
                title = SteamHtmlDecoder.stripTagsAndDecode(titleMatcher.group(1));
            }

            String author = "";
            Matcher authorMatcher = ITEM_AUTHOR_PATTERN.matcher(block);
            if (authorMatcher.find()) {
                author = SteamHtmlDecoder.stripTagsAndDecode(authorMatcher.group(1));
            }

            String desc = descriptions.get(fileId);
            items.add(new WorkshopItem(fileId, appId, title, author, previewUrl, desc));
        }

        boolean hasNextPage = payload.contains("&p=" + (page + 1)) && payload.contains("class='pagebtn'");
        return new WorkshopPage(items, page, hasNextPage);
    }

    private WorkshopPage tryParseValveSsrData(String payload, int page) {
        try {
            Matcher m = VALVE_SSR_DATA_PATTERN.matcher(payload);
            if (!m.find()) return null;

            String jsonText = m.group(1);
            if (jsonText == null || jsonText.isEmpty()) return null;

            JSONObject root = new JSONObject(jsonText);
            JSONObject renderContext = root.optJSONObject("renderContext");
            if (renderContext == null) return null;

            Object qdObj = renderContext.opt("queryData");
            if (qdObj == null) return null;

            JSONObject queryData;
            if (qdObj instanceof String) {
                queryData = new JSONObject((String) qdObj);
            } else if (qdObj instanceof JSONObject) {
                queryData = (JSONObject) qdObj;
            } else {
                return null;
            }

            JSONArray queries = queryData.optJSONArray("queries");
            if (queries == null) return null;

            Map<String, String> creatorMap = new HashMap<>();
            JSONObject browseData = null;

            for (int i = 0; i < queries.length(); i++) {
                JSONObject queryObj = queries.optJSONObject(i);
                if (queryObj == null) continue;
                JSONArray keyArr = queryObj.optJSONArray("queryKey");
                if (keyArr == null || keyArr.length() == 0) continue;

                String keyName = keyArr.optString(0, "");
                if ("PlayerLinkDetails".equals(keyName) && keyArr.length() >= 2) {
                    String steamId = keyArr.optString(1, "");
                    JSONObject state = queryObj.optJSONObject("state");
                    if (state != null) {
                        JSONObject d = state.optJSONObject("data");
                        if (d != null) {
                            JSONObject pub = d.optJSONObject("public_data");
                            if (pub != null) {
                                String name = pub.optString("persona_name", "");
                                if (!name.isEmpty()) creatorMap.put(steamId, name);
                            }
                        }
                    }
                } else if ("workshop_browse".equals(keyName)) {
                    JSONObject state = queryObj.optJSONObject("state");
                    if (state != null) {
                        JSONObject d = state.optJSONObject("data");
                        if (d != null && d.has("results")) {
                            browseData = d;
                        }
                    }
                }
            }

            if (browseData == null) return null;

            // Extract creators embedded in creator_player_link_details
            JSONArray creatorLinks = browseData.optJSONArray("creator_player_link_details");
            if (creatorLinks != null) {
                for (int c = 0; c < creatorLinks.length(); c++) {
                    JSONObject cObj = creatorLinks.optJSONObject(c);
                    if (cObj == null) continue;
                    JSONObject pub = cObj.optJSONObject("public_data");
                    if (pub != null) {
                        String sid = pub.optString("steamid", "");
                        String name = pub.optString("persona_name", "");
                        if (!sid.isEmpty() && !name.isEmpty()) {
                            creatorMap.put(sid, name);
                        }
                    }
                }
            }

            int currentPage = browseData.optInt("current_page", page);
            int totalPages = browseData.optInt("total_pages", currentPage);
            JSONArray results = browseData.optJSONArray("results");
            if (results == null) return null;

            List<WorkshopItem> items = new ArrayList<>();
            for (int i = 0; i < results.length(); i++) {
                JSONObject r = results.optJSONObject(i);
                if (r == null) continue;

                long publishedFileId = parseLongSafe(r.opt("publishedfileid"));
                if (publishedFileId <= 0) continue;

                int appId = r.optInt("consumer_appid", DEFAULT_APP_ID);
                String title = r.optString("title", "");
                String previewUrl = r.optString("preview_url", "");
                String creatorId = r.optString("creator", "");
                String author = creatorMap.get(creatorId);
                if (author == null) author = "";
                String shortDesc = r.optString("short_description", "");

                WorkshopItem item = new WorkshopItem(publishedFileId, appId, title, author, previewUrl, shortDesc);

                long fileSize = parseLongSafe(r.opt("file_size"));
                if (fileSize > 0) item.setFileSize(fileSize);

                long updated = parseLongSafe(r.opt("time_updated"));
                if (updated > 0) item.setTimeUpdated(updated);

                long subs = parseLongSafe(r.opt("subscriptions"));
                if (subs > 0) item.setSubscriptions(subs);

                long favs = parseLongSafe(r.opt("favorited"));
                if (favs > 0) item.setFavorites(favs);

                long views = parseLongSafe(r.opt("views"));
                if (views > 0) item.setViews(views);

                JSONArray tagsArr = r.optJSONArray("tags");
                if (tagsArr != null) {
                    List<String> tags = new ArrayList<>();
                    for (int t = 0; t < tagsArr.length(); t++) {
                        JSONObject tagObj = tagsArr.optJSONObject(t);
                        if (tagObj != null) {
                            String tagName = tagObj.optString("display_name", tagObj.optString("tag", ""));
                            if (!tagName.isEmpty()) tags.add(tagName);
                        }
                    }
                    item.setTags(tags);
                }

                items.add(item);
            }

            return new WorkshopPage(items, currentPage, currentPage < totalPages);
        } catch (Exception e) {
            Log.w(TAG, "tryParseValveSsrData error", e);
            return null;
        }
    }

    private WorkshopPage tryParseSsrRenderContext(String payload, int page) {
        try {
            Matcher matcher = SSR_PREFIX_PATTERN.matcher(payload);
            if (!matcher.find()) return null;

            int start = matcher.end();
            StringBuilder escaped = new StringBuilder();
            boolean inEscape = false;
            for (int i = start; i < payload.length(); i++) {
                char c = payload.charAt(i);
                if (inEscape) {
                    escaped.append(c);
                    inEscape = false;
                } else if (c == '\\') {
                    escaped.append(c);
                    inEscape = true;
                } else if (c == '"') {
                    break;
                } else {
                    escaped.append(c);
                }
            }

            String decodedJson = unescapeJavaString(escaped.toString());
            JSONObject root = new JSONObject(decodedJson);
            String queryDataStr = root.optString("queryData", "");
            if (queryDataStr.isEmpty()) return null;

            JSONObject queryData = new JSONObject(queryDataStr);
            JSONArray queries = queryData.optJSONArray("queries");
            if (queries == null) return null;

            Map<String, String> creatorNames = new HashMap<>();
            JSONObject browseStateData = null;

            for (int i = 0; i < queries.length(); i++) {
                JSONObject queryObj = queries.optJSONObject(i);
                if (queryObj == null) continue;
                JSONArray keyArr = queryObj.optJSONArray("queryKey");
                if (keyArr != null && keyArr.length() >= 2 && "PlayerLinkDetails".equals(keyArr.optString(0))) {
                    String steamId = keyArr.optString(1);
                    JSONObject state = queryObj.optJSONObject("state");
                    if (state != null) {
                        JSONObject data = state.optJSONObject("data");
                        if (data != null) {
                            JSONObject pub = data.optJSONObject("public_data");
                            if (pub != null) {
                                String persona = pub.optString("persona_name", "");
                                if (!persona.isEmpty()) {
                                    creatorNames.put(steamId, persona);
                                }
                            }
                        }
                    }
                }

                JSONObject state = queryObj.optJSONObject("state");
                if (state != null) {
                    JSONObject data = state.optJSONObject("data");
                    if (data != null && data.has("results") && data.has("current_page")) {
                        browseStateData = data;
                    }
                }
            }

            if (browseStateData == null) return null;

            int currentPage = browseStateData.optInt("current_page", page);
            int totalPages = browseStateData.optInt("total_pages", currentPage);
            JSONArray results = browseStateData.optJSONArray("results");
            if (results == null) return null;

            List<WorkshopItem> items = new ArrayList<>();
            for (int i = 0; i < results.length(); i++) {
                JSONObject r = results.optJSONObject(i);
                if (r == null) continue;
                long publishedFileId = parseLongSafe(r.opt("publishedfileid"));
                if (publishedFileId <= 0) continue;
                int appId = r.optInt("consumer_appid", DEFAULT_APP_ID);
                String title = r.optString("title", "");
                String previewUrl = r.optString("preview_url", "");
                String creatorId = r.optString("creator", "");
                String author = creatorNames.get(creatorId);
                if (author == null) author = "";
                String shortDesc = r.optString("short_description", "");
                long fileSize = parseLongSafe(r.opt("file_size"));

                WorkshopItem item = new WorkshopItem(publishedFileId, appId, title, author, previewUrl, shortDesc);
                if (fileSize > 0) item.setFileSize(fileSize);
                items.add(item);
            }

            return new WorkshopPage(items, currentPage, currentPage < totalPages);
        } catch (Exception e) {
            Log.d(TAG, "SSR parse failed: " + e.getMessage());
            return null;
        }
    }

    public WorkshopItem getSingleItemDetail(long publishedFileId) {
        try {
            List<WorkshopItem> list = Collections.singletonList(
                    new WorkshopItem(publishedFileId, DEFAULT_APP_ID, "", "", "", "")
            );
            enrichItemsWithDetails(list);
            WorkshopItem item = list.get(0);
            if (!item.getTitle().isEmpty() || item.getFileSize() != null) {
                return item;
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to get single item detail: " + publishedFileId, e);
        }
        return null;
    }

    public void enrichItemsWithDetails(List<WorkshopItem> items) {
        if (items == null || items.isEmpty()) return;
        try {
            URL url = new URL("https://api.steampowered.com/ISteamRemoteStorage/GetPublishedFileDetails/v1/");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            conn.setDoOutput(true);

            StringBuilder body = new StringBuilder();
            body.append("itemcount=").append(items.size());
            body.append("&appid=").append(DEFAULT_APP_ID);
            for (int i = 0; i < items.size(); i++) {
                body.append("&publishedfileids[").append(i).append("]=")
                        .append(items.get(i).getPublishedFileId());
            }

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }

            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                return;
            }

            String resp = readStreamToString(conn.getInputStream());
            JSONObject root = new JSONObject(resp);
            JSONObject responseObj = root.optJSONObject("response");
            if (responseObj == null) return;
            JSONArray details = responseObj.optJSONArray("publishedfiledetails");
            if (details == null) return;

            Map<Long, JSONObject> detailMap = new HashMap<>();
            for (int i = 0; i < details.length(); i++) {
                JSONObject d = details.optJSONObject(i);
                if (d == null) continue;
                long id = parseLongSafe(d.opt("publishedfileid"));
                if (id > 0) {
                    detailMap.put(id, d);
                }
            }

            for (WorkshopItem item : items) {
                JSONObject d = detailMap.get(item.getPublishedFileId());
                if (d == null) continue;
                long size = parseLongSafe(d.opt("file_size"));
                if (size > 0) item.setFileSize(size);

                String apiTitle = d.optString("title", "");
                if (item.getTitle().isEmpty() && !apiTitle.isEmpty()) {
                    item.setTitle(apiTitle);
                }

                String apiDesc = d.optString("description", "");
                if (item.getDescription().isEmpty() && !apiDesc.isEmpty()) {
                    item.setDescription(apiDesc);
                }

                String previewUrl = d.optString("preview_url", "");
                if (item.getPreviewUrl().isEmpty() && !previewUrl.isEmpty()) {
                    item.setPreviewUrl(previewUrl);
                }

                long updated = parseLongSafe(d.opt("time_updated"));
                if (updated > 0) item.setTimeUpdated(updated);

                long subs = parseLongSafe(d.opt("subscriptions"));
                if (subs > 0) item.setSubscriptions(subs);

                long favs = parseLongSafe(d.opt("favorited"));
                if (favs > 0) item.setFavorites(favs);

                long views = parseLongSafe(d.opt("views"));
                if (views > 0) item.setViews(views);

                JSONArray tagsArr = d.optJSONArray("tags");
                if (tagsArr != null && (item.getTags() == null || item.getTags().isEmpty())) {
                    List<String> tags = new ArrayList<>();
                    for (int t = 0; t < tagsArr.length(); t++) {
                        JSONObject tagObj = tagsArr.optJSONObject(t);
                        if (tagObj != null) {
                            String name = tagObj.optString("display_name", tagObj.optString("tag", ""));
                            if (!name.isEmpty()) tags.add(name);
                        }
                    }
                    item.setTags(tags);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "enrichItemsWithDetails error", e);
        }
    }

    public static Long parsePublishedFileId(String input) {
        if (input == null) return null;
        String trimmed = input.trim();
        if (trimmed.isEmpty()) return null;

        try {
            long id = Long.parseLong(trimmed);
            if (id > 0) return id;
        } catch (NumberFormatException ignored) {}

        try {
            Uri uri = Uri.parse(trimmed);
            if (uri != null) {
                String host = uri.getHost();
                if (host != null && host.contains("steamcommunity.com")) {
                    String idParam = uri.getQueryParameter("id");
                    if (idParam != null) {
                        long id = Long.parseLong(idParam);
                        if (id > 0) return id;
                    }
                }
            }
        } catch (Exception ignored) {}

        return null;
    }

    public static List<Long> parseAllPublishedFileIds(String input) {
        if (input == null || input.trim().isEmpty()) {
            return Collections.emptyList();
        }
        Set<Long> result = new LinkedHashSet<>();

        // 1. Extract ?id= or &id= parameters from any URLs present in the text
        Matcher matcher = Pattern.compile("[?&]id=(\\d+)").matcher(input);
        while (matcher.find()) {
            try {
                long id = Long.parseLong(matcher.group(1));
                if (id > 0 && id != DEFAULT_APP_ID) {
                    result.add(id);
                }
            } catch (NumberFormatException ignored) {}
        }

        // 2. Split input by whitespace, newlines, commas, semicolons, quotes, etc.
        String[] tokens = input.split("[\\s,;\\\"'\\[\\]|]+");
        for (String token : tokens) {
            token = token.trim();
            if (token.isEmpty()) continue;
            Long id = parsePublishedFileId(token);
            if (id != null && id > 0 && id != DEFAULT_APP_ID) {
                result.add(id);
            }
        }

        return new ArrayList<>(result);
    }

    private static long parseLongSafe(Object obj) {
        if (obj == null) return 0L;
        if (obj instanceof Number) return ((Number) obj).longValue();
        try {
            return Long.parseLong(obj.toString().trim());
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private static String getSteamLanguage() {
        String lang = Locale.getDefault().getLanguage();
        if ("ru".equalsIgnoreCase(lang)) return "russian";
        if ("zh".equalsIgnoreCase(lang)) return "schinese";
        if ("pt".equalsIgnoreCase(lang)) return "brazilian";
        if ("es".equalsIgnoreCase(lang)) return "spanish";
        if ("fr".equalsIgnoreCase(lang)) return "french";
        if ("de".equalsIgnoreCase(lang)) return "german";
        return "english";
    }

    private static String readStreamToString(InputStream is) throws Exception {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[8192];
            int n;
            while ((n = reader.read(buf)) != -1) {
                sb.append(buf, 0, n);
            }
            return sb.toString();
        }
    }

    private static String unescapeJavaString(String st) {
        StringBuilder sb = new StringBuilder(st.length());
        for (int i = 0; i < st.length(); i++) {
            char ch = st.charAt(i);
            if (ch == '\\') {
                char next = (i == st.length() - 1) ? '\\' : st.charAt(i + 1);
                if (next >= '0' && next <= '7') {
                    String code = "" + next;
                    i++;
                    if ((i < st.length() - 1) && st.charAt(i + 1) >= '0' && st.charAt(i + 1) <= '7') {
                        code += st.charAt(i + 1);
                        i++;
                        if ((i < st.length() - 1) && st.charAt(i + 1) >= '0' && st.charAt(i + 1) <= '7') {
                            code += st.charAt(i + 1);
                            i++;
                        }
                    }
                    sb.append((char) Integer.parseInt(code, 8));
                    continue;
                }
                switch (next) {
                    case '\\': ch = '\\'; break;
                    case 'b':  ch = '\b'; break;
                    case 'f':  ch = '\f'; break;
                    case 'n':  ch = '\n'; break;
                    case 'r':  ch = '\r'; break;
                    case 't':  ch = '\t'; break;
                    case '\"': ch = '\"'; break;
                    case '\'': ch = '\''; break;
                    case 'u':
                        if (i >= st.length() - 5) {
                            ch = 'u';
                            break;
                        }
                        int code = Integer.parseInt(st.substring(i + 2, i + 6), 16);
                        sb.append(Character.toChars(code));
                        i += 5;
                        continue;
                    default:
                        ch = next;
                        break;
                }
                i++;
            }
            sb.append(ch);
        }
        return sb.toString();
    }
}
