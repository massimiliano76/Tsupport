package org.telegram.messenger;

import com.appspot.tsupport_android.ownedConversation.OwnedConversation;
import com.google.api.client.extensions.android.http.AndroidHttp;
import com.google.api.client.extensions.android.json.AndroidJsonFactory;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.JsonFactory;

public class AppConstantsOwnConversation {

    /**
     * Class instance of the JSON factory.
     */
    public static final JsonFactory JSON_FACTORY = new AndroidJsonFactory();

    /**
     * Class instance of the HTTP transport.
     */
    public static final HttpTransport HTTP_TRANSPORT = AndroidHttp.newCompatibleTransport();

    public static OwnedConversation getApiServiceHandle() {
        OwnedConversation.Builder ownedConversation = new OwnedConversation.Builder(AppConstantsOwnConversation.HTTP_TRANSPORT,
                AppConstantsOwnConversation.JSON_FACTORY, null);

        return ownedConversation.build();
    }
}
