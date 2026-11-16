package com.nexo.cefriumprototype;

import android.util.Log;
import com.cefrium.CefriumBrowser;
import com.cefrium.CefriumDownloadClient;
import com.cefrium.CefriumProfile;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Two Cefrium download types, two scopes:
 * - CefriumBrowser.DownloadItem via CefriumDownloadClient: per browser, decides
 *   whether and where a download is saved (required from the next Cefrium).
 * - CefriumProfile.Download via DownloadObserver: per profile, every state
 *   change, also for downloads resumed after a restart.
 */
final class CefriumDownloadManager implements CefriumDownloadClient {
    private final CefriumProfile profile;
    private final List<Consumer<CefriumProfile.Download>> listeners = new CopyOnWriteArrayList<>();

    CefriumDownloadManager(CefriumProfile profile) { this.profile = profile; }

    /** Call on the UI thread once a browser for this profile exists (incognito needs one). */
    boolean attachObserver() {
        boolean ok = profile.setDownloadObserver(download -> {
            Log.i(Nexo.TAG, "download " + describe(download));
            for (Consumer<CefriumProfile.Download> l : listeners) l.accept(download);
        });
        if (!ok) Log.w(Nexo.TAG, "download observer not installed for profile " + profile.getName());
        return ok;
    }

    void detachObserver() { profile.setDownloadObserver(null); }

    @Override public void onBeforeDownload(CefriumBrowser browser, CefriumBrowser.DownloadItem item,
            CefriumBrowser.BeforeDownloadCallback callback) {
        Log.i(Nexo.TAG, "download requested url=" + item.getUrl() + " name=" + item.getSuggestedName());
        callback.allow(null); // app's Downloads dir under its suggested name
    }

    void addListener(Consumer<CefriumProfile.Download> l) { listeners.add(l); }
    void removeListener(Consumer<CefriumProfile.Download> l) { listeners.remove(l); }

    static String describe(CefriumProfile.Download d) {
        String state;
        switch (d.getState()) {
            case CefriumProfile.DOWNLOAD_IN_PROGRESS: state = "in_progress"; break;
            case CefriumProfile.DOWNLOAD_COMPLETE: state = "complete"; break;
            case CefriumProfile.DOWNLOAD_CANCELED: state = "canceled"; break;
            case CefriumProfile.DOWNLOAD_INTERRUPTED: state = "interrupted(" + d.getInterruptReason() + ")"; break;
            default: state = "state" + d.getState();
        }
        return state + " " + d.getReceivedBytes() + "/" + d.getTotalBytes() + " url=" + d.getUrl() + " path=" + d.getPath();
    }
}
