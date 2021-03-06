package cgeo.geocaching.connector.gc;

import android.os.Bundle;
import android.support.v4.app.LoaderManager;
import android.support.v4.content.Loader;
import android.widget.CheckBox;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import java.util.Calendar;
import java.util.Collections;
import java.util.List;

import cgeo.geocaching.R;
import cgeo.geocaching.activity.ActivityMixin;
import cgeo.geocaching.connector.AbstractLoggingManager;
import cgeo.geocaching.connector.ImageResult;
import cgeo.geocaching.connector.LogResult;
import cgeo.geocaching.enumerations.Loaders;
import cgeo.geocaching.enumerations.StatusCode;
import cgeo.geocaching.loaders.UrlLoader;
import cgeo.geocaching.log.LogCacheActivity;
import cgeo.geocaching.log.LogType;
import cgeo.geocaching.log.TrackableLog;
import cgeo.geocaching.models.Geocache;
import cgeo.geocaching.models.Image;
import cgeo.geocaching.network.Parameters;
import cgeo.geocaching.settings.Settings;
import cgeo.geocaching.storage.DataStore;
import cgeo.geocaching.utils.Log;
import cgeo.geocaching.utils.TextUtils;

class GCLoggingManager extends AbstractLoggingManager implements LoaderManager.LoaderCallbacks<String> {

    private final LogCacheActivity activity;
    private final Geocache cache;

    private String[] viewstates;
    @NonNull private List<TrackableLog> trackables = Collections.emptyList();
    private List<LogType> possibleLogTypes;
    private boolean hasLoaderError = true;
    private int premFavcount;

    GCLoggingManager(final LogCacheActivity activity, final Geocache cache) {
        this.activity = activity;
        this.cache = cache;
    }

    @Nullable
    @Override
    public Loader<String> onCreateLoader(final int arg0, final Bundle arg1) {
        if (!Settings.hasGCCredentials()) { // allow offline logging
            ActivityMixin.showToast(activity, activity.getString(R.string.err_login));
            return null;
        }
        activity.onLoadStarted();
        return new UrlLoader(activity.getBaseContext(), "https://www.geocaching.com/seek/log.aspx", new Parameters("ID", cache.getCacheId()));
    }

    @Override
    public void onLoadFinished(final Loader<String> arg0, final String page) {
        if (page == null) {
            hasLoaderError = true;
        } else {
            viewstates = GCLogin.getViewstates(page);
            trackables = GCParser.parseTrackableLog(page);
            possibleLogTypes = GCParser.parseTypes(page);
            if (StringUtils.isBlank(cache.getGuid())) {
                // Acquire the cache GUID from the log page. This will not only complete the information in the database,
                // but also allow the user to post a rating using GCVote since it requires the GUID to do so.
                final String guid = TextUtils.getMatch(page, GCConstants.PATTERN_LOG_GUID, null);
                if (StringUtils.isNotBlank(guid)) {
                    cache.setGuid(guid);
                    DataStore.saveChangedCache(cache);
                } else {
                    Log.w("Could not acquire GUID from log page for " + cache.getGeocode());
                }
            }
            premFavcount = GCParser.getFavoritePoints(page);
            hasLoaderError = possibleLogTypes.isEmpty();
        }

        activity.onLoadFinished();
    }

    @Override
    public void onLoaderReset(final Loader<String> arg0) {
        // nothing to do
    }

    @Override
    public void init() {
        activity.getSupportLoaderManager().initLoader(Loaders.LOGGING_GEOCHACHING.getLoaderId(), null, this);
    }

    @Override
    @NonNull
    public LogResult postLog(@NonNull final LogType logType, @NonNull final Calendar date, @NonNull final String log, @Nullable final String logPassword, @NonNull final List<TrackableLog> trackableLogs) {

        try {
            final CheckBox favCheck = (CheckBox) activity.findViewById(R.id.favorite_check);
            final ImmutablePair<StatusCode, String> postResult = GCParser.postLog(cache.getGeocode(), cache.getCacheId(), viewstates, logType,
                    date.get(Calendar.YEAR), date.get(Calendar.MONTH) + 1, date.get(Calendar.DATE),
                    log, trackableLogs, favCheck.isChecked());

            if (postResult.left == StatusCode.NO_ERROR) {
                if (logType == LogType.TEMP_DISABLE_LISTING) {
                    cache.setDisabled(true);
                } else if (logType == LogType.ENABLE_LISTING) {
                    cache.setDisabled(false);
                }
                if (favCheck.isChecked()) {
                    cache.setFavorite(true);
                    cache.setFavoritePoints(cache.getFavoritePoints() + 1);
                }
            }
            return new LogResult(postResult.left, postResult.right);
        } catch (final Exception e) {
            Log.e("GCLoggingManager.postLog", e);
        }

        return new LogResult(StatusCode.LOG_POST_ERROR, "");
    }

    @Override
    @NonNull
    public ImageResult postLogImage(final String logId, final Image image) {

        if (!image.isEmpty()) {

            final ImmutablePair<StatusCode, String> imageResult = GCParser.uploadLogImage(logId, image);

            return new ImageResult(imageResult.left, imageResult.right);
        }

        return new ImageResult(StatusCode.LOGIMAGE_POST_ERROR, "");
    }

    @Override
    public boolean hasLoaderError() {
        return hasLoaderError;
    }

    @Override
    @NonNull
    public List<TrackableLog> getTrackables() {
        if (hasLoaderError) {
            return Collections.emptyList();
        }
        return trackables;
    }

    @Override
    @NonNull
    public List<LogType> getPossibleLogTypes() {
        if (hasLoaderError) {
            return Collections.emptyList();
        }
        return possibleLogTypes;
    }

    @Override
    public int getPremFavoritePoints() {
        return hasLoaderError ? 0 : premFavcount;
    }

}
