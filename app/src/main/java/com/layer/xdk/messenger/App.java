package com.layer.xdk.messenger;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.StrictMode;
import android.support.multidex.MultiDexApplication;

import com.layer.sdk.LayerClient;
import com.layer.xdk.messenger.util.AuthenticationProvider;
import com.layer.xdk.messenger.util.CustomEndpoint;
import com.layer.xdk.messenger.util.LayerAuthenticationProvider;
import com.layer.xdk.messenger.util.Log;
import com.layer.xdk.ui.message.LegacyMimeTypes;
import com.layer.xdk.ui.util.Util;
import com.squareup.picasso.Picasso;

import java.util.Arrays;

/**
 * App provides static access to a LayerClient and other XDK and Messenger context, including
 * AuthenticationProvider, ParticipantProvider, Participant, and Picasso.
 *
 * @see LayerClient
 * @see Picasso
 * @see AuthenticationProvider
 */
public class App extends MultiDexApplication {

    // Set your Layer App ID from your Layer Developer Dashboard.
    public final static String LAYER_APP_ID = null;

    public static final String SHARED_PREFS = "MESSENGER_SHARED_PREFS";
    public static final String SHARED_PREFS_KEY_TELEMETRY_ENABLED = "TELEMETRY_ENABLED";

    private static Application sInstance;
    private static AuthenticationProvider sAuthProvider;

    //==============================================================================================
    // Application Overrides
    //==============================================================================================

    @Override
    public void onCreate() {
        super.onCreate();

        sInstance = this;

        // Enable verbose logging in debug builds
        if (BuildConfig.DEBUG) {
            com.layer.xdk.ui.util.Log.setLoggingEnabled(true);
            com.layer.xdk.messenger.util.Log.setAlwaysLoggable(true);
            LayerClient.setLoggingEnabled(this, true);
            LayerClient.setPrivateLoggingEnabled(true);

            StrictMode.setThreadPolicy(new StrictMode.ThreadPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build());
            StrictMode.setVmPolicy(new StrictMode.VmPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build());
        }

        if (Log.isPerfLoggable()) {
            Log.perf("Application onCreate()");
        }

        // Allow the LayerClient to track app state
        LayerClient.applicationCreated(this);

        LayerServiceLocatorManager.INSTANCE.getInstance().setAppContext(this);
    }

    public static Application getInstance() {
        return sInstance;
    }


    //==============================================================================================
    // Identity Provider Methods
    //==============================================================================================

    /**
     * Routes the user to the proper Activity depending on their authenticated state.  Returns
     * `true` if the user has been routed to another Activity, or `false` otherwise.
     *
     * @param from Activity to route from.
     * @return `true` if the user has been routed to another Activity, or `false` otherwise.
     */
    public static boolean routeLogin(Activity from) {
        return getAuthenticationProvider().routeLogin(getLayerClient(), getLayerAppId(), from);
    }

    /**
     * Authenticates with the AuthenticationProvider and Layer, returning asynchronous results to
     * the provided callback.
     *
     * @param credentials Credentials associated with the current AuthenticationProvider.
     * @param callback    Callback to receive authentication results.
     */
    @SuppressWarnings("unchecked")
    public static void authenticate(Object credentials, AuthenticationProvider.Callback callback) {
        LayerClient client = getLayerClient();
        if (client == null) return;
        String layerAppId = getLayerAppId();
        if (layerAppId == null) return;
        getAuthenticationProvider()
                .setCredentials(credentials)
                .setCallback(callback);
        client.authenticate();
    }

    /**
     * Deauthenticates with Layer and clears cached AuthenticationProvider credentials.
     *
     * @param callback Callback to receive deauthentication success and failure.
     */
    public static void deauthenticate(final Util.DeauthenticationCallback callback) {
        Util.deauthenticate(getLayerClient(), new Util.DeauthenticationCallback() {
            @Override
            @SuppressWarnings("unchecked")
            public void onDeauthenticationSuccess(LayerClient client) {
                getAuthenticationProvider().setCredentials(null);
                callback.onDeauthenticationSuccess(client);
            }

            @Override
            public void onDeauthenticationFailed(LayerClient client, String reason) {
                callback.onDeauthenticationFailed(client, reason);
            }
        });
    }


    //==============================================================================================
    // Getters / Setters
    //==============================================================================================

    /**
     * Gets or creates a LayerClient, using a default set of LayerClient.Options
     * App ID and Options from the `generateLayerClient` method.  Returns `null` if the App was
     * unable to create a LayerClient (due to no App ID, etc.). Set the information in assets/LayerConfiguration.json
     * @return New or existing LayerClient, or `null` if a LayerClient could not be constructed.
     */
    public static LayerClient getLayerClient() {
        LayerClient layerClient = LayerServiceLocatorManager.INSTANCE.getInstance().getLayerClient();
        if (layerClient == null) {
            boolean telemetryEnabled;
            SharedPreferences sharedPreferences = sInstance.getSharedPreferences(SHARED_PREFS, MODE_PRIVATE);
            if (sharedPreferences.contains(SHARED_PREFS_KEY_TELEMETRY_ENABLED)) {
                telemetryEnabled = sharedPreferences.getBoolean(SHARED_PREFS_KEY_TELEMETRY_ENABLED, true);
            } else {
                sharedPreferences.edit().putBoolean(SHARED_PREFS_KEY_TELEMETRY_ENABLED, true).apply();
                telemetryEnabled = true;
            }

            // Custom options for constructing a LayerClient
            LayerClient.Options options = new LayerClient.Options()

                    /* Fetch the minimum amount per conversation when first authenticated */
                    .historicSyncPolicy(LayerClient.Options.HistoricSyncPolicy.FROM_LAST_MESSAGE)

                    /* Automatically download root and preview parts, along with legacy text and
                     * three part info preview parts
                     */
                    .autoDownloadMimeTypes(Arrays.asList(
                            "*/*; role=root",
                            "*/*; role=preview",
                            "text/plain",
                            LegacyMimeTypes.LEGACY_IMAGE_MIME_TYPE_INFO,
                            LegacyMimeTypes.LEGACY_IMAGE_MIME_TYPE_PREVIEW))
                    .setTelemetryEnabled(telemetryEnabled);

            layerClient = generateLayerClient(sInstance, options);

            // Unable to generate Layer Client (no App ID, etc.)
            if (layerClient == null) return null;

            /* Register AuthenticationProvider for handling authentication challenges */
            layerClient.registerAuthenticationListener(getAuthenticationProvider());

            LayerServiceLocatorManager.INSTANCE.getInstance().setLayerClient(layerClient);
        }
        return layerClient;
    }

    public static AuthenticationProvider getAuthenticationProvider() {
        if (sAuthProvider == null) {
            sAuthProvider = generateAuthenticationProvider(sInstance);

            // If we have cached credentials, try authenticating with Layer
            LayerClient layerClient = getLayerClient();
            if (layerClient != null && sAuthProvider.hasCredentials()) layerClient.authenticate();
        }
        return sAuthProvider;
    }

    public static String getLayerAppId() {
        return (LAYER_APP_ID != null) ? LAYER_APP_ID : CustomEndpoint.getLayerAppId();
    }

    private static LayerClient generateLayerClient(Context context, LayerClient.Options options) {
        String layerAppId = getLayerAppId();
        if (layerAppId == null) {
            if (Log.isLoggable(Log.ERROR)) Log.e(context.getString(R.string.app_id_required));
            return null;
        }
        options.useFirebaseCloudMessaging(true);
        CustomEndpoint.setLayerClientOptions(options);
        return LayerClient.newInstance(context, layerAppId, options);
    }

    private static AuthenticationProvider generateAuthenticationProvider(Context context) {
        return new LayerAuthenticationProvider(context);
    }

}