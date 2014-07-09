/**
 * Amazon Cognito Sync client
 * 
 * A high level client that provides data synchronization across multiple mobile devices. It's
 * powered by Amazon Cognito Identity service, Cognito Sync service, and Security Token
 * Service (STS).
 *
 * 
 * Here is a sample usage:
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
 * 
 * Please refer to {@link com.amazonaws.android.cognito.DefaultCognitoSyncClient} and
 * {@link com.amazonaws.android.cognito.Dataset} for more details.
 */

package com.amazonaws.android.cognito;