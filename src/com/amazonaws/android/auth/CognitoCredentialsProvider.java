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

package com.amazonaws.android.auth;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.amazonaws.ClientConfiguration;
import com.amazonaws.auth.AWSSessionCredentials;
import com.amazonaws.auth.BasicSessionCredentials;
import com.amazonaws.services.cognitoidentity.AmazonCognitoIdentityService;
import com.amazonaws.services.cognitoidentity.model.NotAuthorizedException;
import com.amazonaws.services.securitytoken.AWSSecurityTokenService;

import java.util.Calendar;
import java.util.Map;

/**
 * This credentials provider is intended for Android application. It offers the
 * ability to persist the Cognito identity id in {@link SharedPreferences}.
 * Furthermore, it caches session credentials so as to reduce the number of
 * network requests.
 */
public class CognitoCredentialsProvider extends
        com.amazonaws.auth.CognitoCredentialsProvider implements
        com.amazonaws.auth.CognitoCredentialsProvider.IdentityChangedListener {

    private static final String PREFS_NAME = "com.amazonaws.android.auth";

    private static final String TAG = "CognitoCredentialsProvider";

    /**
     * User agent string appended to cib and sts
     */
    private static final String USER_AGENT = "CognitoCredentialsProvider";

    private static final String ID_KEY = "identityId";
    private static final String AK_KEY = "accessKey";
    private static final String SK_KEY = "secretKey";
    private static final String ST_KEY = "sessionToken";
    private static final String EXP_KEY = "expirationDate";

    private SharedPreferences sharedPreferences = null;

    /**
     * Constructs a new {@link CognitoCredentialsProvider}, which will use the
     * specified Amazon Cognito identity pool to make a request to the AWS
     * Security Token Service (STS) to request short lived session credentials,
     * which will then be returned by this class's {@link #getCredentials()}
     * method.
     * 
     * @param context The context that will store identity id
     * @param accountId The AWS accountId for the account with Amazon Cognito
     * @param identityPoolId The Amazon Cogntio identity pool to use
     * @param unauthRoleArn The ARN of the IAM Role that will be assumed when
     *            unauthenticated
     * @param authRoleArn The ARN of the IAM Role that will be assumed when
     *            authenticated
     */
    public CognitoCredentialsProvider(Context context,
            String accountId, String identityPoolId, String unauthRoleArn, String authRoleArn) {
        this(context, accountId, identityPoolId, unauthRoleArn, authRoleArn,
                new ClientConfiguration() {
                    {
                        setUserAgent(USER_AGENT);
                    }
                });
    }

    /**
     * Constructs a new {@link CognitoCredentialsProvider}, which will use the
     * specified Amazon Cognito identity pool to make a request to the AWS
     * Security Token Service (STS) to request short lived session credentials,
     * which will then be returned by this class's {@link #getCredentials()}
     * method.
     * 
     * @param context The context that will store identity id
     * @param accountId The AWS accountId for the account with Amazon Cognito
     * @param identityPoolId The Amazon Cogntio identity pool to use
     * @param unauthRoleArn The ARN of the IAM Role that will be assumed when
     *            unauthenticated
     * @param authRoleArn The ARN of the IAM Role that will be assumed when
     *            authenticated
     * @param clientConfiguation Configuration to apply to service clients
     *            created
     */
    public CognitoCredentialsProvider(Context context,
            String accountId, String identityPoolId, String unAuthRoleArn, String authRoleArn,
            ClientConfiguration clientConfiguration) {
        super(accountId, identityPoolId, unAuthRoleArn, authRoleArn, clientConfiguration);
        if (context == null) {
            throw new IllegalArgumentException("context can't be null");
        }
        this.sharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        registerIdentityChangedListener(this);
    }

    /**
     * Constructs a new {@link CognitoCredentialsProvider}, which will use the
     * specified Amazon Cognito identity pool to make a request to the AWS
     * Security Token Service (STS) to request short lived session credentials,
     * which will then be returned by this class's {@link #getCredentials()}
     * method.
     * 
     * @param context The context that will store identity id
     * @param accountId The AWS accountId for the account with Amazon Cognito
     * @param identityPoolId The Amazon Cogntio identity pool to use
     * @param unauthRoleArn The ARN of the IAM Role that will be assumed when
     *            unauthenticated
     * @param authRoleArn The ARN of the IAM Role that will be assumed when
     *            authenticated
     * @param cibClient Preconfigured CognitoIdentity client to make requests
     *            with
     * @param stsClient Preconfigured STS client to make requests with
     */
    public CognitoCredentialsProvider(Context context,
            String accountId, String identityPoolId, String unAuthRoleArn, String authRoleArn,
            AmazonCognitoIdentityService cibClient, AWSSecurityTokenService stsClient) {
        super(accountId, identityPoolId, unAuthRoleArn, authRoleArn, cibClient, stsClient);
        if (context == null) {
            throw new IllegalArgumentException("context can't be null");
        }
        this.sharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        registerIdentityChangedListener(this);
    }

    @Override
    public void clear() {
        super.clear();

        // clear cached identity id and credentials
        sharedPreferences.edit().clear().commit();
    }

    @Override
    public String getIdentityId() {
        // try to get the ID from UserPreferences
        this.identityId = sharedPreferences.getString(ID_KEY, null);

        if (this.identityId == null) {
            super.getIdentityId();
        }

        return this.identityId;
    }

    /**
     * {@link NotAuthorizedException} is thrown when the Cognito Identity pool
     * setup is incorrect. Possible causes include: identity pool doesn't allow
     * unauth use case; identity pool doesn't support the given provider; the
     * open id token is invalid.
     */
    @Override
    synchronized public AWSSessionCredentials getCredentials() throws NotAuthorizedException {
        if (sessionCredentials == null) {
            loadCredentials();
        }
        // return only if the credentials are valid
        if (!needsNewSession()) {
            return sessionCredentials;
        }

        try {
            // super will validate loaded credentials
            // and fetch if necessary
            super.getCredentials();
        } catch (NotAuthorizedException e) {
            Log.e(TAG, "Failure to get credentials", e);
            if (logins != null) {
                // if the fetch failed, clear the credentials
                // as provided credentials don't match current id
                clear();
                super.getCredentials();
            }
            else {
                throw e;
            }
        }

        saveCredentials();

        return sessionCredentials;
    }

    /**
     * Save the credentials to SharedPreferences
     */
    private void saveCredentials() {
        Log.d(TAG, "Saving credentials to SharedPreferences");
        Calendar c = Calendar.getInstance();
        c.setTime(sessionCredentialsExpiration);
        sharedPreferences.edit()
                .putString(AK_KEY, sessionCredentials.getAWSAccessKeyId())
                .putString(SK_KEY, sessionCredentials.getAWSSecretKey())
                .putString(ST_KEY, sessionCredentials.getSessionToken())
                .putLong(EXP_KEY, c.getTimeInMillis())
                .commit();
    }

    /**
     * Load the credentials from SharedPreferences
     */
    private void loadCredentials() {
        Log.d(TAG, "Loading credentials from SharedPreferences");
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(sharedPreferences.getLong(EXP_KEY, 0));
        sessionCredentialsExpiration = c.getTime();
        String AK = sharedPreferences.getString(AK_KEY, null);
        String SK = sharedPreferences.getString(SK_KEY, null);
        String ST = sharedPreferences.getString(ST_KEY, null);

        // make sure we have valid data in SharedPreferences
        if ((AK == null) || (SK == null) || (ST == null)) {
            Log.d(TAG, "No valid credentials found in SharedPreferences");
            sessionCredentialsExpiration = null;
            return;
        }

        sessionCredentials = new BasicSessionCredentials(AK, SK, ST);
    }

    /**
     * Save the Amazon Cognito Identity Id to SharedPreferences
     */
    private void saveIdentityId() {
        Log.d(TAG, "Saving identity id to SharedPreferences");
        sharedPreferences.edit()
                .putString(ID_KEY, this.identityId)
                .commit();
    }

    @Override
    public void identityChanged(String oldIdentityId, String newIdentityId) {
        Log.d(TAG, "Identity id is changed");
        // identityId has already been updated, just save it
        saveIdentityId();
    }

    @Override
    public void setLogins(Map<String, String> logins) {
        super.setLogins(logins);
        // clear cached credentials
        sharedPreferences.edit()
                .remove(AK_KEY)
                .remove(SK_KEY)
                .remove(ST_KEY)
                .remove(EXP_KEY)
                .commit();
    }

    /**
     * Gets the cached identity id without making a network request.
     * 
     * @return cached identity id, null if it doesn't exist
     */
    public String getCachedIdentityId() {
        return sharedPreferences.getString(ID_KEY, null);
    }
}
