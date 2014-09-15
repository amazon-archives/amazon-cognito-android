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

package com.amazonaws.mobileconnectors.cognito;

import android.content.Context;
import android.util.Log;

import com.amazonaws.auth.CognitoCachingCredentialsProvider;
import com.amazonaws.auth.IdentityChangedListener;
import com.amazonaws.mobileconnectors.cognito.exceptions.DataStorageException;
import com.amazonaws.mobileconnectors.cognito.internal.storage.CognitoSyncStorage;
import com.amazonaws.mobileconnectors.cognito.internal.storage.SQLiteLocalStorage;
import com.amazonaws.mobileconnectors.cognito.internal.util.DatasetUtils;
import com.amazonaws.mobileconnectors.cognito.internal.util.StringUtils;
import com.amazonaws.regions.Regions;
import com.amazonaws.util.VersionInfoUtils;

import java.util.List;

/**
 * This saves {@link Dataset} in SQLite database. Here is a sample usage:
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
public class CognitoSyncManager {

    private static final String TAG = "CognitoSyncManager";

    /**
     * User agent string to append to all requests to the remote service
     */
    private static final String USER_AGENT = CognitoSyncManager.class.getName()
            + "/" + VersionInfoUtils.getVersion();

    /**
     * Default database name.
     */
    private static final String DATABASE_NAME = "cognito_dataset_cache.db";

    private final Context context;
    private final SQLiteLocalStorage local;
    private final CognitoSyncStorage remote;
    private final CognitoCachingCredentialsProvider provider;

    /**
     * Constructs a DefaultCognitoClient object.
     * 
     * @param context a context of the app
     * @param identityPoolId Cognito identity pool id
     * @param region Cognito sync region
     * @param provider a credentials provider
     */
    public CognitoSyncManager(Context context, String identityPoolId, Regions region,
            CognitoCachingCredentialsProvider provider) {
        if (context == null) {
            throw new IllegalArgumentException("context can't be null");
        }
        if (StringUtils.isEmpty(identityPoolId)) {
            throw new IllegalArgumentException("invalid identity pool id");
        }
        this.context = context;
        this.provider = provider;
        local = new SQLiteLocalStorage(context, DATABASE_NAME);
        remote = new CognitoSyncStorage(identityPoolId, region, provider);
        remote.setUserAgent(USER_AGENT);
        provider.registerIdentityChangedListener(new IdentityChangedListener() {
            @Override
            public void identityChanged(String oldIdentityId, String newIdentityId) {
                Log.i(TAG, "identity change detected");
                local.changeIdentityId(oldIdentityId == null ? DatasetUtils.UNKNOWN_IDENTITY_ID
                        : oldIdentityId, newIdentityId);
            }
        });
    }

    /**
     * Opens or creates a dataset. If the dataset doesn't exist, an empty one
     * with the given name will be created. Otherwise, dataset is loaded from
     * local storage. If a dataset is marked as deleted but hasn't been deleted
     * on remote via {@link #refreshDatasetMetadata()}, it will throw
     * {@link IllegalStateException}.
     *
     * @param datasetName dataset name, must be [a-zA-Z0=9_.:-]+
     * @return dataset loaded from local storage
     */
    public DefaultDataset openOrCreateDataset(String datasetName) {
        DatasetUtils.validateDatasetName(datasetName);
        local.createDataset(getIdentityId(), datasetName);
        return new DefaultDataset(context, datasetName, provider, local, remote);
    }

    /**
     * Retrieves a list of datasets from local storage. It may not reflects
     * latest dataset on the remote storage until refreshDatasetMetadata is
     * called.
     *
     * @return list of datasets
     */
    public List<DatasetMetadata> listDatasets() {
        return local.getDatasets(getIdentityId());
    }

    /**
     * Refreshes dataset metadata. Dataset metadata is pulled from remote
     * storage and stored in local storage. Their record data isn't pulled down
     * until you sync each dataset. Note: this is a network request, so calling
     * this method in the main thread will result in
     * NetworkOnMainThreadException.
     *
     * @throws DataStorageException thrown when fail to fresh dataset metadata
     */
    public void refreshDatasetMetadata() throws DataStorageException {
        List<DatasetMetadata> datasets = remote.getDatasets();
        local.updateDatasetMetadata(getIdentityId(), datasets);
    }

    /**
     * Wipes all user data cached locally, including identity id, session
     * credentials, dataset metadata, and all records. Any data that hasn't been
     * synced will be lost. This method is usually used when customer logs out.
     */
    public void wipeData() {
        provider.clear();
        local.wipeData();
        Log.i(TAG, "All data has been wiped");
    }

    String getIdentityId() {
        return DatasetUtils.getIdentityId(provider);
    }
}
