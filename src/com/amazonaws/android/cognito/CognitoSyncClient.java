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

import com.amazonaws.android.cognito.exceptions.DataStorageException;

import java.util.List;

/**
 * This client operates {@link Dataset}.
 */
public interface CognitoSyncClient {

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
    Dataset openOrCreateDataset(String datasetName);

    /**
     * Refreshes dataset metadata. Dataset metadata is pulled from remote
     * storage and stored in local storage. Their record data isn't pulled down
     * until you sync each dataset. Note: this is a network request, so calling
     * this method in the main thread will result in
     * NetworkOnMainThreadException.
     * 
     * @throws DataStorageException thrown when fail to fresh dataset metadata
     */
    public void refreshDatasetMetadata() throws DataStorageException;

    /**
     * Retrieves a list of datasets from local storage. It may not reflects
     * latest dataset on the remote storage until refreshDatasetMetadata is
     * called.
     * 
     * @return list of datasets
     */
    List<DatasetMetadata> listDatasets();

    /**
     * Wipes all user data cached locally, including identity id, session
     * credentials, dataset metadata, and all records. Any data that hasn't been
     * synced will be lost. This method is usually used when customer logs out.
     */
    void wipeData();
}
