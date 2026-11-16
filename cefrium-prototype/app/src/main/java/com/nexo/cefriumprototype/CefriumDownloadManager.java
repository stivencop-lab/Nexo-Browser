package com.nexo.cefriumprototype;

import com.cefrium.CefriumBrowser;
import com.cefrium.CefriumProfile;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

final class CefriumDownloadManager {
    private final CefriumProfile.DownloadObserver observer;
    private final List<Consumer<DownloadItem>> listeners = new ArrayList<>();

    interface DownloadItem {
        String getUrl();
        String getSuggestedFilename();
        long getTotalBytes();
        long getReceivedBytes();
        int getState();
        boolean isInterrupted();
        boolean canResume();
        void resume();
        void cancel();
        void remove();
    }

    CefriumDownloadManager(CefriumProfile profile) {
        observer = download -> {
            CefriumBrowser.DownloadItem item = download;
            DownloadItem wrapper = new DownloadItem() {
                public String getUrl() { return item.getUrl(); }
                public String getSuggestedFilename() { return item.getSuggestedFilename(); }
                public long getTotalBytes() { return item.getTotalBytes(); }
                public long getReceivedBytes() { return item.getReceivedBytes(); }
                public int getState() { return item.getState(); }
                public boolean isInterrupted() { return item.isInterrupted(); }
                public boolean canResume() { return item.canResume(); }
                public void resume() { item.resume(); }
                public void cancel() { item.cancel(); }
                public void remove() { item.remove(); }
            };
            listeners.forEach(l -> l.accept(wrapper));
        };
        profile.setDownloadObserver(observer);
    }

    void addListener(Consumer<DownloadItem> l) { listeners.add(l); }
}
