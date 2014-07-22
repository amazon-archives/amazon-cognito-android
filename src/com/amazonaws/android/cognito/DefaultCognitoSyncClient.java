/**
 * Copyright 2013-2014 Amazon.com, 
 * Inc. or its affiliates. All Rights Reserved.
 * 
 * Licensed under the Amazon Software License (the "License"). 
 * You may not use this file except in compliance with the 
 * License. A copy of the License is located at
 * 
 *     http://aws.amazon.com/asl/
 * 
 * or in the "license" file accompanying this file. This file is 
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR 
 * CONDITIONS OF ANY KIND, express or implied. See the License 
 * for the specific language governing permissions and 
 * limitations under the License.
 */

package com.amazonaws.android.cognito;

import android.content.Context;
import android.util.Log;

import com.amazonaws.android.auth.CognitoCredentialsProvider;
import com.amazonaws.android.cognito.exceptions.DataStorageException;
import com.amazonaws.android.cognito.internal.storage.CognitoSyncStorage;
import com.amazonaws.android.cognito.internal.storage.SQLiteLocalStorage;
import com.amazonaws.android.cognito.internal.util.DatasetUtils;
import com.amazonaws.android.cognito.internal.util.StringUtils;
import com.amazonaws.util.VersionInfoUtils;

import java.util.List;

/**
 * Default implementation of {@link CognitoSyncClient}. It saves {@link Dataset}
 * in SQLite database. Here is a sample usage:
 * 
 * <pre>
 * CognitoCredentialsProvider provider = new CognitoCredentialsProvider(context,
 *         awsAccountId, identityPoolId, unauthRoleArn, authRoleArn);
 * CognitoClient client = new DefaultCognitoClient(context, identityPoolId, provider);
 * 
 * Dataset dataset = client.openOrCreateDataset(&quot;default_dataset&quot;);
 * dataset.put(&quot;high_score&quot;, &quot;100&quot;);
 * dataset.synchronize(new SyncCallback() {
 *     // override callbacks
 * });
 * </pre>
 */
public class DefaultCognitoSyncClient implements CognitoSyncClient,
        CognitoCredentialsProvider.IdentityChangedListener {

    private static final String TAG = "DefaultCognitoClient";

    /**
     * User agent string to append to all requests to the remote service
     */
    private static final String USER_AGENT = DefaultCognitoSyncClient.class.getName()
            + "/" + VersionInfoUtils.getVersion();

    /**
     * Default database name.
     */
    private static final String DATABASE_NAME = "cognito_dataset_cache.db";

    private final Context context;
    private final SQLiteLocalStorage local;
    private final CognitoSyncStorage remote;
    private final CognitoCredentialsProvider provider;

    /**
     * Constructs a DefaultCognitoClient object.
     * 
     * @param context a context of the app
     * @param identityPoolId Cognito identity pool id
     * @param provider a credentials provider
     */
    public DefaultCognitoSyncClient(Context context, String identityPoolId,
            CognitoCredentialsProvider provider) {
        if (context == null) {
            throw new IllegalArgumentException("context can't be null");
        }
        if (StringUtils.isEmpty(identityPoolId)) {
            throw new IllegalArgumentException("invalid identity pool id");
        }
        this.context = context;
        this.provider = provider;
        local = new SQLiteLocalStorage(context, DATABASE_NAME);
        remote = new CognitoSyncStorage(identityPoolId, provider);
        remote.setUserAgent(USER_AGENT);
        provider.registerIdentityChangedListener(this);
    }

    @Override
    public DefaultDataset openOrCreateDataset(String datasetName) {
        DatasetUtils.validateDatasetName(datasetName);
        local.createDataset(getIdentityId(), datasetName);
        return new DefaultDataset(context, datasetName, provider, local, remote);
    }

    @Override
    public List<DatasetMetadata> listDatasets() {
        return local.getDatasets(getIdentityId());
    }

    /**
     * This method makes network requests. Don't call it in the main thread.
     */
    @Override
    public void refreshDatasetMetadata() throws DataStorageException {
        List<DatasetMetadata> datasets = remote.getDatasets();
        local.updateDatasetMetadata(getIdentityId(), datasets);
    }

    @Override
    public void wipeData() {
        provider.clear();
        local.wipeData();
        Log.i(TAG, "All data has been wiped");
    }

    @Override
    public void identityChanged(String oldIdentityId, String newIdentityId) {
        Log.i(TAG, "identity change detected");
        local.changeIdentityId(oldIdentityId == null ? DatasetUtils.UNKNOWN_IDENTITY_ID
                : oldIdentityId, newIdentityId);
    }

    String getIdentityId() {
        return DatasetUtils.getIdentityId(provider);
    }
}
