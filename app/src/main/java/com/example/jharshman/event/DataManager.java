/**
 * @file DataManager.java
 * @author Bruce Emehiser
 * @date 2016 03 07
 *
 * Data manager which manages Event and Check Point data
 * and the storage and retrieval of them.
 */

package com.example.jharshman.event;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import android.widget.Toast;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Headers;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class DataManager implements Callback {

    private static final String TAG = "DataManager";

    /**
     * Listeners who want to be notified of changes.
     */
    private static ArrayList<UpdateListener> mListeners;

    /**
     * Singleton instance of data manager.
     */
    private static DataManager mInstance;

    private Context mContext;

    /**
     * SQLite database manager.
     */
    private DataHelper mDataHelper;

    /**
     * Tells whether or not data set is dirty.
     */
    private boolean mDataSetChanged;

    /**
     * Server connection and data parser
     */
    private final OkHttpClient mClient = new OkHttpClient();
    private final Gson mGson = new Gson();

    private DataManager(Context context) {
        // singleton constructor will read data from server on first run
        mContext = context;

        // connect to database
        mDataHelper = DataHelper.newInstance(context);

        // set up data listeners
        mListeners = new ArrayList<>();

        // set current data set state
        mDataSetChanged = false;

        // sync data with server todo use SyncData() instead
        getEventData();
    }

    /**
     * Singleton instance method. This is so that we don't
     * get concurrent database access issues, and can easily
     * use the same instance everywhere.
     *
     * @param context Everyone needs some context in their isntance.
     * @return The new Data Manager
     */
    public static DataManager instance(Context context) {
        if(mInstance == null) {
            mInstance = new DataManager(context);
        }

        return mInstance;
    }

    // todo pull data from web server based on location and radius
    // todo put data to web server

    /**
     * Get event data from the server
     */
    private void getEventData() {

        Activity activity = (Activity) mContext;

        SharedPreferences sharedPreferences = activity.getPreferences(Context.MODE_PRIVATE);
        String token = sharedPreferences.getString(activity.getString(R.string.jwt_server_token), "");

        Request request = new Request.Builder()
                .url(activity.getString(R.string.magpie_server_event_data))
                .addHeader(activity.getString(R.string.jwt_server_token), token)
                .build();

        mClient.newCall(request).enqueue(this);
    }

    /**
     * Called when the request could not be executed due to cancellation, a connectivity problem or
     * timeout. Because networks can fail during an exchange, it is possible that the remote server
     * accepted the request before the failure.
     *
     * @param call call
     * @param e exception
     */
    @Override
    public void onFailure(Call call, IOException e) {

        try {
            Toast.makeText(mContext, "Server Connection Failed", Toast.LENGTH_LONG).show();
        } catch (Exception f) {
            // something probably happened to the activity, and we can ignore it
        }
    }

    /**
     * Called when the HTTP response was successfully returned by the remote server. The callback may
     * proceed to read the response body with {@link Response#body}. The response is still live until
     * its response body is closed with {@code response.body().close()}. The recipient of the callback
     * may even consume the response body on another thread.
     * <p/>
     * <p>Note that transport-layer success (receiving a HTTP response code, headers and body) does
     * not necessarily indicate application-layer success: {@code response} may still indicate an
     * unhappy HTTP response code like 404 or 500.
     *
     * @param call call
     * @param response response
     */
    @Override
    public void onResponse(Call call, Response response) throws IOException {

        Log.i(TAG, "onResponse()");

        if (! response.isSuccessful()) {
            throw new IOException("Unexpected code " + response);
        }

        Headers responseHeaders = response.headers();
        for (int i = 0; i < responseHeaders.size(); i++) {
            System.out.println(responseHeaders.name(i) + ": " + responseHeaders.value(i));
        }

        JsonParser parser = new JsonParser();
        JsonArray jArray = parser.parse(response.body().string()).getAsJsonArray();
        ArrayList<Event> events = new ArrayList<>();

        // this parses out the json array. the Events and Checkpoints must have @SerializedName("id") notation
        for(JsonElement obj : jArray )
        {
            Event event = mGson.fromJson(obj , Event.class);
            events.add(event);
            Log.i(TAG, "Event: " + event.toString());
        }

        // update the local database
        for(Event event : events) {
            // insert event
            mDataHelper.insertEvent(event);

            // insert checkpoints
            for(CheckPoint checkPoint : event.getCheckPoints()) {
                mDataHelper.insertCheckPoint(checkPoint);
            }
        }
    }

    /**
     * Get all the events from the data helper.
     *
     * @return List of all events.
     */
    public List<Event> getEvents() {

        // get all events from data helper
        return mDataHelper.getEvents();
    }

    /**
     * Get the events that the user is subscribed to.
     * This returns a shallow copy of the event list.
     *
     * @return The list of subscribed events
     */
    public List<Event> getSubscribedEvents() {

        List<Event> subscribedEvents = new ArrayList<>();
        List<Event> allEvents = mDataHelper.getEvents();

        for(Event event : allEvents) {
            if(event.getSubscribed()) {
                subscribedEvents.add(event);
            }
        }

        return subscribedEvents;
    }

    /**
     * Update the subscription status of the event.
     *
     * @param eventID The event to update.
     * @param subscribed The subscription status.
     * @return True if event updated, otherwise false.
     */
    public boolean updateSubscribed(int eventID, boolean subscribed) {

        long updated = mDataHelper.updateSubscribed(eventID, subscribed);

        // set data set changed, so server will be updated
        mDataSetChanged = true;

        return updated != -1;
    }

    /**
     * Update the current check in status at the check point.
     *
     * @param checkpointID The checkpoint to check in to.
     * @param checked The check in status.
     * @return Success/failure when updating local database.
     */
    public boolean updateChecked(int checkpointID, boolean checked) {

        long updated = mDataHelper.updateChecked(checkpointID, checked);

        // set data set changed, so server will be updated
        mDataSetChanged = true;

        return updated != -1;
    }

    /**
     * Get all the checkpoints for the given event.
     *
     * @param eventID The event to get checkpoints of.
     * @return List of checkpoints for given event.
     */
    public List<CheckPoint> getCheckpoints(int eventID) {

        return mDataHelper.getCheckpoints(eventID);
    }

    /**
     * Get a single checkpoint.
     *
     * @param checkpointID The ID of the checkpoint.
     * @return The instance of the checkpoint.
     */
    public CheckPoint getCheckpoint(int checkpointID) {

        return mDataHelper.getCheckpoint(checkpointID);
    }

    /**
     * Synchronize data with server
     */
    public void syncData() {
        getEventData();

        // todo make this update both local and server data with the most recent data.
    }

    public interface UpdateListener {

        /**
         * Called when server receives new data and updates data set
         *
         * @param dataManager This
         */
        void onDataUpdated(DataManager dataManager);
    }

    /**
     * Remove listener from list if listener exists
     *
     * @param listener The listener to remove
     */
    public void setUpdateListener(UpdateListener listener) {
        mListeners.add(listener);
    }

    /**
     * Add listener to be notified when data set changes
     *
     * @param listener Listener to add to listener list
     */
    public void removeUpdateListener(UpdateListener listener) {
        mListeners.remove(listener);
    }

    /**
     * Notify all listeners that the data set has changed
     */
    private void notifyUpdateListeners() {

        for(UpdateListener listener : mListeners) {
            try {
                listener.onDataUpdated(this);
            } catch (Exception e) {
                Log.e(TAG, "Error when notifying listener of data set changed.");
                mListeners.remove(listener);
            }
        }
    }

    /**
     * Get the timestamp of the current time
     * @return The timestamp as a string.
     */
    private String getTimestamp() {

         // todo figure out if we should parse strings for the date, or just let SQLite handle it as a DATE type

        SimpleDateFormat dateFormatGmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        dateFormatGmt.setTimeZone(TimeZone.getTimeZone("GMT"));

        //Local time zone
        SimpleDateFormat dateFormatLocal = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

        try {
            return dateFormatLocal.parse(dateFormatGmt.format(new Date())).toString();
        } catch (ParseException e) {
            Log.e(TAG, "Error parsing timestamp");
            return "0000-000-00 00:00:00";
        }
    }
}